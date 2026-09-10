package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.koitharu.kotatsu.core.db.TABLE_LIBRARY_GROUP_MEMBERS
import org.koitharu.kotatsu.core.db.entity.MangaEntity

@Entity(
	tableName = TABLE_LIBRARY_GROUP_MEMBERS,
	primaryKeys = ["group_id", "manga_id"],
	foreignKeys = [
		ForeignKey(
			entity = LibraryGroupEntity::class,
			parentColumns = ["group_id"],
			childColumns = ["group_id"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["group_id"]),
		Index(value = ["manga_id"]),
	],
)
data class LibraryGroupMemberEntity(
	@ColumnInfo(name = "group_id") val groupId: Long,
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "position") val position: Int,
)
