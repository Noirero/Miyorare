package org.koitharu.kotatsu.reader.domain

import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

/**
 * Reader-session owner for stale page-list detection. Only terminal HTTP 404 failures that still map
 * to the current Reader snapshot participate. Two distinct pages from the same source + chapter
 * trigger recovery once; subsequent failures for that chapter are ignored for the rest of session.
 */
internal class PageMetadataRecoverySession(
	private val policy: PageMetadataRecoveryPolicy = PageMetadataRecoveryPolicy(),
) {
	fun record(pages: List<ReaderPage>, failure: PageLoadFailure): ReaderPage? {
		if (!PageMetadataRecovery.isNotFound(failure.error)) return null
		val readerPage = PageMetadataRecovery.findReaderPage(pages, failure.page) ?: return null
		return if (
			policy.recordNotFound(
				sourceName = failure.page.source.name,
				chapterId = readerPage.chapterId,
				pageId = failure.page.id,
			)
		) {
			readerPage
		} else {
			null
		}
	}

	fun clear() = policy.clear()
}
