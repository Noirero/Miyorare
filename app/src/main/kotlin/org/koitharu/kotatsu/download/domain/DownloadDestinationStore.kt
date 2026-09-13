package org.koitharu.kotatsu.download.domain

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user-selected download root for each favourites space.
 *
 * A configured value is always a parent/root directory. [LocalMangaOutput] owns the physical
 * `downloads/...` layout below it, so callers must never append `downloads` themselves.
 *
 * NORMAL keeps using the legacy [AppSettings.KEY_LOCAL_STORAGE] preference for backward
 * compatibility, but reads its raw path here instead of [AppSettings.mangaStorageDir]. The latter
 * intentionally hides temporarily unavailable/unreadable folders, which is useful for generic
 * storage selection but unsafe for a strict download destination: an unmounted SD card must not
 * silently turn the configured Normal root into another fallback directory.
 *
 * When NORMAL has never been configured, [effectiveRoot] resolves the same app-owned fallback used
 * by LocalStorageManager. This keeps even a fresh install on an explicit root, so a later Private
 * custom directory can never be selected accidentally by a legacy `destination = null` lookup.
 *
 * PRIVATE has an independent optional root; until the user chooses one it follows NORMAL so
 * existing installations keep working without moving any files. Previous roots are retained per
 * space for read-only discovery after a destination change; new writes always use [effectiveRoot].
 */
@Singleton
class DownloadDestinationStore @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	fun configuredRoot(space: FavouriteSpace): File? = when (space) {
		// Preserve the selected path even while an SD card is temporarily unavailable. The legacy
		// AppSettings getter filters such paths out, but the raw preference remains authoritative.
		FavouriteSpace.NORMAL -> prefs.getString(AppSettings.KEY_LOCAL_STORAGE, null)?.let(::File)
		// Keep the selected path even while an SD card is temporarily unavailable. A Private task
		// must fail/retry rather than silently spill into the Normal destination.
		FavouriteSpace.PRIVATE -> prefs.getString(KEY_PRIVATE_DOWNLOAD_ROOT, null)?.let(::File)
	}

	/** PRIVATE follows NORMAL only while no dedicated Private root has ever been chosen. */
	fun effectiveRoot(space: FavouriteSpace): File? = when (space) {
		FavouriteSpace.NORMAL -> configuredRoot(space) ?: defaultNormalRoot()
		FavouriteSpace.PRIVATE -> configuredRoot(space) ?: effectiveRoot(FavouriteSpace.NORMAL)
	}

	/**
	 * Roots that may contain downloads belonging to [space], ordered with the active destination
	 * first. Legacy roots are read-only candidates and are never selected for a new download task.
	 */
	fun readableRoots(space: FavouriteSpace): List<File> {
		val roots = LinkedHashSet<File>()
		effectiveRoot(space)?.let(roots::add)
		readLegacyRoots(space).forEach(roots::add)
		// While Private follows Normal it also inherits Normal's previous roots, matching the period
		// where both spaces intentionally shared one destination.
		if (space == FavouriteSpace.PRIVATE && !privateUsesOwnRoot()) {
			readLegacyRoots(FavouriteSpace.NORMAL).forEach(roots::add)
		}
		return roots.toList()
	}

	/**
	 * Local folders owned by the currently active favourites destination.
	 *
	 * Legacy download roots are intentionally excluded here: changing a destination must also
	 * change the virtual Lokal shelf instead of merging old and new destinations. Both `local`
	 * and `lokal` are accepted case-insensitively.
	 */
	fun localRoots(space: FavouriteSpace): List<File> {
		val destination = effectiveRoot(space) ?: return emptyList()
		if (!destination.isDirectory || !destination.canRead()) return emptyList()
		return destination.listFiles()
			?.filter { child -> child.isDirectory && child.name.isLocalFolderName() }
			.orEmpty()
	}

	fun setRoot(space: FavouriteSpace, root: File?) {
		// Validate/create the new target first. If this fails, neither the active preference nor the
		// legacy-root history is changed.
		if (root != null) {
			val downloads = File(root, LocalMangaOutput.DOWNLOADS_DIR_NAME)
			check(downloads.isDirectory || downloads.mkdirs()) { "Cannot create downloads directory under $root" }
		}

		val previousEffective = effectiveRoot(space)
		if (previousEffective != null && !previousEffective.samePathAs(root)) {
			rememberLegacyRoot(space, previousEffective)
		}
		if (root != null) {
			// Keep custom roots in Local Storage's configured/readable set so downloads remain indexed
			// after restart and legacy lookup can still find files without a special scanner.
			settings.userSpecifiedMangaDirectories += root
			forgetLegacyRoot(space, root)
		}
		when (space) {
			FavouriteSpace.NORMAL -> settings.mangaStorageDir = root
			FavouriteSpace.PRIVATE -> prefs.edit {
				if (root == null) remove(KEY_PRIVATE_DOWNLOAD_ROOT) else putString(KEY_PRIVATE_DOWNLOAD_ROOT, root.path)
			}
		}
	}

	/** Explicit removal from Manage folders means this path should no longer be used even as legacy. */
	fun forgetRoot(root: File) {
		forgetLegacyRoot(FavouriteSpace.NORMAL, root)
		forgetLegacyRoot(FavouriteSpace.PRIVATE, root)
	}

	fun privateUsesOwnRoot(): Boolean = prefs.contains(KEY_PRIVATE_DOWNLOAD_ROOT)

	fun rootsOverlap(): Boolean {
		// Private following Normal is intentional and is explained separately in the UI. Warn only
		// when the user explicitly configured Private to the very same effective Normal directory.
		if (!privateUsesOwnRoot()) return false
		val normal = effectiveRoot(FavouriteSpace.NORMAL)?.canonicalOrAbsolute() ?: return false
		val privateRoot = configuredRoot(FavouriteSpace.PRIVATE)?.canonicalOrAbsolute() ?: return false
		return normal == privateRoot
	}

	private fun defaultNormalRoot(): File? {
		val external = context.getExternalFilesDir(DEFAULT_STORAGE_DIR_NAME)
		if (external != null && (external.isDirectory || external.mkdirs())) return external
		val internal = File(context.filesDir, DEFAULT_STORAGE_DIR_NAME)
		return internal.takeIf { it.isDirectory || it.mkdirs() }
	}

	private fun readLegacyRoots(space: FavouriteSpace): List<File> =
		prefs.getStringSet(legacyKey(space), emptySet()).orEmpty().map(::File)

	private fun rememberLegacyRoot(space: FavouriteSpace, root: File) {
		val key = legacyKey(space)
		val paths = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
		if (paths.add(root.path)) prefs.edit { putStringSet(key, paths) }
	}

	private fun forgetLegacyRoot(space: FavouriteSpace, root: File) {
		val key = legacyKey(space)
		val paths = prefs.getStringSet(key, emptySet()).orEmpty()
		val updated = paths.filterNotTo(LinkedHashSet()) { File(it).samePathAs(root) }
		if (updated.size != paths.size) prefs.edit { putStringSet(key, updated) }
	}

	private fun legacyKey(space: FavouriteSpace): String = when (space) {
		FavouriteSpace.NORMAL -> KEY_NORMAL_LEGACY_DOWNLOAD_ROOTS
		FavouriteSpace.PRIVATE -> KEY_PRIVATE_LEGACY_DOWNLOAD_ROOTS
	}

	private fun String.isLocalFolderName(): Boolean =
		equals("local", ignoreCase = true) || equals("lokal", ignoreCase = true)

	private fun File.samePathAs(other: File?): Boolean =
		other != null && canonicalOrAbsolute() == other.canonicalOrAbsolute()

	private fun File.canonicalOrAbsolute(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)

	companion object {
		const val KEY_PRIVATE_DOWNLOAD_ROOT = "private_download_root"
		private const val KEY_NORMAL_LEGACY_DOWNLOAD_ROOTS = "normal_download_legacy_roots"
		private const val KEY_PRIVATE_LEGACY_DOWNLOAD_ROOTS = "private_download_legacy_roots"
		private const val DEFAULT_STORAGE_DIR_NAME = "manga"
	}
}
