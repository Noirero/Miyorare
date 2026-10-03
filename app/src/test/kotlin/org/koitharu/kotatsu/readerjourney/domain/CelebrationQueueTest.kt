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
		runCurrent()
		assertEquals(listOf(1, 2), presented)
		queue.close()
		advanceUntilIdle()
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
			presenter = { presented += it },
		)

		queue.enqueue(event(1))
		mode = ReaderJourneyCelebrationMode.SUBTLE
		reduceMotion = true
		queue.enqueue(event(2))
		runCurrent()

		assertEquals(1, presented.size)
		assertEquals(2, presented.single().event.xpEarned)
		assertEquals(ReaderJourneyCelebrationMode.SUBTLE, presented.single().mode)
		assertTrue(presented.single().reduceMotion)
		queue.close()
		advanceUntilIdle()
	}

	@Test
	fun `full mode routes rich events while subtle stays snackbar`() {
		val achievement = event(25).copy(unlockedAchievements = listOf(ReaderAchievementId.FIRST_CHAPTER))
		val cosmetics = ReaderJourneyCosmetics.newlyUnlocked(ReaderRank.NEWCOMER, ReaderRank.READER)
		val cosmetic = event(0).copy(unlockedCosmetics = cosmetics)
		val rankUp = event(100).copy(
			fromLevel = 9,
			toLevel = 10,
			fromRank = ReaderRank.NEWCOMER,
			toRank = ReaderRank.READER,
			unlockedCosmetics = cosmetics,
		)

		assertEquals(CelebrationPresentation.ACHIEVEMENT, item(achievement).presentation())
		assertEquals(CelebrationPresentation.COSMETIC, item(cosmetic).presentation())
		assertEquals(
			listOf(CelebrationPresentation.COSMETIC),
			item(cosmetic).presentations(),
		)
		assertEquals(CelebrationPresentation.RANK_UP, item(rankUp).presentation())
		assertEquals(
			listOf(CelebrationPresentation.RANK_UP, CelebrationPresentation.COSMETIC),
			item(rankUp).presentations(),
		)
		assertEquals(CelebrationPresentation.SNACKBAR, item(event(10)).presentation())
		assertEquals(
			CelebrationPresentation.ACHIEVEMENT,
			item(achievement).copy(reduceMotion = true).presentation(),
		)
		assertEquals(
			CelebrationPresentation.SNACKBAR,
			item(achievement, ReaderJourneyCelebrationMode.SUBTLE).presentation(),
		)
	}

	private fun item(
		event: ReaderJourneyCelebration,
		mode: ReaderJourneyCelebrationMode = ReaderJourneyCelebrationMode.FULL,
	) = CelebrationQueueItem(event, mode, reduceMotion = false)

	private fun event(xp: Int) = ReaderJourneyCelebration(
		xpEarned = xp,
		fromLevel = 1,
		toLevel = 1,
		fromRank = ReaderRank.NEWCOMER,
		toRank = ReaderRank.NEWCOMER,
	)
}
