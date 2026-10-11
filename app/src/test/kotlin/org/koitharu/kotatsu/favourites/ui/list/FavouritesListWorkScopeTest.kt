package org.koitharu.kotatsu.favourites.ui.list

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
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
		var producer: Job? = null
		val database = flow {
			producer = currentCoroutineContext()[Job]
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
		assertTrue(producer!!.isCancelled)
		// No dispatch/yield between pause and resume: the inactive value may be conflated.
		work.setActive(true)
		runCurrent()
		assertEquals(1, started)
		assertEquals(1, content.value)
		// Supersede a resume whose collector is still waiting for the previous generation to drain.
		work.setActive(false)
		work.setActive(true)
		runCurrent()
		assertEquals(1, started)
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
		checkNotNull(oldJob).cancel()
		currentJob = work.launch(StandardTestDispatcher(testScheduler)) { awaitCancellation() }
		runCurrent()
		work.setActive(false)
		assertTrue(checkNotNull(currentJob).isCancelled)
		assertFalse(checkNotNull(oldJob).isCompleted)
		assertEquals(0, stopped)
		runCurrent()
		assertTrue(checkNotNull(currentJob).isCancelled)
		assertFalse(checkNotNull(oldJob).isCompleted)
		assertEquals(0, stopped)
		release.complete(Unit)
		runCurrent()
		assertTrue(checkNotNull(oldJob).isCompleted)
		assertTrue(checkNotNull(currentJob).isCompleted)
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

	@Test
	fun `pause without stop requests producer cancellation before returning`() = runTest {
		val owner = FavouritesListWorkOwner()
		val release = CompletableDeferred<Unit>()
		var stopped = 0
		val work = FavouritesListWorkScope { stopped++ }
		var producer: Job? = null
		val database = flow {
			producer = currentCoroutineContext()[Job]
			try {
				emit(listOf(1, 2))
				awaitCancellation()
			} finally {
				withContext(NonCancellable) { release.await() }
			}
		}
		val content = work.whileActive(database).stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
		owner.onStart()
		work.setActive(owner.isActive(hasView = true, hidden = false))
		runCurrent()
		assertEquals(null, producer)
		owner.onResume()
		work.setActive(owner.isActive(hasView = true, hidden = false))
		runCurrent()
		owner.onPause() // The source remains STARTED. No onStop, dispatcher run or yield.
		work.setActive(owner.isActive(hasView = true, hidden = false))
		assertTrue(producer!!.isCancelled)
		assertFalse(producer!!.isCompleted)
		assertEquals(0, stopped)
		assertEquals(listOf(1, 2), content.value)
		runCurrent()
		assertEquals(0, stopped)
		release.complete(Unit)
		runCurrent()
		assertTrue(producer!!.isCompleted)
		assertEquals(1, stopped)
	}

	@Test
	fun `container pause gates every STARTED child and child callbacks cannot reactivate it`() = runTest {
		val container = FavouritesListWorkOwner().apply { onStart(); onResume() }
		val selected = FavouritesListWorkOwner().apply { onStart(); onResume() }
		val neighbour = FavouritesListWorkOwner().apply { onStart() }
		val notStarted = FavouritesListWorkOwner()
		val owners = listOf(selected, neighbour, notStarted)
		val works = owners.map { FavouritesListWorkScope() }
		val databases = owners.map { MutableStateFlow(1) }
		val producers = arrayOfNulls<Job>(owners.size)
		works.forEachIndexed { index, work ->
			backgroundScope.launch {
				work.whileActive(databases[index].onEach { producers[index] = currentCoroutineContext()[Job] }).collect()
			}
		}
		fun updateChildren(hidden: Boolean = false) {
			val screenActive = container.isActive(hasView = true, hidden = hidden)
			owners.forEachIndexed { index, owner ->
				works[index].setActive(owner.isActive(hasView = true, hidden = false, containerActive = screenActive))
			}
		}
		updateChildren()
		runCurrent()
		assertEquals(listOf(1, 1, 0), databases.map { it.subscriptionCount.value })
		container.onPause()
		updateChildren()
		assertTrue(producers[0]!!.isCancelled)
		assertTrue(producers[1]!!.isCancelled)
		selected.onResume() // A child callback cannot override the paused outer screen.
		updateChildren()
		runCurrent()
		assertEquals(listOf(0, 0, 0), databases.map { it.subscriptionCount.value })
		container.onResume()
		updateChildren()
		runCurrent()
		assertEquals(listOf(1, 1, 0), databases.map { it.subscriptionCount.value })
		updateChildren(hidden = true) // Browse's hidden-tab gate remains authoritative.
		runCurrent()
		assertEquals(listOf(0, 0, 0), databases.map { it.subscriptionCount.value })
	}

	@Test
	fun `standalone pause and rapid return resume exactly one producer without resetting content`() = runTest {
		val owner = FavouritesListWorkOwner().apply { onStart(); onResume() }
		val work = FavouritesListWorkScope()
		var started = 0
		var running = 0
		var peak = 0
		var producer: Job? = null
		val database = flow {
			producer = currentCoroutineContext()[Job]
			started++
			running++
			peak = maxOf(peak, running)
			try {
				emit(listOf(7, 8))
				awaitCancellation()
			} finally { running-- }
		}
		val content = work.whileActive(database).stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
		work.setActive(owner.isActive(hasView = true, hidden = false))
		runCurrent()
		val retained = content.value
		owner.onPause()
		work.setActive(owner.isActive(hasView = true, hidden = false))
		assertTrue(producer!!.isCancelled)
		assertTrue(content.value === retained)
		owner.onResume()
		work.setActive(owner.isActive(hasView = true, hidden = false))
		work.setActive(owner.isActive(hasView = true, hidden = false))
		runCurrent()
		assertEquals(2, started)
		assertEquals(1, running)
		assertEquals(1, peak)
		assertTrue(content.value === retained)
	}

	@Test
	fun `inactive or destroyed views stay ineligible despite container resume`() = runTest {
		val child = FavouritesListWorkOwner().apply { onStart() }
		assertTrue(child.isActive(hasView = true, hidden = false, containerActive = true))
		assertFalse(child.isActive(hasView = false, hidden = false, containerActive = true))
		assertFalse(child.isActive(hasView = true, hidden = true, containerActive = true))
		assertFalse(child.isActive(hasView = true, hidden = false, containerActive = false))
		child.onDestroyView()
		assertFalse(child.isActive(hasView = true, hidden = false, containerActive = true))
		child.onStart()
		assertFalse(child.isActive(hasView = true, hidden = false))
		child.onResume()
		assertTrue(child.isActive(hasView = true, hidden = false))
	}

	@Test
	fun `activation withdrawn before its producer is scheduled does not query the database`() = runTest {
		val work = FavouritesListWorkScope()
		var queries = 0
		backgroundScope.launch { work.whileActive(flow { queries++; emit(1) }).collect() }
		work.setActive(true)
		work.setActive(false)
		runCurrent()
		assertEquals(0, queries)
		work.setActive(true)
		runCurrent()
		assertEquals(1, queries)
	}
}
