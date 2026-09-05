package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo

data class FavouriteSourceCount(
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "item_count") val itemCount: Int,
)
