package org.koitharu.kotatsu.download.domain

import android.os.SystemClock
import android.util.Log
import org.koitharu.kotatsu.BuildConfig
import java.util.UUID

/** Debug-only, opt-in, process-local trace. Never log source URLs, paths, titles or exception text. */
internal object DownloadObservation {
	private const val TAG = "MiyorareObs01"
	private const val MAX_TRACKED = 32
	private val tracked = LinkedHashSet<UUID>()

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
