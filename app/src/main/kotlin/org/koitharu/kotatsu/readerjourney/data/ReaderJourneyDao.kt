package org.koitharu.kotatsu.readerjourney.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules

@Dao
abstract class ReaderJourneyDao {

	@Query("SELECT * FROM reader_journey_profile WHERE id = 0")
	abstract suspend fun getProfile(): ReaderJourneyProfileEntity?

	@Query("SELECT * FROM reader_journey_profile WHERE id = 0")
	abstract fun observeProfile(): Flow<ReaderJourneyProfileEntity?>

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
