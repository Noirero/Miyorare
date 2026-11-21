package org.koitharu.kotatsu.download.domain

import android.os.SystemClock
import android.util.Log
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.diagnostics.DownloadEvent
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics
import java.util.UUID

/** Progress tracing for debug and a bounded, structured subset for approved verbose sessions. */
internal object DownloadObservation {
	private const val TAG = "MiyorareObs01"
	private const val MAX_TRACKED = 32
	private val tracked = LinkedHashSet<UUID>()

	fun exportFor(recording: String): Export? {
		val operation = PerformanceDiagnostics.subjectId(recording) ?: return null
		return Export(recording, operation)
	}

	class Export(private val recording: String, private val operation: Long) {
		val active: Boolean get() = PerformanceDiagnostics.recordingId() == recording

		fun record(event: DownloadEvent, expected: Int, actual: Int) =
			PerformanceDiagnostics.download(recording, operation, event, expected, actual)

		fun archiveCount(expected: Int, actual: Int?) {
			val event = when (actual) {
				null -> DownloadEvent.ARCHIVE_UNREADABLE
				expected -> DownloadEvent.ARCHIVE_VERIFIED
				else -> DownloadEvent.ARCHIVE_PAGE_COUNT_MISMATCH
			}
			record(event, expected, actual ?: -1)
		}
	}

	val enabled: Boolean get() = BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)

	fun start(id: UUID, attempt: Int) {
		if (!enabled) return
		synchronized(tracked) {
			tracked.remove(id)
			tracked.add(id)
			if (tracked.size > MAX_TRACKED) {
				tracked.iterator().apply { next(); remove() }
			}
		}
		record(id, "worker_start attempt=$attempt")
	}

	fun tracks(id: UUID): Boolean = enabled && synchronized(tracked) { id in tracked }

	fun record(id: UUID, message: String) {
		Log.d(TAG, "t_ns=${SystemClock.elapsedRealtimeNanos()} work=$id $message")
	}
}
