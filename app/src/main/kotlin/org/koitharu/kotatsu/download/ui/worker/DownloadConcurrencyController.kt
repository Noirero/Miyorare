package org.koitharu.kotatsu.download.ui.worker

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide worker gate for manga/novel downloads. The configured "parallel sources" value is
 * treated as the number of download jobs that may run concurrently, regardless of whether two jobs
 * happen to come from the same Mihon source. Page concurrency is still limited independently by
 * [DownloadPerformanceSettings.parallelPageLimit] inside every worker.
 */
@Singleton
class DownloadConcurrencyController @Inject constructor() {
	private val mutex = Mutex()
	private var activeDownloads = 0
	private val revision = MutableStateFlow(0L)

	/** Compatibility entry point for existing workers; [sourceKey] no longer serializes same-source jobs. */
	suspend fun <T> withSourcePermit(
		@Suppress("UNUSED_PARAMETER") sourceKey: String,
		limit: Int,
		block: suspend () -> T,
	): T = withPermit(limit, block)

	suspend fun <T> withPermit(
		limit: Int,
		block: suspend () -> T,
	): T {
		val pausingHandle = PausingHandle.current()
		acquire(limit.coerceAtLeast(1), pausingHandle)
		return try {
			block()
		} finally {
			// A cancelled worker must still release its slot. Without NonCancellable a cancellation
			// arriving at mutex acquisition can strand the permit until process restart.
			withContext(NonCancellable) {
				release()
			}
		}
	}

	private suspend fun acquire(limit: Int, pausingHandle: PausingHandle) {
		while (true) {
			// A download paused while queued must remain outside the active slots until it resumes.
			pausingHandle.yield()
			val observedRevision = revision.value
			val acquired = mutex.withLock {
				if (activeDownloads < limit && !pausingHandle.isPaused) {
					activeDownloads++
					true
				} else {
					false
				}
			}
			if (acquired) return
			// Wake for either a released slot or a pause command. This avoids a queued worker sitting
			// inside revision.first() until some unrelated download completes before it notices pause.
			combine(revision, pausingHandle.pauseState) { currentRevision, paused ->
				currentRevision != observedRevision || paused
			}.first { it }
		}
	}

	private suspend fun release() {
		mutex.withLock {
			if (activeDownloads > 0) {
				activeDownloads--
				revision.value = revision.value + 1L
			}
		}
	}
}
