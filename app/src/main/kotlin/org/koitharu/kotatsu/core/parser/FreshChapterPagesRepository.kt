package org.koitharu.kotatsu.core.parser

import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage

/**
 * Capability for callers that have evidence the cached chapter page list is stale.
 * Ordinary Reader/Downloader paths should continue to use [MangaRepository.getPages].
 */
interface FreshChapterPagesRepository {
	suspend fun getFreshPages(chapter: MangaChapter): List<MangaPage>
}
