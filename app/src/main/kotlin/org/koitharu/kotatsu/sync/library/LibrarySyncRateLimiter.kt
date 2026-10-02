package org.koitharu.kotatsu.sync.library

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibrarySyncRateLimiter(private val minimumIntervalMs: Long) {
	private val mutex = Mutex()
	private var lastRequestAt = 0L

	suspend fun awaitPermit() = mutex.withLock {
		val now = System.currentTimeMillis()
		val wait = (lastRequestAt + minimumIntervalMs - now).coerceAtLeast(0L)
		if (wait > 0) delay(wait)
		lastRequestAt = System.currentTimeMillis()
	}
}
