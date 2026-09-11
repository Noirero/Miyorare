package org.koitharu.kotatsu.favourites.domain

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/** Controls whether Favourites/Downloaded metadata is paged or fully prepared. */
enum class FavouriteListLoadingMode(@StringRes val titleResId: Int) {
	PAGED(R.string.favourites_loading_mode_paged),
	FULL(R.string.favourites_loading_mode_full),
}
