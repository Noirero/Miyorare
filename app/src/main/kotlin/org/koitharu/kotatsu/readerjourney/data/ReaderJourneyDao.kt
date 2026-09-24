package org.koitharu.kotatsu.readerjourney.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules

@Dao
abstract class ReaderJourneyDao {

	@Query("SELECT * FROM reader_journey_profile WHERE id = 0")
	abstract suspend fun getProfile(): ReaderJourneyProfileEntity?

	@Query("SELECT * FROM reader_journey_profile WHERE id = 0")
	abstract fun observeProfile(): Flow<ReaderJourneyProfileEntity?>

	@Query(
		"""
		SELECT * FROM reader_journey_chapters
		WHERE manga_id > :afterMangaId
			OR (manga_id = :afterMangaId AND chapter_id > :afterChapterId)
		ORDER BY manga_id, chapter_id
		LIMIT :limit
		""",
	)
	protected abstract suspend fun findJourneyBatch(
		afterMangaId: Long,
		afterChapterId: Long,
		limit: Int,
	): List<ReaderJourneyChapterEntity>

	fun dumpJourney(batchSize: Int = 256): Flow<ReaderJourneyChapterEntity> = flow {
		var mangaId = Long.MIN_VALUE
		var chapterId = Long.MIN_VALUE
		while (true) {
			val batch = findJourneyBatch(mangaId, chapterId, batchSize)
			if (batch.isEmpty()) break
			batch.forEach { emit(it) }
			val last = batch.last()
			mangaId = last.mangaId
			chapterId = last.chapterId
		}
	}

	@Query(
		"""
		SELECT * FROM reader_journey_chapters
		WHERE manga_id = :mangaId AND chapter_id = :chapterId
		""",
	)
	protected abstract suspend fun findChapterAward(
		mangaId: Long,
		chapterId: Long,
	): ReaderJourneyChapterEntity?

	@Upsert
	protected abstract suspend fun upsertChapterAward(entity: ReaderJourneyChapterEntity)

	/**
	 * Backup restore is monotonic. A stale backup can never reduce XP or completion count already
	 * recorded on this device.
	 */
	@Transaction
	open suspend fun mergeRestoredChapter(entity: ReaderJourneyChapterEntity) {
		val local = findChapterAward(entity.mangaId, entity.chapterId)
		val merged = if (local == null) {
			entity
		} else {
			local.copy(
				isNovel = local.isNovel || entity.isNovel,
				readingUnits = maxOf(local.readingUnits, entity.readingUnits),
				completionCount = maxOf(local.completionCount, entity.completionCount),
				awardedXp = maxOf(local.awardedXp, entity.awardedXp),
				firstCompletedAt = minOf(local.firstCompletedAt, entity.firstCompletedAt),
				lastCompletedAt = maxOf(local.lastCompletedAt, entity.lastCompletedAt),
			)
		}
		upsertChapterAward(merged)
	}

	@Query("SELECT IFNULL(SUM(awarded_xp), 0) FROM reader_journey_chapters")
	protected abstract suspend fun sumAwardedXp(): Long

	@Query("SELECT COUNT(*) FROM reader_journey_chapters")
	protected abstract suspend fun countCompletedChapters(): Long

	@Query("SELECT COUNT(*) FROM reader_journey_chapters WHERE is_novel = 0")
	protected abstract suspend fun countMangaChapters(): Long

	@Query("SELECT COUNT(*) FROM reader_journey_chapters WHERE is_novel = 1")
	protected abstract suspend fun countNovelChapters(): Long

	@Upsert
	protected abstract suspend fun upsertProfile(entity: ReaderJourneyProfileEntity)

	@Transaction
	open suspend fun rebuildProfile(updatedAt: Long = System.currentTimeMillis()) {
		upsertProfile(
			ReaderJourneyProfileEntity(
				totalXp = sumAwardedXp(),
				completedChapters = countCompletedChapters(),
				mangaChapters = countMangaChapters(),
				novelChapters = countNovelChapters(),
				updatedAt = updatedAt,
			),
		)
	}

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	protected abstract suspend fun insertProfile(entity: ReaderJourneyProfileEntity): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	protected abstract suspend fun insertChapter(entity: ReaderJourneyChapterEntity): Long

	@Query(
		"""
		UPDATE reader_journey_chapters
		SET completion_count = completion_count + 1,
			awarded_xp = awarded_xp + :xp,
			last_completed_at = :completedAt
		WHERE manga_id = :mangaId
			AND chapter_id = :chapterId
			AND completion_count <= :maxRereads
		""",
	)
	protected abstract suspend fun awardReread(
		mangaId: Long,
		chapterId: Long,
		xp: Int,
		completedAt: Long,
		maxRereads: Int,
	): Int

	@Query(
		"""
		UPDATE reader_journey_profile
		SET total_xp = total_xp + :xp,
			completed_chapters = completed_chapters + :firstCompletion,
			manga_chapters = manga_chapters + :mangaCompletion,
			novel_chapters = novel_chapters + :novelCompletion,
			updated_at = :updatedAt
		WHERE id = 0
		""",
	)
	protected abstract suspend fun addToProfile(
		xp: Int,
		firstCompletion: Int,
		mangaCompletion: Int,
		novelCompletion: Int,
		updatedAt: Long,
	)

	/**
	 * Idempotent first-completion award. Existing chapters only receive the deliberately tiny
	 * reread award, capped to prevent reopening one chapter from becoming the easiest XP farm.
	 */
	@Transaction
	open suspend fun awardCompletion(
		mangaId: Long,
		chapterId: Long,
		isNovel: Boolean,
		readingUnits: Int,
		baseXp: Int,
		completedAt: Long,
	): ReaderJourneyAward {
		insertProfile(ReaderJourneyProfileEntity(updatedAt = completedAt))
		val inserted = insertChapter(
			ReaderJourneyChapterEntity(
				mangaId = mangaId,
				chapterId = chapterId,
				isNovel = isNovel,
				readingUnits = readingUnits.coerceAtLeast(0),
				completionCount = 1,
				awardedXp = baseXp.toLong(),
				firstCompletedAt = completedAt,
				lastCompletedAt = completedAt,
			),
		)
		if (inserted != -1L) {
			addToProfile(
				xp = baseXp,
				firstCompletion = 1,
				mangaCompletion = if (isNovel) 0 else 1,
				novelCompletion = if (isNovel) 1 else 0,
				updatedAt = completedAt,
			)
			return ReaderJourneyAward(baseXp, isFirstCompletion = true)
		}
		val changed = awardReread(
			mangaId = mangaId,
			chapterId = chapterId,
			xp = ReaderJourneyRules.REREAD_XP,
			completedAt = completedAt,
			maxRereads = ReaderJourneyRules.MAX_REREAD_AWARDS,
		)
		if (changed > 0) {
			addToProfile(
				xp = ReaderJourneyRules.REREAD_XP,
				firstCompletion = 0,
				mangaCompletion = 0,
				novelCompletion = 0,
				updatedAt = completedAt,
			)
			return ReaderJourneyAward(ReaderJourneyRules.REREAD_XP, isFirstCompletion = false)
		}
		return ReaderJourneyAward(0, isFirstCompletion = false)
	}

	@Query("DELETE FROM reader_journey_chapters")
	protected abstract suspend fun clearChapters()

	@Query("DELETE FROM reader_journey_profile")
	protected abstract suspend fun clearProfile()

	@Transaction
	open suspend fun clearJourney() {
		clearChapters()
		clearProfile()
	}
}

data class ReaderJourneyAward(
	val xp: Int,
	val isFirstCompletion: Boolean,
)
