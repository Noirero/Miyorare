package org.koitharu.kotatsu.favourites.ui.list

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesListWorkScopeTest {

	@Test
	fun `retained CREATED consumers cannot keep background database subscription active`() = runTest {
		val work = FavouritesListWorkScope()
		val database = MutableStateFlow(listOf(1, 2))
		val content = work.whileActive(database).stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
		// Mirrors observe(owner): both collectors survive while the view owner is CREATED.
		backgroundScope.launch { content.collect() }
		backgroundScope.launch { content.collect() }
		runCurrent()
		assertEquals(0, database.subscriptionCount.value)
		work.setActive(true)
		runCurrent()
		assertEquals(1, database.subscriptionCount.value)
		assertEquals(listOf(1, 2), content.value)
		work.setActive(false)
		runCurrent()
		assertEquals(0, database.subscriptionCount.value)
		assertEquals(listOf(1, 2), content.value)
		database.value = listOf(3, 4, 5)
		runCurrent()
		assertEquals(listOf(1, 2), content.value)
		work.setActive(true)
		runCurrent()
		assertEquals(1, database.subscriptionCount.value)
		assertEquals(listOf(3, 4, 5), content.value)
	}

	@Test
	fun `rapid resume waits for in-flight database unwind before starting another producer`() = runTest {
		val work = FavouritesListWorkScope()
		val release = CompletableDeferred<Unit>()
		var started = 0
		var inFlight = 0
		var peak = 0
		val database = flow {
			val generation = ++started
			inFlight++
			peak = maxOf(peak, inFlight)
			try {
				emit(generation)
				awaitCancellation()
			} finally {
				if (generation == 1) withContext(NonCancellable) { release.await() }
				inFlight--
			}
		}
		val content = work.whileActive(database).stateIn(backgroundScope, SharingStarted.Eagerly, 0)
		work.setActive(true)
		runCurrent()
		work.setActive(false)
		runCurrent()
		work.setActive(true)
		runCurrent()
		assertEquals(1, started)
		assertEquals(1, content.value)
		release.complete(Unit)
		runCurrent()
		assertEquals(2, started)
		assertEquals(2, content.value)
		assertEquals(1, peak)
	}

	@Test
	fun `pause drains replaced cancelled enrichment jobs before acknowledging completion`() = runTest {
		val release = CompletableDeferred<Unit>()
		var stopped = 0
		val work = FavouritesListWorkScope { stopped++ }
		var oldJob: Job? = null
		var currentJob: Job? = null
		val source = flow {
			oldJob = work.launch(StandardTestDispatcher(testScheduler)) {
				try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
			}
			emit(1)
			awaitCancellation()
		}
		work.setActive(true)
		backgroundScope.launch { work.whileActive(source).collect() }
		runCurrent()
		oldJob!!.cancel()
		currentJob = work.launch(StandardTestDispatcher(testScheduler)) { awaitCancellation() }
		runCurrent()
		work.setActive(false)
		runCurrent()
		assertTrue(currentJob!!.isCancelled)
		assertFalse(oldJob!!.isCompleted)
		assertEquals(0, stopped)
		release.complete(Unit)
		runCurrent()
		assertTrue(oldJob!!.isCompleted)
		assertTrue(currentJob!!.isCompleted)
		assertEquals(1, stopped)
	}

	@Test
	fun `cancelled enrichment cannot overwrite state from a resumed generation`() = runTest {
		val release = CompletableDeferred<Unit>()
		val enrichment = MutableStateFlow(0)
		val work = FavouritesListWorkScope()
		var generation = 0
		var stalePublication = false
		val source = flow {
			val current = ++generation
			work.launch(StandardTestDispatcher(testScheduler)) {
				if (current == 1) {
					// Models a DB operation returning after cancellation, before its next suspension.
					withContext(NonCancellable) { release.await() }
					coroutineContext.ensureActive()
					stalePublication = true
				}
				enrichment.value = current
			}
			emit(current)
			awaitCancellation()
		}
		work.setActive(true)
		backgroundScope.launch { work.whileActive(source).collect() }
		runCurrent()
		work.setActive(false)
		runCurrent()
		work.setActive(true)
		runCurrent()
		assertEquals(1, generation)
		release.complete(Unit)
		runCurrent()
		assertEquals(2, generation)
		assertEquals(2, enrichment.value)
		assertFalse(stalePublication)
	}

	@Test
	fun `stopped owner does not react to database invalidations or run new enrichment`() = runTest {
		val work = FavouritesListWorkScope()
		val database = MutableStateFlow(1)
		var mapped = 0
		var enriched = 0
		val source = database.onEach {
			mapped++
			work.launch(StandardTestDispatcher(testScheduler)) { enriched++ }
		}
		backgroundScope.launch { work.whileActive(source).collect() }
		work.setActive(true)
		runCurrent()
		work.setActive(false)
		runCurrent()
		database.value = 2
		runCurrent()
		assertEquals(1, mapped)
		assertEquals(1, enriched)
		work.setActive(true)
		runCurrent()
		assertEquals(2, mapped)
		assertEquals(2, enriched)
	}

	@Test
	fun `another active category or container cannot activate a stopped list owner`() = runTest {
		val main = FavouritesListWorkScope()
		val private = FavouritesListWorkScope()
		val firstCategory = MutableStateFlow(1)
		val secondCategory = MutableStateFlow(2)
		backgroundScope.launch { main.whileActive(firstCategory).collect() }
		backgroundScope.launch { private.whileActive(secondCategory).collect() }
		main.setActive(true)
		private.setActive(true)
		runCurrent()
		main.setActive(false)
		runCurrent()
		assertEquals(0, firstCategory.subscriptionCount.value)
		assertEquals(1, secondCategory.subscriptionCount.value)
		private.setActive(false)
		runCurrent()
		assertEquals(0, secondCategory.subscriptionCount.value)
	}

	@Test
	fun `repeated activation and recreation leave one producer and no escaped child jobs`() = runTest {
		val database = MutableStateFlow(1)
		val work = FavouritesListWorkScope()
		var auxiliary: Job? = null
		val source = database.onEach { auxiliary = work.launch(StandardTestDispatcher(testScheduler)) { awaitCancellation() } }
		val sharing = backgroundScope.launch { work.whileActive(source).collect() }
		repeat(3) {
			work.setActive(true)
			work.setActive(true)
			runCurrent()
			assertEquals(1, database.subscriptionCount.value)
			work.setActive(false)
			runCurrent()
			assertEquals(0, database.subscriptionCount.value)
			assertTrue(auxiliary!!.isCompleted)
		}
		work.setActive(true)
		runCurrent()
		sharing.cancel()
		runCurrent()
		assertEquals(0, database.subscriptionCount.value)
		assertTrue(auxiliary!!.isCompleted)
		val recreated = FavouritesListWorkScope()
		backgroundScope.launch { recreated.whileActive(database).collect() }
		recreated.setActive(true)
		runCurrent()
		assertEquals(1, database.subscriptionCount.value)
	}
}
