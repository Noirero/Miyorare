package org.koitharu.kotatsu.favourites.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import org.koitharu.kotatsu.core.db.entity.MangaEntity

/** Normal-only observation cache, not ownership, completion receipt or delete authority. */
@Entity(
	tableName = "normal_download_observation",
	foreignKeys = [ForeignKey(
		entity = MangaEntity::class, parentColumns = ["manga_id"],
		childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["session", "rootEpoch", "status"])],
)
data class NormalDownloadObservationEntity(
	@PrimaryKey val mangaId: Long,
	val session: String,
	val rootEpoch: Long,
	val revision: Long,
	val rootPath: String?,
	val status: String,
	val path: String?,
)

@Dao
interface NormalDownloadObservationDao {
	@Query("SELECT * FROM normal_download_observation WHERE session = :session AND rootEpoch = :epoch AND mangaId IN (:ids)")
	suspend fun findCurrent(session: String, epoch: Long, ids: Collection<Long>): List<NormalDownloadObservationEntity>

	@Upsert
	suspend fun upsert(row: NormalDownloadObservationEntity)
}
