package org.koitharu.kotatsu.reader.domain

import android.os.SystemClock
import org.koitharu.kotatsu.core.diagnostics.PageRoute
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics
import org.koitharu.kotatsu.core.diagnostics.ReaderEvent

/** Only a publicly classified Reader may create a trace, and callbacks recheck that decision. */
internal class ReaderDiagnostics(private val isPublic: () -> Boolean) {
	private var active: Operation? = null

	@Synchronized
	fun begin(): Operation? {
		val recording = PerformanceDiagnostics.recordingId() ?: return null
		if (!isPublic()) return null
		val existing = active
		if (existing?.recording == recording) return existing
		val reader = PerformanceDiagnostics.subjectId(recording) ?: return null
		return Operation(recording, reader, SystemClock.elapsedRealtime()).also { active = it }
	}

	inner class Operation(val recording: String, private val reader: Long, private val started: Long) {
		private val routes = java.util.EnumSet.noneOf(PageRoute::class.java)
		private var firstPageReady = false

		fun event(event: ReaderEvent) {
			if (isPublic()) PerformanceDiagnostics.reader(recording, reader, event)
		}

		@Synchronized
		fun route(route: PageRoute) {
			if (isPublic() && routes.add(route)) PerformanceDiagnostics.pageRoute(recording, reader, route)
		}

		@Synchronized
		fun pageReady() {
			if (!isPublic() || firstPageReady) return
			firstPageReady = true
			PerformanceDiagnostics.reader(recording, reader, ReaderEvent.FIRST_PAGE_READY,
				SystemClock.elapsedRealtime() - started)
		}
	}
}
