package org.koitharu.kotatsu.reader.domain

import org.jsoup.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

class PageMetadataRecoverySessionTest {

	@Test
	fun twoDistinctCurrentPagesTriggerExactlyOnce() {
		val session = PageMetadataRecoverySession()
		val first = readerPage(id = 11L, chapterId = 7L, index = 0)
		val second = readerPage(id = 12L, chapterId = 7L, index = 1)
		val pages = listOf(first, second)

		assertNull(session.record(pages, failure(first)))
		assertEquals(second, session.record(pages, failure(second)))
		assertNull(session.record(pages, failure(first)))
	}

	@Test
	fun repeatedSamePageAndStaleHolderDoNotTrigger() {
		val session = PageMetadataRecoverySession()
		val current = readerPage(id = 11L, chapterId = 7L, index = 0)
		val stale = current.copy(url = "https://example.test/old")
		val pages = listOf(current)

		assertNull(session.record(pages, failure(current)))
		assertNull(session.record(pages, failure(current)))
		assertNull(session.record(pages, failure(stale)))
	}

	@Test
	fun non404DoesNotParticipate() {
		val session = PageMetadataRecoverySession()
		val page = readerPage(id = 11L, chapterId = 7L, index = 0)
		val failure = PageLoadFailure(
			page = page.toMangaPage(),
			error = HttpStatusException("forbidden", 403, page.url),
		)

		assertNull(session.record(listOf(page), failure))
	}

	private fun failure(page: ReaderPage) = PageLoadFailure(
		page = page.toMangaPage(),
		error = HttpStatusException("missing", 404, page.url),
	)

	private fun readerPage(id: Long, chapterId: Long, index: Int) = ReaderPage(
		id = id,
		url = "https://example.test/$id",
		preview = null,
		chapterId = chapterId,
		index = index,
		source = UnknownMangaSource,
	)
}
