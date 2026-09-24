package org.koitharu.kotatsu.stats.domain

/**
 * Privacy-safe annual summary used by Reader Journey and the share card.
 *
 * Deliberately contains aggregate values only: no title, cover, source, genre, tag or content-rating
 * identity can enter the share path through this model.
 */
data class YearInReview(
	val year: Int,
	val totalDuration: Long = 0L,
	val chapters: Int = 0,
	val pages: Int = 0,
	val activeDays: Int = 0,
	val titleCount: Int = 0,
	val longestStreak: Int = 0,
	val mangaChapters: Int = 0,
	val novelChapters: Int = 0,
) {
	val isEmpty: Boolean
		get() = totalDuration == 0L && chapters == 0 && pages == 0 && titleCount == 0
}
