package org.koitharu.kotatsu.sync.library

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibrarySyncRateLimiter(
	private val minimumIntervalMs: Long,
	private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
	private val mutex = Mutex()
	private var lastRequestAt: Long? = null

	init {
		require(minimumIntervalMs >= 0)
	}

	suspend fun awaitPermit() = mutex.withLock {
		val now = nowMs()
		val wait = lastRequestAt?.let { (it + minimumIntervalMs - now).coerceAtLeast(0L) } ?: 0L
		if (wait > 0) delay(wait)
		lastRequestAt = nowMs()
	}
}
