package org.koitharu.kotatsu.sync.domain

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_PRIVATE_FAVOURITES
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extends the existing Google Drive SETTINGS sync with small continuity-only data that previously
 * lived in separate SharedPreferences files: manga notes and opt-in per-manga reader profiles.
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

		// A title may become Private-only without its note/profile changing. Rebuild on membership
		// changes so the continuity payload is scrubbed even in that case.
		database.invalidationTracker
			.createFlow(TABLE_FAVOURITES, TABLE_PRIVATE_FAVOURITES, emitInitialState = false)
			.collect { rebuildPayload() }
	}

	private fun scheduleRebuild() {
		processLifecycleScope.launch(Dispatchers.IO) { rebuildPayload() }
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
		if (rebuildAfter) rebuildPayload()
	}

	private suspend fun privateOnlyIds(): Set<Long> {
		val privateDao = database.getPrivateFavouritesDao()
		if (privateDao.isIsolationDisabled()) return emptySet()
		val privateIds = privateDao.findAllActiveMangaIds().toHashSet()
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

	private fun buildProfilesSnapshot(privateOnlyIds: Set<Long>): JSONObject {
		val ids = profilePrefs.all.keys
			.mapNotNullTo(LinkedHashSet()) { it.substringBefore(':').toLongOrNull() }
			.filter { it !in privateOnlyIds }
			.sorted()
		val result = JSONObject()
		for (id in ids) {
			val prefix = "$id:"
			if (!profilePrefs.getBoolean(prefix + PROFILE_ENABLED, false)) continue
			result.put(
				id.toString(),
				JSONObject()
					.put(PROFILE_ENABLED, true)
					.put(PROFILE_ZOOM, profilePrefs.getString(prefix + PROFILE_ZOOM, null))
					.put(PROFILE_BACKGROUND, profilePrefs.getString(prefix + PROFILE_BACKGROUND, null))
					.put(PROFILE_OPTIMIZE, profilePrefs.getBoolean(prefix + PROFILE_OPTIMIZE, false))
					.put(PROFILE_UPSCALE, profilePrefs.getBoolean(prefix + PROFILE_UPSCALE, false))
					.put(PROFILE_COLOR_32BIT, profilePrefs.getBoolean(prefix + PROFILE_COLOR_32BIT, false))
					.put(PROFILE_PAGE_NUMBERS, profilePrefs.getBoolean(prefix + PROFILE_PAGE_NUMBERS, false))
					.put(PROFILE_CROP_STANDARD, profilePrefs.getBoolean(prefix + PROFILE_CROP_STANDARD, false))
					.put(PROFILE_CROP_WEBTOON, profilePrefs.getBoolean(prefix + PROFILE_CROP_WEBTOON, false)),
			)
		}
		return result
	}

	private fun applyNotes(remote: JSONObject, privateOnlyIds: Set<Long>) {
		val remoteIds = remote.keys().asSequence().mapNotNull { it.toLongOrNull() }.toSet()
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

	private fun applyProfiles(remote: JSONObject, privateOnlyIds: Set<Long>) {
		val remoteIds = remote.keys().asSequence().mapNotNull { it.toLongOrNull() }.toSet()
		val localIds = profilePrefs.all.keys.mapNotNullTo(LinkedHashSet()) { it.substringBefore(':').toLongOrNull() }
		val editor = profilePrefs.edit()
		for (id in localIds) {
			if (id !in privateOnlyIds && id !in remoteIds) removeProfile(editor, id)
		}
		for (id in remoteIds) {
			if (id in privateOnlyIds) continue
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
		}
		editor.apply()
	}

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

	private fun payloadHash(raw: String): String? = runCatching { JSONObject(raw).optString("hash") }.getOrNull()
	private fun payloadRevision(raw: String): Long = runCatching { JSONObject(raw).optLong("revision") }.getOrDefault(0L)

	private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray())
		.joinToString("") { "%02x".format(it) }

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
	}
}
