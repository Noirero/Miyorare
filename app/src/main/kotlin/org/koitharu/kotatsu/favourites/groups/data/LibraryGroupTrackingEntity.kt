package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
	tableName = "library_group_tracking",
	primaryKeys = ["group_id", "service"],
	foreignKeys = [
		ForeignKey(
			entity = LibraryGroupEntity::class,
			parentColumns = ["group_id"],
			childColumns = ["group_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["group_id"]),
		Index(value = ["service"]),
	],
)
data class LibraryGroupTrackingEntity(
	@ColumnInfo(name = "group_id") val groupId: Long,
	@ColumnInfo(name = "service") val service: Int,
	@ColumnInfo(name = "rate_id") val rateId: Long,
	@ColumnInfo(name = "target_id") val targetId: Long,
	@ColumnInfo(name = "target_title") val targetTitle: String,
	@ColumnInfo(name = "target_url") val targetUrl: String?,
	@ColumnInfo(name = "status") val status: String?,
	@ColumnInfo(name = "progress") val progress: Int,
	@ColumnInfo(name = "rating") val rating: Float,
	@ColumnInfo(name = "comment") val comment: String?,
	@ColumnInfo(name = "last_sync_at") val lastSyncAt: Long,
)
