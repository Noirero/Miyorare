package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.koitharu.kotatsu.core.db.TABLE_LIBRARY_GROUP_TIMELINE

@Entity(
	tableName = TABLE_LIBRARY_GROUP_TIMELINE,
	primaryKeys = ["group_id", "manga_id", "chapter_id"],
	foreignKeys = [
		ForeignKey(
			entity = LibraryGroupMemberEntity::class,
			parentColumns = ["group_id", "manga_id"],
			childColumns = ["group_id", "manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["group_id", "manga_id"]),
		Index(value = ["group_id", "position"]),
	],
)
data class LibraryGroupTimelineItemEntity(
	@ColumnInfo(name = "group_id") val groupId: Long,
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "position") val position: Int,
)
