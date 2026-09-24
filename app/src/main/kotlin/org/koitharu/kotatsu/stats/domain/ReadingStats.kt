package org.koitharu.kotatsu.stats.domain

import java.util.concurrent.TimeUnit

/** Granularity of a single bar in the retained period activity chart data. */
enum class StatsBucketUnit {
	HOUR, DAY, WEEK, MONTH
}

data class StatsBucket(
	val startAt: Long,
	val duration: Long,
)

/**
 * One coherent snapshot backing the redesigned Stats dashboard.
 *
 * Period metrics, heatmap, insights and title rows are all calculated from the same filtered
 * sessions. Lifetime XP/streaks deliberately ignore the period selector so the reader profile does
 * not jump backwards when the user changes from All time to Week.
 */
data class ReadingStats(
	val period: StatsPeriod = StatsPeriod.ALL,
	val scope: StatsContentScope = StatsContentScope.OVERVIEW,
	val matureMode: StatsMatureMode = StatsMatureMode.PRIVATE,
	val records: List<StatsRecord> = emptyList(),
	val otherRecords: List<StatsRecord> = emptyList(),
	val revisited: List<StatsRecord> = emptyList(),
	val topGenres: List<StatsInsight> = emptyList(),
	val formatBreakdown: List<StatsInsight> = emptyList(),
	val heatmapDays: List<StatsHeatmapDay> = emptyList(),
	val buckets: List<StatsBucket> = emptyList(),
	val bucketUnit: StatsBucketUnit = StatsBucketUnit.DAY,
	val totalDuration: Long = 0L,
	val chapterDuration: Long = 0L,
	val chapters: Int = 0,
	val pages: Int = 0,
	val titleCount: Int = 0,
	val sessionCount: Int = 0,
	val activeDays: Int = 0,
	val currentStreak: Int = 0,
	val longestStreak: Int = 0,
	val lifetimeXp: Long = 0L,
	val isJourneyEnabled: Boolean = true,
	val privateDuration: Long = 0L,
	val privateTitles: Int = 0,
) {

	val isEmpty: Boolean
		get() = titleCount == 0 && totalDuration == 0L

	val minutesPerChapter: Double
		get() = if (chapters > 0 && chapterDuration > 0L) {
			chapterDuration.toDouble() / chapters / TimeUnit.MINUTES.toMillis(1)
		} else {
			0.0
		}

	val averagePerActiveDay: Long
		get() = if (activeDays > 0) totalDuration / activeDays else 0L

	val averageSessionDuration: Long
		get() = if (sessionCount > 0) totalDuration / sessionCount else 0L
}
