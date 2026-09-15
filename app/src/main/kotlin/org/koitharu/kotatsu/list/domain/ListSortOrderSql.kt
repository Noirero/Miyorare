package org.koitharu.kotatsu.list.domain

/**
 * Builds the `ORDER BY` clause for a sorted manga list. Every query that uses it must join `manga`.
 *
 * The three expressions are passed in because they live in different places per table: the history
 * table has them as plain columns, while favourites has to reach into `history` for them.
 */
fun ListSortOrder.toOrderBy(
	dateAdded: String,
	lastRead: String,
	progress: String,
): String {
	val direction = if (isAscending) "ASC" else "DESC"
	val titleOrder = "LOWER(manga.title) COLLATE LOCALIZED"
	val totalChapters = "(SELECT COUNT(*) FROM chapters WHERE chapters.manga_id = manga.manga_id)"
	val unreadCount = "($totalChapters * (1.0 - $progress))"
	val expression = when (type) {
		ListSortOrder.Type.ALPHABETICAL -> titleOrder

		ListSortOrder.Type.TOTAL_CHAPTERS -> totalChapters

		ListSortOrder.Type.LAST_READ -> lastRead

		// Like Mihon, entries with nothing left to read stay at the bottom whichever way you sort.
		// An empty chapter cache means "not fetched yet", not "fully read", so it is left out of that.
		ListSortOrder.Type.UNREAD_COUNT -> return "CASE WHEN $totalChapters > 0 AND $unreadCount < 1 " +
			"THEN 1 ELSE 0 END ASC, $unreadCount $direction, $titleOrder ASC"

		// tracks.last_chapter_date is only filled in for tracked manga, so the cached chapters win
		ListSortOrder.Type.LATEST_CHAPTER ->
			"IFNULL((SELECT MAX(upload_date) FROM chapters WHERE chapters.manga_id = manga.manga_id), " +
				"IFNULL((SELECT last_chapter_date FROM tracks WHERE tracks.manga_id = manga.manga_id), 0))"

		ListSortOrder.Type.DATE_ADDED -> dateAdded

		ListSortOrder.Type.PROGRESS -> progress

		ListSortOrder.Type.NEW_CHAPTERS ->
			"IFNULL((SELECT chapters_new FROM tracks WHERE tracks.manga_id = manga.manga_id), 0)"
	}
	// Mihon's DATE_ADDED comparator is stable: entries with the same timestamp keep their source
	// library order. Mihon restore stores that stable sequence in favourites.sort_key, so use it
	// before the title fallback. Normal non-restore favourites use the same sort key for ties and
	// therefore keep deterministic behaviour without changing the primary date ordering.
	val stableDateAddedTieBreaker = if (type == ListSortOrder.Type.DATE_ADDED) {
		when (dateAdded) {
			"favourites.created_at" -> "favourites.sort_key"
			"private_favourites.created_at" -> "private_favourites.sort_key"
			else -> null
		}
	} else {
		null
	}
	return buildString {
		append(expression)
		append(' ')
		append(direction)
		if (stableDateAddedTieBreaker != null) {
			append(", ")
			append(stableDateAddedTieBreaker)
			append(" ASC")
		}
		append(", ")
		append(titleOrder)
		append(" ASC")
	}
}
