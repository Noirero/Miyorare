package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ChapterLogicalCount(
	val mangaId: Long,
	val chapterCount: Int,
)

data class ChapterUnreadAfterCurrent(
	val mangaId: Long,
	val chapterId: Long,
	val unreadCount: Int,
)

data class ChapterRevision(
	val mangaRevision: Long,
	val globalRevision: Long,
)

@Dao
abstract class ChaptersDao {

	private val mangaRevisions = ConcurrentHashMap<Long, AtomicLong>()
	private val globalRevision = AtomicLong()


	@Query("SELECT * FROM chapters WHERE manga_id = :mangaId ORDER BY `index` ASC")
	abstract suspend fun findAll(mangaId: Long): List<ChapterEntity>

	@Query("SELECT * FROM chapters WHERE manga_id IN (:mangaIds) ORDER BY manga_id, `index` ASC")
	abstract suspend fun findAll(mangaIds: Collection<Long>): List<ChapterEntity>

	@Query("SELECT url FROM chapters WHERE manga_id = :mangaId AND chapter_id = :chapterId LIMIT 1")
	abstract suspend fun findChapterUrl(mangaId: Long, chapterId: Long): String?

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

	/**
	 * O(1) process-local revision used to reject unrelated table-wide Room invalidations before
	 * materializing large chapter lists. All runtime chapter replacement paths flow through
	 * [replaceAll]; GC paths update either the affected manga revisions or the global revision.
	 */
	fun revision(mangaId: Long): ChapterRevision = ChapterRevision(
		mangaRevision = mangaRevisions[mangaId]?.get() ?: 0L,
		globalRevision = globalRevision.get(),
	)

	@Query("DELETE FROM chapters WHERE manga_id = :mangaId")
	protected abstract suspend fun deleteAll(mangaId: Long)

	/** Cached chapters are internal data; Private membership pins them just like History/Normal. */
	@Query(
		"""
		DELETE FROM chapters
		WHERE manga_id NOT IN (SELECT manga_id FROM history WHERE deleted_at = 0)
			AND manga_id NOT IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)
			AND manga_id NOT IN (SELECT manga_id FROM private_favourites WHERE deleted_at = 0)
		""",
	)
	protected abstract suspend fun gcAll()

	@Transaction
	open suspend fun gc() {
		globalRevision.incrementAndGet()
		resetInitializedForGcAll()
		gcAll()
	}

	/**
	 * Interactive removals already know which manga changed. Limit cache GC to those ids instead of
	 * scanning the entire chapters table; chunking keeps large Select All operations below SQLite's
	 * bind-parameter limit.
	 */
	@Transaction
	open suspend fun gc(mangaIds: Collection<Long>) {
		if (mangaIds.isEmpty()) return
		for (chunk in mangaIds.chunked(GC_CHUNK_SIZE)) {
			for (mangaId in chunk) bumpRevision(mangaId)
			resetInitializedForGcChunk(chunk)
			gcChunk(chunk)
		}
	}

	@Query(
		"""
		UPDATE manga
		SET chapters_initialized = 0
		WHERE manga_id IN (
			SELECT DISTINCT manga_id FROM chapters
			WHERE manga_id NOT IN (SELECT manga_id FROM history WHERE deleted_at = 0)
				AND manga_id NOT IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)
				AND manga_id NOT IN (SELECT manga_id FROM private_favourites WHERE deleted_at = 0)
		)
		""",
	)
	protected abstract suspend fun resetInitializedForGcAll()

	@Query(
		"""
		UPDATE manga
		SET chapters_initialized = 0
		WHERE manga_id IN (
			SELECT DISTINCT manga_id FROM chapters
			WHERE manga_id IN (:mangaIds)
				AND manga_id NOT IN (SELECT manga_id FROM history WHERE deleted_at = 0)
				AND manga_id NOT IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)
				AND manga_id NOT IN (SELECT manga_id FROM private_favourites WHERE deleted_at = 0)
		)
		""",
	)
	protected abstract suspend fun resetInitializedForGcChunk(mangaIds: Collection<Long>)

	@Query(
		"""
		DELETE FROM chapters
		WHERE manga_id IN (:mangaIds)
			AND manga_id NOT IN (SELECT manga_id FROM history WHERE deleted_at = 0)
			AND manga_id NOT IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)
			AND manga_id NOT IN (SELECT manga_id FROM private_favourites WHERE deleted_at = 0)
		""",
	)
	protected abstract suspend fun gcChunk(mangaIds: Collection<Long>)

	@Transaction
	open suspend fun replaceAll(mangaId: Long, entities: Collection<ChapterEntity>) {
		deleteAll(mangaId)
		insert(entities)
		markInitialized(mangaId)
		bumpRevision(mangaId)
	}

	@Query("UPDATE manga SET chapters_initialized = 1 WHERE manga_id = :mangaId")
	protected abstract suspend fun markInitialized(mangaId: Long)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	protected abstract suspend fun insert(entities: Collection<ChapterEntity>)

	private fun bumpRevision(mangaId: Long) {
		val fresh = AtomicLong()
		val counter = mangaRevisions[mangaId] ?: mangaRevisions.putIfAbsent(mangaId, fresh) ?: fresh
		counter.incrementAndGet()
	}

	private companion object {
		const val GC_CHUNK_SIZE = 500
	}
}
