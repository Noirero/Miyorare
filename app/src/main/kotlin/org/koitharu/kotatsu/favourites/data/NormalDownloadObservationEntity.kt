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

/** Metadata-only membership row: no Manga relations, tags or archive reads. */
data class NormalLibraryMember(
	val mangaId: Long,
	val categoryId: Long,
	val source: String,
	val title: String,
	val url: String,
	val detailsRevision: Long,
	val chaptersInitialized: Boolean,
)

data class NormalLibraryCategory(val mangaId: Long, val categoryId: Long)

data class NormalLibraryLocator(val mangaId: Long, val chapterId: Long, val source: String, val url: String)

private const val NORMAL_LIBRARY_MEMBERS = "SELECT m.manga_id AS mangaId, f.category_id AS categoryId, " +
	"m.source, m.title, m.url, m.details_updated_at AS detailsRevision, m.chapters_initialized AS chaptersInitialized " +
	"FROM favourites f INNER JOIN manga m ON m.manga_id = f.manga_id WHERE f.deleted_at = 0"

@Dao
interface NormalDownloadObservationDao {
	@Query(NORMAL_LIBRARY_MEMBERS)
	suspend fun findLibraryMembers(): List<NormalLibraryMember>

	@Query(NORMAL_LIBRARY_MEMBERS + " AND f.manga_id IN (:ids)")
	suspend fun findLibraryMembers(ids: Collection<Long>): List<NormalLibraryMember>

	@Query("SELECT manga_id AS mangaId, category_id AS categoryId FROM private_favourites WHERE deleted_at = 0 AND manga_id IN (:ids)")
	suspend fun findPrivateCategories(ids: Collection<Long>): List<NormalLibraryCategory>

	@Query("SELECT manga_id AS mangaId, chapter_id AS chapterId, source, url FROM chapters WHERE manga_id IN (:ids) ORDER BY manga_id, `index`")
	suspend fun findChapterLocators(ids: Collection<Long>): List<NormalLibraryLocator>

	@Query("SELECT * FROM normal_download_observation WHERE session = :session AND rootEpoch = :epoch AND mangaId IN (:ids)")
	suspend fun findCurrent(session: String, epoch: Long, ids: Collection<Long>): List<NormalDownloadObservationEntity>

	@Upsert
	suspend fun upsert(row: NormalDownloadObservationEntity)
}
