package org.koitharu.kotatsu.sync.domain

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_PREFERENCES
import org.koitharu.kotatsu.core.db.TABLE_PRIVATE_FAVOURITES
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extends the existing Google Drive SETTINGS sync with small continuity-only data that previously
 * lived outside its regular snapshot: manga notes and the complete opt-in per-manga reader profile.
 *
 * The bridge stores one full JSON snapshot under the default settings file. GoogleDriveSyncRepository
 * already synchronizes unknown/default preference keys, so manual and background sync both carry it
 * without a second cloud protocol. Private-only manga are removed before the payload is generated.
 * Downloaded archives/pages are intentionally never part of this payload.
 */
@Singleton
class CrossDeviceContinuity @Inject constructor(
	@ApplicationContext context: Context,
	private val database: MangaDatabase,
) {

	private val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
	private val notesPrefs = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)
	private val profilePrefs = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
	private val started = AtomicBoolean(false)
	private val rebuildRunning = AtomicBoolean(false)
	private val rebuildDirty = AtomicBoolean(false)
	private val mutex = Mutex()
	@Volatile private var applyingPayload = false
	@Volatile private var lastLocallyWrittenPayload: String? = null

	private val notesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if (key?.toLongOrNull() != null && !applyingPayload) scheduleRebuild()
	}
	private val profileListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if (key?.substringBefore(':')?.toLongOrNull() != null && !applyingPayload) scheduleRebuild()
	}
	private val defaultListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
		if (key != PAYLOAD_KEY) return@OnSharedPreferenceChangeListener
		val raw = prefs.getString(PAYLOAD_KEY, null) ?: return@OnSharedPreferenceChangeListener
		if (raw == lastLocallyWrittenPayload) return@OnSharedPreferenceChangeListener
		processLifecycleScope.launch(Dispatchers.IO) { applyIncoming(raw) }
	}

	/** Starts process-wide bridging and privacy scrubbing. The function collects for process life. */
	suspend fun start() {
		if (!started.compareAndSet(false, true)) return

		// Apply a payload restored by settings/cloud before publishing a new local snapshot. On a fresh
		// install there is no payload, so existing local notes/profiles simply become the first one.
		defaultPrefs.getString(PAYLOAD_KEY, null)?.let { applyIncoming(it, rebuildAfter = false) }
		rebuildPayload()

		notesPrefs.registerOnSharedPreferenceChangeListener(notesListener)
		profilePrefs.registerOnSharedPreferenceChangeListener(profileListener)
		defaultPrefs.registerOnSharedPreferenceChangeListener(defaultListener)

		// Membership changes can make a title Private-only, while preferences changes include Reader
		// Mode/Color Filter which live in Room rather than the profile SharedPreferences file.
		database.invalidationTracker
			.createFlow(TABLE_FAVOURITES, TABLE_PRIVATE_FAVOURITES, TABLE_PREFERENCES, emitInitialState = false)
			.collect { if (!applyingPayload) scheduleRebuild() }
	}

	private fun scheduleRebuild() {
		rebuildDirty.set(true)
		if (!rebuildRunning.compareAndSet(false, true)) return
		processLifecycleScope.launch(Dispatchers.IO) {
			try {
				do {
					rebuildDirty.set(false)
					rebuildPayload()
				} while (rebuildDirty.get())
			} finally {
				rebuildRunning.set(false)
				// Close the tiny race where a listener marks dirty after the loop condition but before
				// rebuildRunning becomes false.
				if (rebuildDirty.get()) scheduleRebuild()
			}
		}
	}

	private suspend fun rebuildPayload() = mutex.withLock {
		if (applyingPayload) return@withLock
		val privateOnlyIds = privateOnlyIds()
		val notes = buildNotesSnapshot(privateOnlyIds)
		val profiles = buildProfilesSnapshot(privateOnlyIds)
		val canonical = JSONObject()
			.put("notes", notes)
			.put("profiles", profiles)
			.toString()
		val hash = sha256(canonical)
		val currentRaw = defaultPrefs.getString(PAYLOAD_KEY, null)
		val currentHash = currentRaw?.let(::payloadHash)
		if (hash == currentHash) return@withLock

		val previousRevision = currentRaw?.let(::payloadRevision) ?: 0L
		val revision = maxOf(System.currentTimeMillis(), previousRevision + 1L)
		val payload = JSONObject()
			.put("version", PAYLOAD_VERSION)
			.put("revision", revision)
			.put("hash", hash)
			.put("notes", notes)
			.put("profiles", profiles)
			.toString()
		lastLocallyWrittenPayload = payload
		defaultPrefs.edit().putString(PAYLOAD_KEY, payload).apply()
	}

	private suspend fun applyIncoming(raw: String, rebuildAfter: Boolean = true) {
		val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return
		if (parsed.optInt("version", 0) != PAYLOAD_VERSION) return
		mutex.withLock {
			val privateOnlyIds = privateOnlyIds()
			val remoteNotes = parsed.optJSONObject("notes") ?: JSONObject()
			val remoteProfiles = parsed.optJSONObject("profiles") ?: JSONObject()
			applyingPayload = true
			try {
				applyNotes(remoteNotes, privateOnlyIds)
				applyProfiles(remoteProfiles, privateOnlyIds)
			} finally {
				applyingPayload = false
			}
			lastLocallyWrittenPayload = raw
		}
		// If this device isolates a title that another device keeps Normal, immediately rewrite a
		// sanitized payload rather than re-uploading the incoming Private-sensitive entry.
		if (rebuildAfter) scheduleRebuild()
	}

	private suspend fun privateOnlyIds(): Set<Long> {
		val privateIds = database.getPrivateFavouritesDao().findAllActiveMangaIds().toHashSet()
		if (privateIds.isEmpty()) return emptySet()
		val normalIds = database.getFavouritesDao().findMemberships().mapTo(HashSet()) { it.mangaId }
		privateIds.removeAll(normalIds)
		return privateIds
	}

	private fun buildNotesSnapshot(privateOnlyIds: Set<Long>): JSONObject {
		val result = JSONObject()
		val entries = notesPrefs.all.entries
			.mapNotNull { (key, value) ->
				val id = key.toLongOrNull() ?: return@mapNotNull null
				if (id in privateOnlyIds) return@mapNotNull null
				val text = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
				id to text
			}
			.sortedBy { it.first }
		for ((id, text) in entries) result.put(id.toString(), text)
		return result
	}

	private suspend fun buildProfilesSnapshot(privateOnlyIds: Set<Long>): JSONObject {
		val ids = profilePrefs.all.keys
			.mapNotNullTo(LinkedHashSet()) { it.substringBefore(':').toLongOrNull() }
			.filter { it !in privateOnlyIds && profilePrefs.getBoolean("$it:$PROFILE_ENABLED", false) }
			.sorted()
		val readerPrefs = if (ids.isEmpty()) {
			emptyMap()
		} else {
			database.getPreferencesDao().findAll(ids).associateBy { it.mangaId }
		}
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

	private fun applyNotes(remote: JSONObject, privateOnlyIds: Set<Long>) {
		val remoteIds = remote.longKeys()
		val editor = notesPrefs.edit()
		for (key in notesPrefs.all.keys) {
			val id = key.toLongOrNull() ?: continue
			if (id !in privateOnlyIds && id !in remoteIds) editor.remove(key)
		}
		for (id in remoteIds) {
			if (id in privateOnlyIds) continue
			val text = remote.optString(id.toString()).trim()
			if (text.isEmpty()) editor.remove(id.toString()) else editor.putString(id.toString(), text)
		}
		editor.apply()
	}

	private suspend fun applyProfiles(remote: JSONObject, privateOnlyIds: Set<Long>) {
		val remoteIds = remote.longKeys()
		val eligibleRemoteIds = remoteIds.filterTo(LinkedHashSet()) { it !in privateOnlyIds }
		val localIds = profilePrefs.all.keys.mapNotNullTo(LinkedHashSet()) { it.substringBefore(':').toLongOrNull() }
		val editor = profilePrefs.edit()
		for (id in localIds) {
			if (id !in privateOnlyIds && id !in eligibleRemoteIds) removeProfile(editor, id)
		}

		val preferencesDao = database.getPreferencesDao()
		val existingPrefs = if (eligibleRemoteIds.isEmpty()) {
			emptyMap()
		} else {
			preferencesDao.findAll(eligibleRemoteIds).associateBy { it.mangaId }
		}
		val existingMangaIds = if (eligibleRemoteIds.isEmpty()) {
			emptySet()
		} else {
			database.getMangaDao().findByIds(eligibleRemoteIds).mapTo(HashSet()) { it.manga.mangaId }
		}

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

	private fun payloadHash(raw: String): String? = runCatching {
		JSONObject(raw).optString("hash").takeIf { it.isNotBlank() }
	}.getOrNull()

	private fun payloadRevision(raw: String): Long = runCatching { JSONObject(raw).optLong("revision") }.getOrDefault(0L)

	private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray())
		.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

	private companion object {
		const val PAYLOAD_VERSION = 1
		const val PAYLOAD_KEY = "miyorare_cross_device_continuity_v1"
		const val NOTES_PREFS = "manga_notes"
		const val PROFILE_PREFS = "manga_reader_profiles"
		const val PROFILE_ENABLED = "enabled"
		const val PROFILE_ZOOM = "zoom_mode"
		const val PROFILE_BACKGROUND = "background"
		const val PROFILE_OPTIMIZE = "optimize"
		const val PROFILE_UPSCALE = "upscale"
		const val PROFILE_COLOR_32BIT = "color_32bit"
		const val PROFILE_PAGE_NUMBERS = "page_numbers"
		const val PROFILE_CROP_STANDARD = "crop_standard"
		const val PROFILE_CROP_WEBTOON = "crop_webtoon"
		const val READER_MODE = "reader_mode"
		const val CF_BRIGHTNESS = "cf_brightness"
		const val CF_CONTRAST = "cf_contrast"
		const val CF_INVERT = "cf_invert"
		const val CF_GRAYSCALE = "cf_grayscale"
		const val CF_BOOK = "cf_book"
	}
}
