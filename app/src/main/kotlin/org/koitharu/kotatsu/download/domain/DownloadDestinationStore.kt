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
 * NORMAL deliberately keeps using [AppSettings.mangaStorageDir] for backward compatibility.
 * PRIVATE has an independent optional root; until the user chooses one it follows NORMAL so
 * existing installations keep working without moving any files.
 */
@Singleton
class DownloadDestinationStore @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	fun configuredRoot(space: FavouriteSpace): File? = when (space) {
		FavouriteSpace.NORMAL -> settings.mangaStorageDir
		// Keep the selected path even while an SD card is temporarily unavailable. A Private task
		// must fail/retry rather than silently spill into the Normal destination.
		FavouriteSpace.PRIVATE -> prefs.getString(KEY_PRIVATE_DOWNLOAD_ROOT, null)?.let(::File)
	}

	/** PRIVATE follows NORMAL only while no dedicated Private root has ever been chosen. */
	fun effectiveRoot(space: FavouriteSpace): File? =
		configuredRoot(space) ?: if (space == FavouriteSpace.PRIVATE) configuredRoot(FavouriteSpace.NORMAL) else null

	fun setRoot(space: FavouriteSpace, root: File?) {
		if (root != null) {
			// The selected directory is the parent/root. Create the conventional child first so a
			// failed filesystem write never leaves a half-persisted destination preference behind.
			val downloads = File(root, LocalMangaOutput.DOWNLOADS_DIR_NAME)
			check(downloads.isDirectory || downloads.mkdirs()) { "Cannot create downloads directory under $root" }
			// Keep custom roots in Local Storage's configured/readable set so downloads remain indexed
			// after restart and legacy lookup can still find files without a special scanner.
			settings.userSpecifiedMangaDirectories += root
		}
		when (space) {
			FavouriteSpace.NORMAL -> settings.mangaStorageDir = root
			FavouriteSpace.PRIVATE -> prefs.edit {
				if (root == null) remove(KEY_PRIVATE_DOWNLOAD_ROOT) else putString(KEY_PRIVATE_DOWNLOAD_ROOT, root.path)
			}
		}
	}

	fun privateUsesOwnRoot(): Boolean = prefs.contains(KEY_PRIVATE_DOWNLOAD_ROOT)

	fun rootsOverlap(): Boolean {
		val normal = configuredRoot(FavouriteSpace.NORMAL)?.canonicalOrAbsolute() ?: return false
		val privateRoot = configuredRoot(FavouriteSpace.PRIVATE)?.canonicalOrAbsolute() ?: return false
		return normal == privateRoot
	}

	private fun File.canonicalOrAbsolute(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)

	companion object {
		const val KEY_PRIVATE_DOWNLOAD_ROOT = "private_download_root"
	}
}
