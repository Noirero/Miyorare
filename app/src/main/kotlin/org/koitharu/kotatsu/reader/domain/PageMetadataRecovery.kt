package org.koitharu.kotatsu.reader.domain

import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.net.HttpURLConnection

internal object PageMetadataRecovery {

	fun isNotFound(error: Throwable): Boolean {
		return error is HttpStatusException && error.statusCode == HttpURLConnection.HTTP_NOT_FOUND
	}

	fun findReaderPage(pages: List<ReaderPage>, failedPage: MangaPage): ReaderPage? {
		return pages.firstOrNull { page ->
			page.source == failedPage.source && page.id == failedPage.id && page.url == failedPage.url
		}
	}

	/**
	 * Keep the logical page when a refreshed list only moved it. If the source removed that page,
	 * preserve the old index as closely as possible and clamp it to the refreshed chapter bounds.
	 */
	fun preserveState(
		state: ReaderState,
		oldPageId: Long?,
		freshPages: List<ReaderPage>,
	): ReaderState {
		val chapterPages = freshPages.filter { it.chapterId == state.chapterId }
		if (chapterPages.isEmpty()) return state
		val matchingIndex = oldPageId?.let { id -> chapterPages.indexOfFirst { it.id == id } }
		val pageIndex = if (matchingIndex != null && matchingIndex >= 0) {
			chapterPages[matchingIndex].index
		} else {
			state.page.coerceIn(0, chapterPages.lastIndex)
		}
		return state.copy(page = pageIndex)
	}
}
