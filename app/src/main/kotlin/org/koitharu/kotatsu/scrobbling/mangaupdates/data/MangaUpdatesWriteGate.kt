package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The list API requires five seconds between writes. Waiting suspends, never blocks the UI. */
internal class MangaUpdatesWriteGate(
	private val now: () -> Long = { System.nanoTime() / 1_000_000 },
	private val wait: suspend (Long) -> Unit = { delay(it) },
) {
	private val mutex = Mutex()
	private var lastAttempt: Long? = null
	suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock {
		lastAttempt?.let { previous -> (5000 - (now() - previous)).takeIf { it > 0 }?.let { wait(it) } }
		lastAttempt = now()
		block()
	}
}
