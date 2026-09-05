package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.koitharu.kotatsu.core.db.entity.ChapterEntity

data class ChapterLogicalCount(
	val mangaId: Long,
	val chapterCount: Int,
)

data class ChapterUnreadAfterCurrent(
	val mangaId: Long,
	val chapterId: Long,
	val unreadCount: Int,
)

@Dao
abstract class ChaptersDao {

	@Query("SELECT * FROM chapters WHERE manga_id = :mangaId ORDER BY `index` ASC")
	abstract suspend fun findAll(mangaId: Long): List<ChapterEntity>

	@Query("SELECT * FROM chapters WHERE manga_id IN (:mangaIds) ORDER BY manga_id, `index` ASC")
	abstract suspend fun findAll(mangaIds: Collection<Long>): List<ChapterEntity>

	@Query(
		"""
		SELECT manga_id AS mangaId, MAX(branch_count) AS chapterCount
		FROM (
			SELECT manga_id, branch, COUNT(*) AS branch_count
			FROM chapters
			WHERE manga_id IN (:mangaIds)
			GROUP BY manga_id, branch
		) AS branch_counts
		GROUP BY manga_id
		""",
	)
	abstract suspend fun findLogicalCounts(mangaIds: Collection<Long>): List<ChapterLogicalCount>

	@Query(
		"""
		SELECT cur.manga_id AS mangaId,
			cur.chapter_id AS chapterId,
			(
				SELECT COUNT(*)
				FROM chapters AS candidate
				WHERE candidate.manga_id = cur.manga_id
					AND candidate.branch IS cur.branch
					AND candidate.`index` > cur.`index`
			) AS unreadCount
		FROM chapters AS cur
		WHERE cur.manga_id IN (:mangaIds)
			AND cur.chapter_id IN (:chapterIds)
		""",
	)
	abstract suspend fun findUnreadAfterCurrent(
		mangaIds: Collection<Long>,
		chapterIds: Collection<Long>,
	): List<ChapterUnreadAfterCurrent>

	@Query("SELECT COUNT(*) FROM chapters WHERE manga_id = :mangaId")
	abstract suspend fun count(mangaId: Long): Int

	@Query("DELETE FROM chapters WHERE manga_id = :mangaId")
	abstract suspend fun deleteAll(mangaId: Long)

	@Query("DELETE FROM chapters WHERE manga_id NOT IN (SELECT manga_id FROM history WHERE deleted_at = 0) AND manga_id NOT IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)")
	abstract suspend fun gc()

	@Transaction
	open suspend fun replaceAll(mangaId: Long, entities: Collection<ChapterEntity>) {
		deleteAll(mangaId)
		insert(entities)
	}

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	protected abstract suspend fun insert(entities: Collection<ChapterEntity>)
}
