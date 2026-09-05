package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo

/**
 * Lightweight projection used for library category counts.
 *
 * Counting badges only needs the favourite/category relationship and the stored source name. Keeping
 * this separate from [FavouriteManga] avoids materialising Manga details and tags for every category.
 */
data class FavouriteMembership(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "category_id") val categoryId: Long,
	@ColumnInfo(name = "source") val source: String,
)
