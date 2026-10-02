package org.koitharu.kotatsu.sync.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySyncRateLimiterTest {
	@Test
	fun `concurrent permits are spaced per service`() = runTest {
		val limiter = LibrarySyncRateLimiter(1000) { testScheduler.currentTime }
		val timestamps =
			(1..3)
				.map {
					async {
						limiter.awaitPermit()
						testScheduler.currentTime
					}
				}
				.awaitAll()
		assertEquals(listOf(0L, 1000L, 2000L), timestamps)
	}

	@Test
	fun `services do not block one another`() = runTest {
		val first = LibrarySyncRateLimiter(1000) { testScheduler.currentTime }
		val second = LibrarySyncRateLimiter(1000) { testScheduler.currentTime }
		first.awaitPermit()
		second.awaitPermit()
		assertEquals(0L, testScheduler.currentTime)
	}
}
