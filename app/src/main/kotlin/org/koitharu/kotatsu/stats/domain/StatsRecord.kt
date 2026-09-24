package org.koitharu.kotatsu.stats.domain

import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Statistics for one visible title. [manga] is null for aggregate rows such as "Other titles" and
 * for the privacy-preserving adult bucket used by [StatsMatureMode.PRIVATE].
 */
data class StatsRecord(
	val manga: Manga?,
	val duration: Long,
	val pages: Int = 0,
	val chapters: Int = 0,
	val daysRead: Int = 0,
	val sessionCount: Int = 0,
	val isPrivate: Boolean = false,
	val isNovel: Boolean = false,
	/** First session in the selected period, so it matches the rest of the numbers on screen. */
	val firstReadAt: Long = 0L,
)
