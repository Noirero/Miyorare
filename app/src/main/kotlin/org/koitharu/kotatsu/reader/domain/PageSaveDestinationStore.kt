package org.koitharu.kotatsu.reader.domain

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps saved-page destinations isolated between Normal and Private favourites.
 *
 * Normal intentionally reuses the legacy AppSettings page directory so existing users keep their
 * current destination. Private has its own preference and never falls back to Normal: if the
 * Private destination is unset or unavailable, callers must ask the user where to save instead of
 * leaking a Private page into the Normal directory.
 */
@Singleton
class PageSaveDestinationStore @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	fun getDirectory(space: FavouriteSpace): DocumentFile? = when (space) {
		FavouriteSpace.NORMAL -> settings.getPagesSaveDir(context)
		FavouriteSpace.PRIVATE -> prefs.getString(KEY_PRIVATE_PAGES_SAVE_DIR, null)
			?.toUri()
			?.let { DocumentFile.fromTreeUri(context, it) }
			?.takeIf { it.isDirectory && it.canWrite() }
	}

	fun setDirectory(space: FavouriteSpace, uri: Uri?) {
		when (space) {
			FavouriteSpace.NORMAL -> settings.setPagesSaveDir(uri)
			FavouriteSpace.PRIVATE -> prefs.edit {
				if (uri == null) {
					remove(KEY_PRIVATE_PAGES_SAVE_DIR)
				} else {
					putString(KEY_PRIVATE_PAGES_SAVE_DIR, uri.toString())
				}
			}
		}
	}

	companion object {
		const val KEY_PRIVATE_PAGES_SAVE_DIR = "private_pages_save_dir"
	}
}
