package org.koitharu.kotatsu.details.data

import org.koitharu.kotatsu.parsers.model.MangaChapter

/** A source-owned locator, scoped to its manga. Never use a title, number or list position. */
data class ChapterPersonalKey(val source: String, val url: String) {
	companion object {
		fun of(chapter: MangaChapter) = ChapterPersonalKey(chapter.source.name, chapter.url)
	}
}

data class ChapterPersonalMetadata(val rating: Int? = null, val note: String? = null) {
	init {
		require(rating == null || rating in 1..5)
		require(note == null || (note.isNotBlank() && note == note.trim() && note.length <= MAX_NOTE_LENGTH))
	}

	val isEmpty: Boolean get() = rating == null && note == null

	companion object {
		const val MAX_NOTE_LENGTH = 1000
		fun normalized(rating: Int?, note: String?) = ChapterPersonalMetadata(
			rating = rating,
			note = note?.trim()?.takeIf(String::isNotEmpty),
		)
	}
}
