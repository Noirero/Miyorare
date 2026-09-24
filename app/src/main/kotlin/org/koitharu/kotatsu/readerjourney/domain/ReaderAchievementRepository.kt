package org.koitharu.kotatsu.readerjourney.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyAchievementEntity
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
		val persisted = dao.getAllAchievements().mapNotNull { entity ->
			ReaderAchievementId.entries.find { it.name == entity.achievementId }?.let { it to entity.unlockedAt }
		}.toMap()
		return ReaderAchievementRefreshResult(
			progress = ReaderAchievementRules.buildProgress(metrics, persisted),
			newlyUnlocked = newlyUnlocked,
		)
	}
}

data class ReaderAchievementRefreshResult(
	val progress: List<ReaderAchievementProgress>,
	val newlyUnlocked: List<ReaderAchievementId>,
)
