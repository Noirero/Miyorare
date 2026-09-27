package org.koitharu.kotatsu.readerjourney.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyAchievementEntity
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyXpEventEntity
import javax.inject.Inject

/**
 * Persists milestone unlocks independently from mutable Stats views.
 *
 * Chapter/title/novel progress comes only from the verified Reader Journey ledger. Streak progress
 * is supplied by Stats from activity occurring after Reader Journey began. Once unlocked, an
 * achievement never relocks if Stats are cleared or privacy presentation changes.
 */
class ReaderAchievementRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	suspend fun refresh(
		longestStreak: Int? = null,
		unlockedAt: Long = System.currentTimeMillis(),
		allowUnlock: Boolean = true,
	): List<ReaderAchievementProgress> = refreshWithResult(
		longestStreak = longestStreak,
		unlockedAt = unlockedAt,
		allowUnlock = allowUnlock,
	).progress

	suspend fun refreshWithResult(
		longestStreak: Int? = null,
		unlockedAt: Long = System.currentTimeMillis(),
		allowUnlock: Boolean = true,
	): ReaderAchievementRefreshResult {
		val dao = db.getReaderJourneyDao()
		val profile = dao.getProfile()
		val current = dao.getAllAchievements()
		val existing = current.mapNotNullTo(LinkedHashSet()) { entity ->
			ReaderAchievementId.entries.find { it.name == entity.achievementId }
		}
		val metrics = ReaderAchievementMetrics(
			completedChapters = profile?.completedChapters ?: 0L,
			novelChapters = profile?.novelChapters ?: 0L,
			uniqueTitles = dao.countDistinctCompletedTitles(),
			longestStreak = longestStreak?.toLong() ?: 0L,
		)
		val newlyUnlocked = ArrayList<ReaderAchievementId>()
		for (id in if (allowUnlock) ReaderAchievementRules.newlySatisfied(metrics, existing) else emptyList()) {
			// A missing streak value means the caller has no authoritative streak snapshot. Never
			// fabricate a streak unlock from zero/unknown data.
			if (id.metric == ReaderAchievementMetric.LONGEST_STREAK && longestStreak == null) continue
			val inserted = dao.mergeAchievement(
				ReaderJourneyAchievementEntity(
					achievementId = id.name,
					unlockedAt = unlockedAt,
				),
			)
			if (inserted) newlyUnlocked += id
		}
		val allPersisted = dao.getAllAchievements()
		val xpAwards = ArrayList<ReaderJourneyXpBreakdown>()
		// Backfill-safe: achievements unlocked before this XP system also receive their one-time reward.
		// Event keys make this idempotent across refresh, restore, and sync.
		for (entity in allPersisted) {
			val id = ReaderAchievementId.entries.find { it.name == entity.achievementId } ?: continue
			if (id.xpReward <= 0) continue
			val award = dao.awardBonusEvent(
				ReaderJourneyXpEventEntity(
					eventKey = "achievement:" + id.name,
					source = ReaderJourneyXpSource.ACHIEVEMENT.name,
					xp = id.xpReward,
					// XP history records when XP is actually credited. The original milestone unlock
					// timestamp remains in reader_journey_achievements and is not rewritten.
					occurredAt = unlockedAt,
					context = id.name,
					profileDelta = true,
				),
			)
			if (award.xp > 0) {
				xpAwards += ReaderJourneyXpBreakdown(
					source = ReaderJourneyXpSource.ACHIEVEMENT.name,
					xp = award.xp,
					context = id.name,
				)
			}
		}
		val persisted = allPersisted.mapNotNull { entity ->
			ReaderAchievementId.entries.find { it.name == entity.achievementId }?.let { it to entity.unlockedAt }
		}.toMap()
		return ReaderAchievementRefreshResult(
			progress = ReaderAchievementRules.buildProgress(metrics, persisted),
			newlyUnlocked = newlyUnlocked,
			xpAwards = xpAwards,
		)
	}
}

data class ReaderAchievementRefreshResult(
	val progress: List<ReaderAchievementProgress>,
	val newlyUnlocked: List<ReaderAchievementId>,
	val xpAwards: List<ReaderJourneyXpBreakdown> = emptyList(),
)
