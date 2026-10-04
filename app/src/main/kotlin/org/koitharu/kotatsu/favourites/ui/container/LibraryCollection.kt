package org.koitharu.kotatsu.favourites.ui.container

import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID

internal enum class LibraryCollection(val titleRes: Int) {
	FAVOURITES(R.string.favourites),
	DOWNLOADED(R.string.downloaded),
	LOCAL(R.string.local_storage);

	fun categories(items: List<FavouriteTabModel>): List<FavouriteTabModel> = when (this) {
		FAVOURITES -> items.filterNot {
			it.id == DOWNLOADED_FAVOURITES_CATEGORY_ID || it.id == LOCAL_FAVOURITES_CATEGORY_ID
		}
		DOWNLOADED -> items.filter { it.id == DOWNLOADED_FAVOURITES_CATEGORY_ID }
		LOCAL -> items.filter { it.id == LOCAL_FAVOURITES_CATEGORY_ID }
	}
}
