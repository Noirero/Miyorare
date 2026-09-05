package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo

/** Lightweight aggregate used to render category badges without loading every favourite row. */
data class FavouriteCategoryCount(
	@ColumnInfo(name = "category_id") val categoryId: Long,
	@ColumnInfo(name = "item_count") val itemCount: Int,
)
