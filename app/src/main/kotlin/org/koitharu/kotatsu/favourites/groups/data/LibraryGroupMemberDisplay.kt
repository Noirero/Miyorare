package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.ColumnInfo

data class LibraryGroupMemberDisplay(
	@ColumnInfo(name = "group_id") val groupId: Long,
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "position") val position: Int,
	@ColumnInfo(name = "display_title") val displayTitle: String,
	@ColumnInfo(name = "display_cover_url") val displayCoverUrl: String?,
	@ColumnInfo(name = "is_nsfw") val isNsfw: Boolean,
	@ColumnInfo(name = "content_rating") val contentRating: String?,
	@ColumnInfo(name = "source") val source: String,
)
