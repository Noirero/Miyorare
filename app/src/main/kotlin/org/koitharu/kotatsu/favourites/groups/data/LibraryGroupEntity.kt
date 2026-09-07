package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_LIBRARY_GROUPS

@Entity(tableName = TABLE_LIBRARY_GROUPS)
data class LibraryGroupEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "group_id") val groupId: Long = 0L,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "cover_url") val coverUrl: String?,
	@ColumnInfo(name = "created_at") val createdAt: Long,
)
