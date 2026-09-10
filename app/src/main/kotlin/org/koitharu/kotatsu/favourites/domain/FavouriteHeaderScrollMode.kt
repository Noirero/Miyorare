package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import org.koitharu.kotatsu.R

/** Controls whether the complete Favourites header follows list scrolling or remains fixed. */
enum class FavouriteHeaderScrollMode(
	@StringRes val titleResId: Int,
) {
	SCROLL_AWAY(R.string.favourites_scroll_header_away),
	PINNED(R.string.favourites_scroll_header_pinned),
	;

	companion object {
		const val KEY_PREFERENCE = "favourites_header_scroll_mode"

		fun current(context: Context): FavouriteHeaderScrollMode {
			val value = PreferenceManager.getDefaultSharedPreferences(context)
				.getString(KEY_PREFERENCE, SCROLL_AWAY.name)
			return entries.firstOrNull { it.name == value } ?: SCROLL_AWAY
		}
	}
}
