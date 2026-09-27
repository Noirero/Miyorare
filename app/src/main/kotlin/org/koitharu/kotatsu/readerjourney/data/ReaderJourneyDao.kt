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

	@Query("SELECT * FROM reader_journey_profile WHERE id = 0")
	abstract suspend fun getProfile(): ReaderJourneyProfileEntity?

	@Query("SELECT * FROM reader_journey_profile WHERE id = 0")
	abstract fun observeProfile(): Flow<ReaderJourneyProfileEntity?>


	@Query("SELECT * FROM reader_journey_chapters ORDER BY manga_id, chapter_id")
	abstract suspend fun getAllChapterAwards(): List<ReaderJourneyChapterEntity>

	@Query("SELECT * FROM reader_journey_achievements ORDER BY unlocked_at, achievement_id")
	abstract suspend fun getAllAchievements(): List<ReaderJourneyAchievementEntity>

	@Query("SELECT COUNT(DISTINCT manga_id) FROM reader_journey_chapters")
	abstract suspend fun countDistinctCompletedTitles(): Long

	@Query("SELECT COUNT(*) FROM reader_journey_chapters WHERE manga_id = :mangaId")
	abstract suspend fun countCompletedChaptersForTitle(mangaId: Long): Long

	@Query(
		"""
		SELECT * FROM reader_journey_chapters
		WHERE first_completed_at >= :startAt AND first_completed_at < :endAt
		ORDER BY first_completed_at ASC
		""",
	)
	abstract suspend fun getFirstCompletionsBetween(
		startAt: Long,
		endAt: Long,
	): List<ReaderJourneyChapterEntity>

	@Query(
		"""
		SELECT COUNT(*) FROM (
			SELECT manga_id FROM reader_journey_chapters
			GROUP BY manga_id
			HAVING MIN(first_completed_at) >= :startAt AND MIN(first_completed_at) < :endAt
		)
		""",
	)
	abstract suspend fun countTitlesFirstCompletedBetween(startAt: Long, endAt: Long): Long

	@Query("SELECT * FROM reader_journey_xp_events ORDER BY occurred_at, event_key")
	abstract suspend fun getAllXpEvents(): List<ReaderJourneyXpEventEntity>

	@Query("SELECT * FROM reader_journey_xp_events WHERE event_key = :eventKey LIMIT 1")
	abstract suspend fun getXpEvent(eventKey: String): ReaderJourneyXpEventEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertXpEvent(entity: ReaderJourneyXpEventEntity): Long

	@Upsert
	protected abstract suspend fun upsertXpEvent(entity: ReaderJourneyXpEventEntity)

	@Transaction
	open suspend fun mergeXpEvent(remote: ReaderJourneyXpEventEntity) {
		val local = getXpEvent(remote.eventKey)
		if (local == null) {
			insertXpEvent(remote)
			return
		}
		val winner = when {
			remote.xp > local.xp -> remote
			remote.xp < local.xp -> local
			remote.occurredAt < local.occurredAt -> remote
			remote.occurredAt > local.occurredAt -> local
			(remote.context ?: "") < (local.context ?: "") -> remote
			else -> local
		}
		upsertXpEvent(
			local.copy(
				source = winner.source.ifBlank { local.source.ifBlank { remote.source } },
				xp = maxOf(local.xp, remote.xp),
				occurredAt = minPositive(local.occurredAt, remote.occurredAt),
				mangaId = winner.mangaId ?: local.mangaId ?: remote.mangaId,
				chapterId = winner.chapterId ?: local.chapterId ?: remote.chapterId,
				context = winner.context ?: local.context ?: remote.context,
				profileDelta = local.profileDelta || remote.profileDelta,
			),
		)
	}

	@Query(
		"""
		SELECT * FROM reader_journey_xp_events
		WHERE xp > 0
		ORDER BY occurred_at DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun getRecentXpEvents(limit: Int): List<ReaderJourneyXpEventEntity>

	@Query(
		"""
		SELECT * FROM reader_journey_xp_events
		WHERE occurred_at = :occurredAt AND xp > 0
		ORDER BY event_key
		""",
	)
	abstract suspend fun getXpEventsAt(occurredAt: Long): List<ReaderJourneyXpEventEntity>

	@Query(
		"""
		SELECT IFNULL(SUM(xp), 0) FROM reader_journey_xp_events
		WHERE occurred_at >= :startAt
			AND source IN ('READING_COMPLETION', 'REREAD')
		""",
	)
	abstract suspend fun sumReadingXpSince(startAt: Long): Long

	@Query(
		"""
		SELECT * FROM reader_journey_xp_events
		WHERE source = :source
		ORDER BY occurred_at DESC
		LIMIT 1
		""",
	)
	abstract suspend fun latestXpEventBySource(source: String): ReaderJourneyXpEventEntity?

	@Query(
		"""
		SELECT COUNT(*) FROM reader_journey_xp_events
		WHERE source = :source AND occurred_at >= :startAt
		""",
	)
	abstract suspend fun countXpEventsBySourceSince(source: String, startAt: Long): Int

	@Query("SELECT EXISTS(SELECT 1 FROM reader_journey_xp_events WHERE event_key = :eventKey)")
	abstract suspend fun hasXpEvent(eventKey: String): Boolean

	@Query("SELECT COUNT(*) FROM reader_journey_xp_events WHERE event_key LIKE :prefix || '%'")
	abstract suspend fun countXpEventsByKeyPrefix(prefix: String): Int

	@Query("SELECT * FROM reader_journey_weekly_state WHERE week_key = :weekKey LIMIT 1")
	abstract suspend fun getWeeklyState(weekKey: String): ReaderJourneyWeeklyStateEntity?

	@Query("SELECT * FROM reader_journey_weekly_state ORDER BY week_key")
	abstract suspend fun getAllWeeklyStates(): List<ReaderJourneyWeeklyStateEntity>

	@Upsert
	abstract suspend fun upsertWeeklyState(entity: ReaderJourneyWeeklyStateEntity)

	@Transaction
	open suspend fun mergeWeeklyState(remote: ReaderJourneyWeeklyStateEntity) {
		val local = getWeeklyState(remote.weekKey)
		if (local == null) {
			upsertWeeklyState(remote)
			return
		}
		val chosenTasks = if (remote.updatedAt > local.updatedAt) remote.taskIds else local.taskIds
		upsertWeeklyState(
			local.copy(
				taskIds = chosenTasks,
				rerollsUsed = maxOf(local.rerollsUsed, remote.rerollsUsed),
				updatedAt = maxOf(local.updatedAt, remote.updatedAt),
			),
		)
	}

	@Query("SELECT * FROM reader_journey_achievements WHERE achievement_id = :achievementId LIMIT 1")
	protected abstract suspend fun findAchievement(achievementId: String): ReaderJourneyAchievementEntity?

	@Upsert
	protected abstract suspend fun upsertAchievement(entity: ReaderJourneyAchievementEntity)

	/** Achievement sync/restore is monotonic: once unlocked, keep the earliest known unlock time. */
	@Transaction
	open suspend fun mergeAchievement(remote: ReaderJourneyAchievementEntity): Boolean {
		val local = findAchievement(remote.achievementId)
		upsertAchievement(
			if (local == null) remote else local.copy(
				unlockedAt = minPositive(local.unlockedAt, remote.unlockedAt),
			),
		)
		return local == null
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

	@Upsert
	protected abstract suspend fun upsertProfile(entity: ReaderJourneyProfileEntity)

	/**
	 * Cross-device/local-backup restore is monotonic. The same logical chapter completion converges
	 * by taking the strongest known state instead of adding two devices' XP together.
	 */
	@Transaction
	open suspend fun mergeChapterAward(remote: ReaderJourneyChapterEntity) {
		val local = findChapterAward(remote.mangaId, remote.chapterId)
		if (local == null) {
			upsertChapterAward(remote)
			return
		}
		upsertChapterAward(
			local.copy(
				isNovel = local.isNovel || remote.isNovel,
				readingUnits = maxOf(local.readingUnits, remote.readingUnits),
				completionCount = maxOf(local.completionCount, remote.completionCount),
				awardedXp = maxOf(local.awardedXp, remote.awardedXp),
				firstCompletedAt = minPositive(local.firstCompletedAt, remote.firstCompletedAt),
				lastCompletedAt = maxOf(local.lastCompletedAt, remote.lastCompletedAt),
			),
		)
	}

	@Query("SELECT IFNULL(SUM(awarded_xp), 0) FROM reader_journey_chapters")
	protected abstract suspend fun sumAwardedXp(): Long

	@Query("SELECT IFNULL(SUM(xp), 0) FROM reader_journey_xp_events WHERE profile_delta = 1")
	protected abstract suspend fun sumBonusXp(): Long

	@Query("SELECT COUNT(*) FROM reader_journey_chapters")
	protected abstract suspend fun countCompletedChapters(): Long

	@Query("SELECT COUNT(*) FROM reader_journey_chapters WHERE is_novel = 0")
	protected abstract suspend fun countMangaChapters(): Long

	@Query("SELECT COUNT(*) FROM reader_journey_chapters WHERE is_novel = 1")
	protected abstract suspend fun countNovelChapters(): Long

	@Query("SELECT IFNULL(MAX(last_completed_at), 0) FROM reader_journey_chapters")
	protected abstract suspend fun latestCompletionAt(): Long

	/**
	 * The profile is a cache of the ledger. Rebuilding from chapter awards makes restore/sync
	 * idempotent and guarantees Lifetime XP never depends on merge order.
	 */
	@Transaction
	open suspend fun rebuildProfileFromLedger() {
		val adjustment = getProfile()?.xpFloorAdjustment ?: 0L
		val ledgerXp = sumAwardedXp() + sumBonusXp()
		upsertProfile(
			ReaderJourneyProfileEntity(
				totalXp = ledgerXp + adjustment,
				xpFloorAdjustment = adjustment,
				completedChapters = countCompletedChapters(),
				mangaChapters = countMangaChapters(),
				novelChapters = countNovelChapters(),
				updatedAt = latestCompletionAt(),
			),
		)
	}

	/**
	 * Reconciles a privacy-safe aggregate Lifetime XP target with the identifiable local ledger.
	 *
	 * [xp_floor] stores only the anonymous adjustment, not the target itself. That lets new local
	 * ledger XP continue to increase total XP after a private/cloud restore, while the adjustment
	 * shrinks as identifiable ledger rows later catch up instead of double-counting them.
	 */
	@Transaction
	open suspend fun reconcileXpFloor(targetLifetimeXp: Long) {
		insertProfile(ReaderJourneyProfileEntity())
		val currentTotal = getProfile()?.totalXp ?: 0L
		val ledgerXp = sumAwardedXp() + sumBonusXp()
		val target = maxOf(currentTotal, targetLifetimeXp, ledgerXp)
		setXpFloorAdjustment(
			adjustment = (target - ledgerXp).coerceAtLeast(0L),
			totalXp = target,
		)
	}

	@Query(
		"""
		UPDATE reader_journey_profile
		SET xp_floor = :adjustment,
			total_xp = :totalXp
		WHERE id = 0
		""",
	)
	protected abstract suspend fun setXpFloorAdjustment(adjustment: Long, totalXp: Long)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	protected abstract suspend fun insertProfile(entity: ReaderJourneyProfileEntity): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	protected abstract suspend fun insertChapter(entity: ReaderJourneyChapterEntity): Long

	@Transaction
	open suspend fun awardBonusEvent(entity: ReaderJourneyXpEventEntity): ReaderJourneyBonusAward {
		insertProfile(ReaderJourneyProfileEntity())
		val previous = getProfile()?.totalXp ?: 0L
		val inserted = insertXpEvent(entity)
		if (inserted == -1L || !entity.profileDelta || entity.xp <= 0) {
			return ReaderJourneyBonusAward(0, previous, previous)
		}
		addBonusXpToProfile(entity.xp)
		return ReaderJourneyBonusAward(
			xp = entity.xp,
			previousTotalXp = previous,
			totalXp = previous + entity.xp,
		)
	}

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
		SET total_xp = total_xp + :xp
		WHERE id = 0
		""",
	)
	protected abstract suspend fun addBonusXpToProfile(xp: Int)

	@Query(
		"""
		UPDATE reader_journey_profile
		SET total_xp = total_xp + :xp,
			completed_chapters = completed_chapters + :firstCompletion,
			manga_chapters = manga_chapters + :mangaCompletion,
			novel_chapters = novel_chapters + :novelCompletion,
			updated_at = MAX(updated_at, :updatedAt)
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
		val previousProfile = getProfile()
		val previousTotalXp = previousProfile?.totalXp ?: 0L
		val previousUpdatedAt = previousProfile?.updatedAt ?: 0L
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
			return ReaderJourneyAward(
				xp = baseXp,
				isFirstCompletion = true,
				completionCount = 1,
				previousUpdatedAt = previousUpdatedAt,
				previousTotalXp = previousTotalXp,
				totalXp = previousTotalXp + baseXp,
			)
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
			val completionCount = findChapterAward(mangaId, chapterId)?.completionCount ?: 2
			return ReaderJourneyAward(
				xp = ReaderJourneyRules.REREAD_XP,
				isFirstCompletion = false,
				completionCount = completionCount,
				previousUpdatedAt = previousUpdatedAt,
				previousTotalXp = previousTotalXp,
				totalXp = previousTotalXp + ReaderJourneyRules.REREAD_XP,
			)
		}
		return ReaderJourneyAward(
			xp = 0,
			isFirstCompletion = false,
			completionCount = findChapterAward(mangaId, chapterId)?.completionCount ?: 0,
			previousUpdatedAt = previousUpdatedAt,
			previousTotalXp = previousTotalXp,
			totalXp = previousTotalXp,
		)
	}

	@Query("DELETE FROM reader_journey_chapters")
	protected abstract suspend fun clearChapters()

	@Query("DELETE FROM reader_journey_profile")
	protected abstract suspend fun clearProfile()

	@Query("DELETE FROM reader_journey_achievements")
	protected abstract suspend fun clearAchievements()

	@Query("DELETE FROM reader_journey_xp_events")
	protected abstract suspend fun clearXpEvents()

	@Query("DELETE FROM reader_journey_weekly_state")
	protected abstract suspend fun clearWeeklyState()

	@Transaction
	open suspend fun clearJourney() {
		clearChapters()
		clearProfile()
		clearAchievements()
		clearXpEvents()
		clearWeeklyState()
	}
}

data class ReaderJourneyAward(
	val xp: Int,
	val isFirstCompletion: Boolean,
	val completionCount: Int,
	val previousUpdatedAt: Long,
	val previousTotalXp: Long,
	val totalXp: Long,
)

data class ReaderJourneyBonusAward(
	val xp: Int,
	val previousTotalXp: Long,
	val totalXp: Long,
)

private fun minPositive(a: Long, b: Long): Long = when {
	a <= 0L -> b
	b <= 0L -> a
	else -> minOf(a, b)
}
