package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koitharu.kotatsu.parsers.model.MangaPage

/**
 * Reader page holders report terminal load failures here so the reader session can decide whether
 * the page-list metadata is stale. Cancellation never reaches this stream, and the session maps an
 * event back to its current ReaderPage before acting, so recycled/stale holders are ignored.
 *
 * Reporting suspends until active collectors accept the event. Recovery correctness depends on
 * observing both distinct 404s, so these signals must not be silently dropped by a bounded buffer.
 */
internal object PageLoadFailureEvents {
	private val mutableEvents = MutableSharedFlow<PageLoadFailure>()
	val events: SharedFlow<PageLoadFailure> = mutableEvents.asSharedFlow()

	suspend fun report(page: MangaPage, error: Throwable) {
		mutableEvents.emit(PageLoadFailure(page, error))
	}
}

internal data class PageLoadFailure(
	val page: MangaPage,
	val error: Throwable,
)
