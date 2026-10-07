package org.koitharu.kotatsu.reader.domain

import org.jsoup.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

class PageMetadataRecoveryTest {

	@Test
	fun onlyHttp404QualifiesForStaleMetadataRecovery() {
		assertTrue(PageMetadataRecovery.isNotFound(HttpStatusException("missing", 404, "https://example.test/1")))
		assertFalse(PageMetadataRecovery.isNotFound(HttpStatusException("forbidden", 403, "https://example.test/1")))
		assertFalse(PageMetadataRecovery.isNotFound(IllegalStateException("not http")))
	}

	@Test
	fun failedPageMapsOnlyToExactCurrentReaderPage() {
		val readerPage = page(id = 11L, chapterId = 7L, index = 0, url = "https://example.test/a")
		val failed = MangaPage(11L, "https://example.test/a", null, UnknownMangaSource)
		assertEquals(readerPage, PageMetadataRecovery.findReaderPage(listOf(readerPage), failed))

		val staleFailure = MangaPage(11L, "https://example.test/old", null, UnknownMangaSource)
		assertEquals(null, PageMetadataRecovery.findReaderPage(listOf(readerPage), staleFailure))
	}

	@Test
	fun preserveStateFollowsSamePageIdAfterReorder() {
		val state = ReaderState(chapterId = 7L, page = 1, scroll = 13)
		val fresh = listOf(
			page(id = 22L, chapterId = 7L, index = 0),
			page(id = 33L, chapterId = 7L, index = 1),
			page(id = 11L, chapterId = 7L, index = 2),
		)
		assertEquals(state.copy(page = 2), PageMetadataRecovery.preserveState(state, oldPageId = 11L, freshPages = fresh))
	}

	@Test
	fun preserveStateClampsRemovedPageAndIgnoresNeighbours() {
		val state = ReaderState(chapterId = 7L, page = 4, scroll = 9)
		val fresh = listOf(
			page(id = 1L, chapterId = 6L, index = 0),
			page(id = 2L, chapterId = 7L, index = 0),
			page(id = 3L, chapterId = 7L, index = 1),
			page(id = 4L, chapterId = 8L, index = 0),
		)
		assertEquals(state.copy(page = 1), PageMetadataRecovery.preserveState(state, oldPageId = 99L, freshPages = fresh))
	}

	private fun page(
		id: Long,
		chapterId: Long,
		index: Int,
		url: String = "https://example.test/$id",
	) = ReaderPage(
		id = id,
		url = url,
		preview = null,
		chapterId = chapterId,
		index = index,
		source = UnknownMangaSource,
	)
}
