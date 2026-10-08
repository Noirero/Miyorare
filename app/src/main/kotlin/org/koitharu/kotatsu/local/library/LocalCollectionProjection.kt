package org.koitharu.kotatsu.local.library

import org.koitharu.kotatsu.history.data.HistoryEntity

/** Pure projection of the selected-root index; never parses archives or touches storage. */
internal fun projectLocalCollection(
	books: List<LocalBook>,
	historiesByKey: Map<String, HistoryEntity>,
	query: String?,
	type: LocalContentType?,
	filter: LocalReadingFilter,
	sort: LocalLibrarySort,
	showExtensions: Boolean,
): List<LocalBook> {
	fun title(book: LocalBook) = book.title ?: LocalTreeScanner.displayName(book.node.name, showExtensions, book.node.directory)
	val needle = query?.trim().orEmpty()
	val filtered = books.filter { book ->
		val history = historiesByKey[book.node.key]
		(type == null || book.contentType == type) && when (filter) {
			LocalReadingFilter.ALL -> true
			LocalReadingFilter.UNREAD -> history == null
			LocalReadingFilter.READING -> history != null && !book.isCompleted(history)
			LocalReadingFilter.COMPLETED -> book.isCompleted(history)
		} && (needle.isEmpty() || title(book).contains(needle, true) || book.authors.any { it.contains(needle, true) })
	}
	return when (sort) {
		LocalLibrarySort.LAST_READ -> filtered.sortedByDescending { historiesByKey[it.node.key]?.updatedAt ?: 0L }
		LocalLibrarySort.ADDED -> filtered.sortedByDescending { it.addedAt }
		LocalLibrarySort.TITLE_ASC -> filtered.sortedWith(compareBy(LocalTreeScanner.NATURAL, ::title))
		LocalLibrarySort.TITLE_DESC -> filtered.sortedWith(compareByDescending(LocalTreeScanner.NATURAL, ::title))
		LocalLibrarySort.CHAPTER_UPDATED -> filtered.sortedByDescending { it.latestChapterAt }
	}
}

internal fun pendingLocalDiscoveries(previous: Int, discovered: Int, chapterCount: Int): Int =
	(previous.coerceAtLeast(0).toLong() + discovered.coerceAtLeast(0)).coerceAtMost(chapterCount.coerceAtLeast(0).toLong()).toInt()

internal fun restoreLocalContentSelection(hasSavedValue: Boolean, savedValue: String?, persisted: LocalContentType?): LocalContentType? =
    if (hasSavedValue) LocalContentType.entries.firstOrNull { it.name == savedValue } else persisted

internal enum class LocalCollectionEmptyReason { NO_FOLDERS, ACCESS, SEARCH, MANGA, NOVEL, NO_CONTENT, FILTER }

internal fun localCollectionEmptyReason(rootCount: Int, indexedTypes: Set<LocalContentType>, hasDiagnoses: Boolean,
    query: String?, type: LocalContentType?): LocalCollectionEmptyReason = when {
    rootCount == 0 -> LocalCollectionEmptyReason.NO_FOLDERS
    indexedTypes.isEmpty() && hasDiagnoses -> LocalCollectionEmptyReason.ACCESS
    !query.isNullOrBlank() -> LocalCollectionEmptyReason.SEARCH
    type == LocalContentType.MANGA && LocalContentType.MANGA !in indexedTypes -> LocalCollectionEmptyReason.MANGA
    type == LocalContentType.NOVEL && LocalContentType.NOVEL !in indexedTypes -> LocalCollectionEmptyReason.NOVEL
    indexedTypes.isEmpty() -> LocalCollectionEmptyReason.NO_CONTENT
    else -> LocalCollectionEmptyReason.FILTER
}
