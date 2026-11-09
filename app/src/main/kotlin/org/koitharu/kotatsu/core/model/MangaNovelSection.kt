package org.koitharu.kotatsu.core.model

import org.koitharu.kotatsu.parsers.model.Manga

/** UI-only separation; downloads and reading history retain their existing storage ownership. */
enum class MangaNovelSection {
	MANGA,
	NOVEL;

	fun includes(isNovel: Boolean): Boolean = (this == NOVEL) == isNovel

	companion object {
		fun fromSaved(value: String?): MangaNovelSection =
			entries.firstOrNull { it.name == value } ?: MANGA
	}
}

fun Manga.matchesMangaNovelSection(section: MangaNovelSection): Boolean =
	section.includes(isNovelContent)
