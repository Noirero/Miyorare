package org.koitharu.kotatsu.details.ui.pager

import org.koitharu.kotatsu.details.ui.model.ChapterListItem

/** null means normal chapter search; an empty note query leaves the current list intact. */
fun List<ChapterListItem>.filterChapterSearch(chapterQuery: String, notesQuery: String?): List<ChapterListItem> {
	val query = notesQuery?.trim() ?: chapterQuery
	if (query.isEmpty() || isEmpty()) return this
	return if (notesQuery != null) {
		filter { it.personalMetadata.note?.contains(query, ignoreCase = true) == true }
	} else {
		filter { it.contains(query) }
	}
}
