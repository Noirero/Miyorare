package org.koitharu.kotatsu.favourites.ui

import android.content.Context
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_ID

internal fun Context.systemFavouriteCategoryLabel(categoryId: Long): String? {
	val labelRes = when (categoryId) {
		LOCAL_FAVOURITES_CATEGORY_ID -> R.string.favourites_system_label_local
		DOWNLOADED_FAVOURITES_CATEGORY_ID -> R.string.favourites_system_label_downloaded
		PRIVATE_IN_PROGRESS_CATEGORY_ID -> R.string.favourites_system_label_in_progress
		PRIVATE_COMPLETED_CATEGORY_ID -> R.string.favourites_system_label_completed
		else -> return null
	}
	return getString(labelRes)
}
