package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo

/** Lightweight searchable metadata for a favourite, without Manga relations/tags/covers. */
data class FavouriteSearchEntry(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "author") val authors: String?,
	@ColumnInfo(name = "source") val source: String,
)
