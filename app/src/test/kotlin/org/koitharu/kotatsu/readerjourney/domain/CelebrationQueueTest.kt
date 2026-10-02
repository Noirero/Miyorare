package org.koitharu.kotatsu.readerjourney.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.ReaderJourneyCelebrationMode

class CelebrationQueueTest {

	@Test
	fun `events are presented one at a time in order`() = runTest {
		val firstDismissed = CompletableDeferred<Unit>()
		val presented = mutableListOf<Int>()
		val queue = CelebrationQueue(
			scope = this,
			modeProvider = { ReaderJourneyCelebrationMode.FULL },
			reduceMotionProvider = { false },
		) { item ->
			presented += item.event.xpEarned
			if (item.event.xpEarned == 1) firstDismissed.await()
		}

		queue.enqueue(event(1))
		queue.enqueue(event(2))
		runCurrent()
		assertEquals(listOf(1), presented)

		firstDismissed.complete(Unit)
		advanceUntilIdle()
		assertEquals(listOf(1, 2), presented)
	}

	@Test
	fun `off mode drops events and presentation uses latest motion preference`() = runTest {
		var mode = ReaderJourneyCelebrationMode.OFF
		var reduceMotion = false
		val presented = mutableListOf<CelebrationQueueItem>()
		val queue = CelebrationQueue(
			scope = this,
			modeProvider = { mode },
			reduceMotionProvider = { reduceMotion },
			presenter = presented::add,
		)

		queue.enqueue(event(1))
		mode = ReaderJourneyCelebrationMode.SUBTLE
		reduceMotion = true
		queue.enqueue(event(2))
		advanceUntilIdle()

		assertEquals(1, presented.size)
		assertEquals(2, presented.single().event.xpEarned)
		assertEquals(ReaderJourneyCelebrationMode.SUBTLE, presented.single().mode)
		assertTrue(presented.single().reduceMotion)
	}

	private fun event(xp: Int) = ReaderJourneyCelebration(
		xpEarned = xp,
		fromLevel = 1,
		toLevel = 1,
		fromRank = ReaderRank.NEWCOMER,
		toRank = ReaderRank.NEWCOMER,
	)
}
