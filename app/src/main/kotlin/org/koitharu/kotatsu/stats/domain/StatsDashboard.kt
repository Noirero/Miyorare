package org.koitharu.kotatsu.stats.domain

/**
 * Primary content scope of the redesigned Stats dashboard.
 *
 * OVERVIEW intentionally combines manga and novel sessions. MANGA and NOVEL only affect
 * presentation/query filtering; they do not create a second statistics store.
 */
enum class StatsContentScope {
	OVERVIEW,
	MANGA,
	NOVEL,
}

/**
 * Privacy policy for adult/NSFW titles in statistics.
 *
 * PRIVATE keeps totals, streaks, XP and heatmap accurate while replacing identifying metadata.
 * EXCLUDE removes those sessions from the visible dashboard entirely.
 * INCLUDE treats them like every other title.
 */
enum class StatsMatureMode {
	PRIVATE,
	EXCLUDE,
	INCLUDE;

	companion object {
		fun fromPreference(value: String?): StatsMatureMode =
			entries.firstOrNull { it.name == value } ?: PRIVATE
	}
}

/** Small count model used by genre/format insight cards. */
data class StatsInsight(
	val label: String,
	val count: Int,
)

/** One day in the reading heatmap. */
data class StatsHeatmapDay(
	val epochDay: Long,
	val duration: Long,
	val sessions: Int,
)
