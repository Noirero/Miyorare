package org.koitharu.kotatsu.readerjourney.domain

/**
 * Stable, privacy-safe achievement identities. None of these encode manga titles, sources, genres,
 * tags or mature-content identity, so they are safe to persist, back up and sync.
 */
enum class ReaderAchievementId(
	val rarity: ReaderAchievementRarity,
	val metric: ReaderAchievementMetric,
	val target: Long,
) {
	FIRST_CHAPTER(ReaderAchievementRarity.COMMON, ReaderAchievementMetric.CHAPTERS, 1),
	CHAPTERS_100(ReaderAchievementRarity.UNCOMMON, ReaderAchievementMetric.CHAPTERS, 100),
	CHAPTERS_1000(ReaderAchievementRarity.LEGENDARY, ReaderAchievementMetric.CHAPTERS, 1_000),
	FIRST_NOVEL(ReaderAchievementRarity.COMMON, ReaderAchievementMetric.NOVEL_CHAPTERS, 1),
	TITLES_10(ReaderAchievementRarity.UNCOMMON, ReaderAchievementMetric.TITLES, 10),
	TITLES_50(ReaderAchievementRarity.RARE, ReaderAchievementMetric.TITLES, 50),
	STREAK_7(ReaderAchievementRarity.UNCOMMON, ReaderAchievementMetric.LONGEST_STREAK, 7),
	STREAK_30(ReaderAchievementRarity.RARE, ReaderAchievementMetric.LONGEST_STREAK, 30),
	STREAK_100(ReaderAchievementRarity.LEGENDARY, ReaderAchievementMetric.LONGEST_STREAK, 100),
}

enum class ReaderAchievementRarity {
	COMMON,
	UNCOMMON,
	RARE,
	EPIC,
	LEGENDARY,
}

enum class ReaderAchievementMetric {
	CHAPTERS,
	NOVEL_CHAPTERS,
	TITLES,
	LONGEST_STREAK,
}

data class ReaderAchievementMetrics(
	val completedChapters: Long = 0L,
	val novelChapters: Long = 0L,
	val uniqueTitles: Long = 0L,
	val longestStreak: Long = 0L,
) {
	fun value(metric: ReaderAchievementMetric): Long = when (metric) {
		ReaderAchievementMetric.CHAPTERS -> completedChapters
		ReaderAchievementMetric.NOVEL_CHAPTERS -> novelChapters
		ReaderAchievementMetric.TITLES -> uniqueTitles
		ReaderAchievementMetric.LONGEST_STREAK -> longestStreak
	}
}

data class ReaderAchievementProgress(
	val id: ReaderAchievementId,
	val progress: Long,
	val target: Long,
	val unlockedAt: Long?,
) {
	val isUnlocked: Boolean
		get() = unlockedAt != null

	val fraction: Float
		get() = if (target <= 0L) 1f else (progress.toFloat() / target).coerceIn(0f, 1f)
}

object ReaderAchievementRules {

	fun buildProgress(
		metrics: ReaderAchievementMetrics,
		unlockedAtById: Map<ReaderAchievementId, Long>,
	): List<ReaderAchievementProgress> = ReaderAchievementId.entries.map { id ->
		ReaderAchievementProgress(
			id = id,
			progress = metrics.value(id.metric).coerceAtMost(id.target),
			target = id.target,
			unlockedAt = unlockedAtById[id],
		)
	}

	fun newlySatisfied(
		metrics: ReaderAchievementMetrics,
		alreadyUnlocked: Set<ReaderAchievementId>,
	): List<ReaderAchievementId> = ReaderAchievementId.entries.filter { id ->
		id !in alreadyUnlocked && metrics.value(id.metric) >= id.target
	}
}
