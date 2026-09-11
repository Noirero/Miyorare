package org.koitharu.kotatsu.sync.domain

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small on-demand companion to Google Drive sync for data that lives outside regular Room sync rows:
 * manga notes and the complete opt-in per-manga reader profile.
 *
 * There is deliberately no process-wide listener, startup observer, worker, or independent cloud
 * protocol here. GoogleDriveSyncRepository exports/applies this payload only while SETTINGS sync is
 * already running. That keeps the feature inert outside Sync and removes the startup race where an
 * old cached payload could overwrite newer local notes/profiles.
 */
@Singleton
class CrossDeviceContinuity @Inject constructor(
	@ApplicationContext context: Context,
	private val database: MangaDatabase,
) {

	private val notesPrefs = context.getSharedPreferences(MangaNotesRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
	private val profilePrefs = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
	private val mutex = Mutex()

	/** Build one authoritative public/Normal snapshot for the existing SETTINGS sync transaction. */
	suspend fun exportPayload(): String = mutex.withLock {
		val protectedIds = computePrivacyProtectedIds()
		JSONObject()
			.put("version", PAYLOAD_VERSION)
			.put("notes", buildNotesSnapshot(protectedIds))
			.put("profiles", buildProfilesSnapshot(protectedIds))
			.toString()
	}

	/** Same privacy boundary used by local export, remote scrub, and incoming apply. */
	suspend fun privacyProtectedIds(): Set<Long> = mutex.withLock { computePrivacyProtectedIds() }

	/** Apply a payload only after Google Drive config merge explicitly selected the remote config. */
	suspend fun applyPayload(raw: String) = mutex.withLock {
		val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return@withLock
		if (parsed.optInt("version", 0) != PAYLOAD_VERSION) return@withLock
		val protectedIds = computePrivacyProtectedIds()
		applyNotes(parsed.optJSONObject("notes") ?: JSONObject(), protectedIds)
		applyProfiles(parsed.optJSONObject("profiles") ?: JSONObject(), protectedIds)
	}

	/**
	 * Remove protected ids from a remote payload before it is merged/re-uploaded. Malformed or
	 * unknown payloads are dropped rather than risking disclosure of manga-specific data.
	 */
	fun scrubPayload(raw: String?, protectedIds: Set<Long>): String? {
		if (raw == null || protectedIds.isEmpty()) return raw
		val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return null
		if (parsed.optInt("version", 0) != PAYLOAD_VERSION) return null
		val notes = parsed.optJSONObject("notes") ?: JSONObject()
		val profiles = parsed.optJSONObject("profiles") ?: JSONObject()
		for (id in protectedIds) {
			notes.remove(id.toString())
			profiles.remove(id.toString())
		}
		return JSONObject()
			.put("version", PAYLOAD_VERSION)
			.put("notes", notes)
			.put("profiles", profiles)
			.toString()
	}

	private suspend fun computePrivacyProtectedIds(): Set<Long> {
		val privateIds = database.getPrivateFavouritesDao().findAllActiveMangaIds().toHashSet()
		val retiredIds = LinkedHashSet<Long>()
		for (key in notesPrefs.all.keys) {
			if (!key.startsWith(MangaNotesRepository.PRIVATE_MIGRATION_PREFIX)) continue
			key.removePrefix(MangaNotesRepository.PRIVATE_MIGRATION_PREFIX).toLongOrNull()?.let(retiredIds::add)
		}

		val candidates = LinkedHashSet<Long>(privateIds.size + retiredIds.size).apply {
			addAll(privateIds)
			addAll(retiredIds)
		}
		if (candidates.isEmpty()) return emptySet()

		val normalIds = candidates.chunked(DB_QUERY_BATCH_SIZE)
			.flatMap { database.getFavouritesDao().findMemberships(it) }
			.mapTo(HashSet()) { it.mangaId }
		privateIds.removeAll(normalIds)

		// A retired id remains protected across restarts/syncs so old cloud payloads can be scrubbed.
		// Deliberately putting that id in Normal Favourites makes it public again and retires the guard.
		val guardsToClear = retiredIds.intersect(normalIds)
		if (guardsToClear.isNotEmpty()) {
			val editor = notesPrefs.edit()
			for (id in guardsToClear) editor.remove(MangaNotesRepository.PRIVATE_MIGRATION_PREFIX + id)
			editor.apply()
			retiredIds.removeAll(guardsToClear)
		}

		privateIds.addAll(retiredIds)
		return privateIds
	}

	private fun buildNotesSnapshot(protectedIds: Set<Long>): JSONObject {
		val result = JSONObject()
		val entries = notesPrefs.all.entries
			.mapNotNull { (key, value) ->
				val id = key.toLongOrNull() ?: return@mapNotNull null
				if (id in protectedIds) return@mapNotNull null
				val text = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
				id to text
			}
			.sortedBy { it.first }
		for ((id, text) in entries) result.put(id.toString(), text)
		return result
	}

	private suspend fun buildProfilesSnapshot(protectedIds: Set<Long>): JSONObject {
		val ids = profilePrefs.all.keys
			.mapNotNullTo(LinkedHashSet()) { it.substringBefore(':').toLongOrNull() }
			.filter { it !in protectedIds && profilePrefs.getBoolean("$it:$PROFILE_ENABLED", false) }
			.sorted()
		val readerPrefs = ids.chunked(DB_QUERY_BATCH_SIZE)
			.flatMap { database.getPreferencesDao().findAll(it) }
			.associateBy { it.mangaId }
		val result = JSONObject()
		for (id in ids) {
			val prefix = "$id:"
			val item = JSONObject()
				.put(PROFILE_ENABLED, true)
				.put(PROFILE_ZOOM, profilePrefs.getString(prefix + PROFILE_ZOOM, null))
				.put(PROFILE_BACKGROUND, profilePrefs.getString(prefix + PROFILE_BACKGROUND, null))
				.put(PROFILE_OPTIMIZE, profilePrefs.getBoolean(prefix + PROFILE_OPTIMIZE, false))
				.put(PROFILE_UPSCALE, profilePrefs.getBoolean(prefix + PROFILE_UPSCALE, false))
				.put(PROFILE_COLOR_32BIT, profilePrefs.getBoolean(prefix + PROFILE_COLOR_32BIT, false))
				.put(PROFILE_PAGE_NUMBERS, profilePrefs.getBoolean(prefix + PROFILE_PAGE_NUMBERS, false))
				.put(PROFILE_CROP_STANDARD, profilePrefs.getBoolean(prefix + PROFILE_CROP_STANDARD, false))
				.put(PROFILE_CROP_WEBTOON, profilePrefs.getBoolean(prefix + PROFILE_CROP_WEBTOON, false))
			readerPrefs[id]?.let { pref ->
				item.put(READER_MODE, pref.mode)
					.put(CF_BRIGHTNESS, pref.cfBrightness.toDouble())
					.put(CF_CONTRAST, pref.cfContrast.toDouble())
					.put(CF_INVERT, pref.cfInvert)
					.put(CF_GRAYSCALE, pref.cfGrayscale)
					.put(CF_BOOK, pref.cfBookEffect)
			}
			result.put(id.toString(), item)
		}
		return result
	}

	private fun applyNotes(remote: JSONObject, protectedIds: Set<Long>) {
		val remoteIds = remote.longKeys()
		val editor = notesPrefs.edit()
		for (key in notesPrefs.all.keys) {
			val id = key.toLongOrNull() ?: continue
			if (id !in protectedIds && id !in remoteIds) editor.remove(key)
		}
		for (id in remoteIds) {
			if (id in protectedIds) continue
			val text = remote.optString(id.toString()).trim()
			if (text.isEmpty()) editor.remove(id.toString()) else editor.putString(id.toString(), text)
		}
		editor.apply()
	}

	private suspend fun applyProfiles(remote: JSONObject, protectedIds: Set<Long>) {
		val remoteIds = remote.longKeys()
		val eligibleRemoteIds = remoteIds.filterTo(LinkedHashSet()) { it !in protectedIds }
		val localIds = profilePrefs.all.keys.mapNotNullTo(LinkedHashSet()) { it.substringBefore(':').toLongOrNull() }
		val editor = profilePrefs.edit()
		for (id in localIds) {
			if (id !in protectedIds && id !in eligibleRemoteIds) removeProfile(editor, id)
		}

		val preferencesDao = database.getPreferencesDao()
		val existingPrefs = eligibleRemoteIds.chunked(DB_QUERY_BATCH_SIZE)
			.flatMap { preferencesDao.findAll(it) }
			.associateBy { it.mangaId }
		val existingMangaIds = eligibleRemoteIds.chunked(DB_QUERY_BATCH_SIZE)
			.flatMap { database.getMangaDao().findByIds(it) }
			.mapTo(HashSet()) { it.manga.id }

		database.withTransaction {
			for (id in eligibleRemoteIds) {
				val profile = remote.optJSONObject(id.toString()) ?: continue
				val prefix = "$id:"
				editor.putBoolean(prefix + PROFILE_ENABLED, profile.optBoolean(PROFILE_ENABLED, true))
				profile.optString(PROFILE_ZOOM).takeIf { it.isNotBlank() }?.let { editor.putString(prefix + PROFILE_ZOOM, it) }
				profile.optString(PROFILE_BACKGROUND).takeIf { it.isNotBlank() }?.let { editor.putString(prefix + PROFILE_BACKGROUND, it) }
				editor.putBoolean(prefix + PROFILE_OPTIMIZE, profile.optBoolean(PROFILE_OPTIMIZE, false))
				editor.putBoolean(prefix + PROFILE_UPSCALE, profile.optBoolean(PROFILE_UPSCALE, false))
				editor.putBoolean(prefix + PROFILE_COLOR_32BIT, profile.optBoolean(PROFILE_COLOR_32BIT, false))
				editor.putBoolean(prefix + PROFILE_PAGE_NUMBERS, profile.optBoolean(PROFILE_PAGE_NUMBERS, false))
				editor.putBoolean(prefix + PROFILE_CROP_STANDARD, profile.optBoolean(PROFILE_CROP_STANDARD, false))
				editor.putBoolean(prefix + PROFILE_CROP_WEBTOON, profile.optBoolean(PROFILE_CROP_WEBTOON, false))

				if (id !in existingMangaIds || !profile.has(READER_MODE)) continue
				val current = existingPrefs[id] ?: emptyPrefs(id)
				preferencesDao.upsert(
					current.copy(
						mode = profile.optInt(READER_MODE, current.mode),
						cfBrightness = profile.optDouble(CF_BRIGHTNESS, current.cfBrightness.toDouble()).toFloat(),
						cfContrast = profile.optDouble(CF_CONTRAST, current.cfContrast.toDouble()).toFloat(),
						cfInvert = profile.optBoolean(CF_INVERT, current.cfInvert),
						cfGrayscale = profile.optBoolean(CF_GRAYSCALE, current.cfGrayscale),
						cfBookEffect = profile.optBoolean(CF_BOOK, current.cfBookEffect),
					),
				)
			}
		}
		editor.apply()
	}

	private fun emptyPrefs(mangaId: Long) = MangaPrefsEntity(
		mangaId = mangaId,
		mode = -1,
		cfBrightness = 0f,
		cfContrast = 0f,
		cfInvert = false,
		cfGrayscale = false,
		cfBookEffect = false,
		titleOverride = null,
		coverUrlOverride = null,
		contentRatingOverride = null,
		authorOverride = null,
		artistOverride = null,
		descriptionOverride = null,
		mergeScanlators = false,
	)

	private fun removeProfile(editor: SharedPreferences.Editor, mangaId: Long) {
		val prefix = "$mangaId:"
		editor.remove(prefix + PROFILE_ENABLED)
			.remove(prefix + PROFILE_ZOOM)
			.remove(prefix + PROFILE_BACKGROUND)
			.remove(prefix + PROFILE_OPTIMIZE)
			.remove(prefix + PROFILE_UPSCALE)
			.remove(prefix + PROFILE_COLOR_32BIT)
			.remove(prefix + PROFILE_PAGE_NUMBERS)
			.remove(prefix + PROFILE_CROP_STANDARD)
			.remove(prefix + PROFILE_CROP_WEBTOON)
	}

	private fun JSONObject.longKeys(): Set<Long> = buildSet {
		val iterator = keys()
		while (iterator.hasNext()) iterator.next().toLongOrNull()?.let(::add)
	}

	companion object {
		/** Legacy experimental key that must never be uploaded through generic app settings again. */
		const val LEGACY_SETTINGS_KEY = "miyorare_cross_device_continuity_v1"
		private const val PAYLOAD_VERSION = 1
		private const val DB_QUERY_BATCH_SIZE = 500
		private const val PROFILE_PREFS = "manga_reader_profiles"
		private const val PROFILE_ENABLED = "enabled"
		private const val PROFILE_ZOOM = "zoom_mode"
		private const val PROFILE_BACKGROUND = "background"
		private const val PROFILE_OPTIMIZE = "optimize"
		private const val PROFILE_UPSCALE = "upscale"
		private const val PROFILE_COLOR_32BIT = "color_32bit"
		private const val PROFILE_PAGE_NUMBERS = "page_numbers"
		private const val PROFILE_CROP_STANDARD = "crop_standard"
		private const val PROFILE_CROP_WEBTOON = "crop_webtoon"
		private const val READER_MODE = "reader_mode"
		private const val CF_BRIGHTNESS = "cf_brightness"
		private const val CF_CONTRAST = "cf_contrast"
		private const val CF_INVERT = "cf_invert"
		private const val CF_GRAYSCALE = "cf_grayscale"
		private const val CF_BOOK = "cf_book"
	}
}
