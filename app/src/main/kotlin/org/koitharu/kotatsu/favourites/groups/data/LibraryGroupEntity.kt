package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_LIBRARY_GROUPS
import org.koitharu.kotatsu.favourites.data.FavouriteSpace

@Entity(
	tableName = TABLE_LIBRARY_GROUPS,
	indices = [Index(value = ["space"])],
)
data class LibraryGroupEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "group_id") val groupId: Long = 0L,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "cover_url") val coverUrl: String?,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "space") val space: Int = FavouriteSpace.NORMAL.dbValue,
	@ColumnInfo(name = "alternative_title") val alternativeTitle: String? = null,
	@ColumnInfo(name = "author") val author: String? = null,
	@ColumnInfo(name = "artist") val artist: String? = null,
	@ColumnInfo(name = "description") val description: String? = null,
	@ColumnInfo(name = "metadata_source") val metadataSource: Int? = null,
	@ColumnInfo(name = "metadata_target_id") val metadataTargetId: Long? = null,
)
