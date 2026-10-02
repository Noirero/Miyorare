package org.koitharu.kotatsu.sync.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.koitharu.kotatsu.core.db.entity.MangaEntity

@Entity(
	tableName = "library_sync_mappings",
	primaryKeys = ["service", "external_id"],
	indices =
		[Index(value = ["service", "local_manga_id"], unique = true), Index("local_manga_id")],
	foreignKeys =
		[
			ForeignKey(
				entity = MangaEntity::class,
				parentColumns = ["manga_id"],
				childColumns = ["local_manga_id"],
				onDelete = ForeignKey.CASCADE,
			)
		],
)
data class LibrarySyncMappingEntity(
	val service: String,
	@ColumnInfo(name = "external_id") val externalId: String,
	@ColumnInfo(name = "local_manga_id") val localMangaId: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Remote snapshots also form a durable inbox. A Worker never creates favourites. */
@Entity(tableName = "library_sync_entries", primaryKeys = ["service", "external_id"])
data class LibrarySyncEntryEntity(
	val service: String,
	@ColumnInfo(name = "external_id") val externalId: String,
	val title: String,
	val progress: Int,
	val status: String?,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
	@ColumnInfo(name = "remote_entry_id") val remoteEntryId: String?,
) {
	fun toEntry(localId: Long? = null) =
		SyncEntry(
			LibrarySyncServiceId.valueOf(service),
			externalId,
			localId,
			title,
			progress,
			status,
			java.time.Instant.ofEpochMilli(updatedAt),
			remoteEntryId,
		)
}

fun SyncEntry.toEntity() =
	LibrarySyncEntryEntity(
		service.name,
		externalId,
		title,
		progress,
		status,
		updatedAt.toEpochMilli(),
		remoteEntryId,
	)
