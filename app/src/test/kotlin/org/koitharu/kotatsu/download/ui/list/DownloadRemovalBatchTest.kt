package org.koitharu.kotatsu.download.ui.list

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.koitharu.kotatsu.local.domain.UnverifiedDownloadRemovalException
import java.io.IOException

class DownloadRemovalBatchTest {
	private val initial = DownloadRemovalFeedback(0, 1, 0, true)

	@Test fun reportsVerifiedRejectionsAndContinues() = runBlocking {
		val result = processDownloadRemoval(listOf(1, 2, 3, 4), initial) {
			when (it) {
				1 -> throw UnverifiedDownloadRemovalException()
				2 -> throw UnverifiedDownloadRemovalException(missing = true)
				3 -> DownloadRemovalOutcome.REMOVED
				else -> DownloadRemovalOutcome.ACTIVE
			}
		}
		assertEquals(initial.copy(removed = 1, active = 2, ambiguous = 1, missing = 1), result)
	}

	@Test fun preservesCommittedOutcomeAndStopsOnUnexpectedFailure() = runBlocking {
		val committed = mutableListOf<Int>()
		val attempted = mutableListOf<Int>()
		val result = processDownloadRemoval(listOf(1, 2, 3), initial) {
			attempted += it
			if (it == 2) throw IOException("Injected failure")
			committed += it
			DownloadRemovalOutcome.REMOVED
		}
		assertEquals(listOf(1, 2), attempted)
		assertEquals(listOf(1), committed)
		assertEquals(initial.copy(removed = 1, failed = 1, notAttempted = 1), result)
	}

	@Test fun propagatesCancellation() = runBlocking {
		try {
			processDownloadRemoval(listOf(1), initial) { throw CancellationException("cancel") }
			fail("Expected cancellation")
		} catch (expected: CancellationException) {
			assertEquals("cancel", expected.message)
		}
	}
}
