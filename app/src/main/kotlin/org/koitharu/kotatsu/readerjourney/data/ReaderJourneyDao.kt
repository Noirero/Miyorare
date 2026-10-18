package org.koitharu.kotatsu.readerjourney.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules

@Dao
abstract class ReaderJourneyDao {
	@Query("SELECT * FROM reader_journey_profile WHERE id = 0") abstract suspend fun getProfile(): ReaderJourneyProfileEntity?
	@Query("SELECT * FROM reader_journey_profile WHERE id = 0") abstract fun observeProfile(): Flow<ReaderJourneyProfileEntity?>
	@Query("SELECT * FROM reader_journey_chapters ORDER BY manga_id, chapter_id") abstract suspend fun getAllChapterAwards(): List<ReaderJourneyChapterEntity>
	@Query("SELECT * FROM reader_journey_chapters WHERE manga_id = :mangaId ORDER BY chapter_id") abstract suspend fun findChapterAwards(mangaId: Long): List<ReaderJourneyChapterEntity>
	@Query("DELETE FROM reader_journey_chapters WHERE manga_id = :mangaId AND chapter_id = :chapterId") abstract suspend fun deleteChapterAward(mangaId: Long, chapterId: Long)
	@Query("SELECT * FROM reader_journey_achievements ORDER BY unlocked_at, achievement_id") abstract suspend fun getAllAchievements(): List<ReaderJourneyAchievementEntity>
	@Query("SELECT COUNT(DISTINCT manga_id) FROM reader_journey_chapters") abstract suspend fun countDistinctCompletedTitles(): Long
	@Query("SELECT * FROM reader_journey_achievements WHERE achievement_id = :achievementId LIMIT 1") protected abstract suspend fun findAchievement(achievementId: String): ReaderJourneyAchievementEntity?
	@Upsert protected abstract suspend fun upsertAchievement(entity: ReaderJourneyAchievementEntity)
	@Transaction open suspend fun mergeAchievement(remote: ReaderJourneyAchievementEntity): Boolean {
		val local = findAchievement(remote.achievementId)
		upsertAchievement(if (local == null) remote else local.copy(unlockedAt = minPositive(local.unlockedAt, remote.unlockedAt)))
		return local == null
	}
	@Query("SELECT * FROM reader_journey_chapters WHERE manga_id = :mangaId AND chapter_id = :chapterId") protected abstract suspend fun findChapterAward(mangaId: Long, chapterId: Long): ReaderJourneyChapterEntity?
	@Upsert protected abstract suspend fun upsertChapterAward(entity: ReaderJourneyChapterEntity)
	@Upsert protected abstract suspend fun upsertProfile(entity: ReaderJourneyProfileEntity)
	@Transaction open suspend fun mergeChapterAward(remote: ReaderJourneyChapterEntity) {
		val local = findChapterAward(remote.mangaId, remote.chapterId)
		if (local == null) { upsertChapterAward(remote); return }
		upsertChapterAward(local.copy(
			isNovel = local.isNovel || remote.isNovel,
			readingUnits = maxOf(local.readingUnits, remote.readingUnits),
			completionCount = maxOf(local.completionCount, remote.completionCount),
			awardedXp = maxOf(local.awardedXp, remote.awardedXp),
			firstCompletedAt = minPositive(local.firstCompletedAt, remote.firstCompletedAt),
			lastCompletedAt = maxOf(local.lastCompletedAt, remote.lastCompletedAt),
		))
	}
	@Query("SELECT IFNULL(SUM(awarded_xp), 0) FROM reader_journey_chapters") protected abstract suspend fun sumAwardedXp(): Long
	@Query("SELECT COUNT(*) FROM reader_journey_chapters") protected abstract suspend fun countCompletedChapters(): Long
	@Query("SELECT COUNT(*) FROM reader_journey_chapters WHERE is_novel = 0") protected abstract suspend fun countMangaChapters(): Long
	@Query("SELECT COUNT(*) FROM reader_journey_chapters WHERE is_novel = 1") protected abstract suspend fun countNovelChapters(): Long
	@Query("SELECT IFNULL(MAX(last_completed_at), 0) FROM reader_journey_chapters") protected abstract suspend fun latestCompletionAt(): Long
	@Transaction open suspend fun rebuildProfileFromLedger() {
		upsertProfile(ReaderJourneyProfileEntity(
			totalXp = sumAwardedXp(), completedChapters = countCompletedChapters(), mangaChapters = countMangaChapters(),
			novelChapters = countNovelChapters(), updatedAt = latestCompletionAt(),
		))
	}
	@Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertProfile(entity: ReaderJourneyProfileEntity): Long
	@Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertChapter(entity: ReaderJourneyChapterEntity): Long
	@Query("UPDATE reader_journey_chapters SET completion_count = completion_count + 1, awarded_xp = awarded_xp + :xp, last_completed_at = :completedAt WHERE manga_id = :mangaId AND chapter_id = :chapterId AND completion_count <= :maxRereads") protected abstract suspend fun awardReread(mangaId: Long, chapterId: Long, xp: Int, completedAt: Long, maxRereads: Int): Int
	@Query("UPDATE reader_journey_profile SET total_xp = total_xp + :xp, completed_chapters = completed_chapters + :firstCompletion, manga_chapters = manga_chapters + :mangaCompletion, novel_chapters = novel_chapters + :novelCompletion, updated_at = :updatedAt WHERE id = 0") protected abstract suspend fun addToProfile(xp: Int, firstCompletion: Int, mangaCompletion: Int, novelCompletion: Int, updatedAt: Long)
	@Transaction open suspend fun awardCompletion(mangaId: Long, chapterId: Long, isNovel: Boolean, readingUnits: Int, baseXp: Int, completedAt: Long): ReaderJourneyAward {
		insertProfile(ReaderJourneyProfileEntity(updatedAt = completedAt))
		val previousTotalXp = getProfile()?.totalXp ?: 0L
		val inserted = insertChapter(ReaderJourneyChapterEntity(mangaId, chapterId, isNovel, readingUnits.coerceAtLeast(0), 1, baseXp.toLong(), completedAt, completedAt))
		if (inserted != -1L) {
			addToProfile(baseXp, 1, if (isNovel) 0 else 1, if (isNovel) 1 else 0, completedAt)
			return ReaderJourneyAward(baseXp, true, previousTotalXp, previousTotalXp + baseXp)
		}
		val changed = awardReread(mangaId, chapterId, ReaderJourneyRules.REREAD_XP, completedAt, ReaderJourneyRules.MAX_REREAD_AWARDS)
		if (changed > 0) {
			addToProfile(ReaderJourneyRules.REREAD_XP, 0, 0, 0, completedAt)
			return ReaderJourneyAward(ReaderJourneyRules.REREAD_XP, false, previousTotalXp, previousTotalXp + ReaderJourneyRules.REREAD_XP)
		}
		return ReaderJourneyAward(0, false, previousTotalXp, previousTotalXp)
	}
	@Query("DELETE FROM reader_journey_chapters") protected abstract suspend fun clearChapters()
	@Query("DELETE FROM reader_journey_profile") protected abstract suspend fun clearProfile()
	@Query("DELETE FROM reader_journey_achievements") protected abstract suspend fun clearAchievements()
	@Transaction open suspend fun clearJourney() { clearChapters(); clearProfile(); clearAchievements() }
}

data class ReaderJourneyAward(val xp: Int, val isFirstCompletion: Boolean, val previousTotalXp: Long, val totalXp: Long)
private fun minPositive(a: Long, b: Long): Long = when { a <= 0L -> b; b <= 0L -> a; else -> minOf(a, b) }
