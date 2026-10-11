package org.koitharu.kotatsu.core.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesWorkloadGateTest {
	@Test
	fun `background stops producer despite retained consumers and resumes latest state without reset`() = runTest {
		val gate = FavouritesWorkloadGate()
		gate.register(1, "NORMAL", 0)
		val upstream = MutableStateFlow(listOf(1, 2))
		gate.visibility(10, true, "visible")
		val content = gate.gate(1, upstream).stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
		backgroundScope.launch { content.collect() }
		runCurrent()
		assertEquals(1, upstream.subscriptionCount.value)
		assertEquals(listOf(1, 2), content.value)
		gate.visibility(10, false, "hidden")
		runCurrent()
		assertEquals(0, upstream.subscriptionCount.value)
		assertTrue(gate.snapshot()["pausedConfirmed"] as Boolean)
		upstream.value = listOf(3, 4, 5)
		runCurrent()
		assertEquals(listOf(1, 2), content.value)
		gate.visibility(10, true, "back")
		runCurrent()
		assertEquals(1, upstream.subscriptionCount.value)
		assertEquals(listOf(3, 4, 5), content.value)
		assertFalse(gate.snapshot()["pausedConfirmed"] as Boolean)
	}

	@Test
	fun `pause is not acknowledged while in-flight producer is still draining`() = runTest {
		val gate = FavouritesWorkloadGate()
		gate.register(1, "NORMAL", 0)
		val release = CompletableDeferred<Unit>()
		val source = flow<Int> {
			try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
		}
		gate.visibility(10, true, "visible")
		backgroundScope.launch { gate.gate(1, source).collect() }
		runCurrent()
		gate.visibility(10, false, "hidden")
		runCurrent()
		assertFalse(gate.snapshot()["pausedConfirmed"] as Boolean)
		release.complete(Unit)
		runCurrent()
		assertTrue(gate.snapshot()["pausedConfirmed"] as Boolean)
	}

	@Test
	fun `pause also waits for mapper jobs outside producer scope including replaced cancelled handles`() = runTest {
		val gate = FavouritesWorkloadGate()
		gate.register(1, "NORMAL", 0)
		val release = CompletableDeferred<Unit>()
		val oldJob = backgroundScope.launch {
			try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
		}
		gate.trackAuxiliary(1, oldJob)
		runCurrent()
		oldJob.cancel()
		gate.visibility(10, true, "visible")
		backgroundScope.launch { gate.gate(1, MutableStateFlow(1)).collect() }
		runCurrent()
		gate.visibility(10, false, "hidden")
		runCurrent()
		assertFalse(gate.snapshot()["pausedConfirmed"] as Boolean)
		release.complete(Unit)
		runCurrent()
		assertTrue(oldJob.isCompleted)
		assertTrue(gate.snapshot()["pausedConfirmed"] as Boolean)
	}

	@Test
	fun `unregistered or never collected workload cannot be claimed paused`() = runTest {
		val gate = FavouritesWorkloadGate()
		assertFalse(gate.snapshot()["pausedConfirmed"] as Boolean)
		gate.register(1, "NORMAL", 0)
		assertFalse(gate.snapshot()["pausedConfirmed"] as Boolean)
		backgroundScope.launch { gate.gate(1, MutableStateFlow(1)).collect() }
		runCurrent()
		assertTrue(gate.snapshot()["pausedConfirmed"] as Boolean)
		gate.unregister(1)
		assertFalse(gate.snapshot()["pausedConfirmed"] as Boolean)
	}

	@Test
	fun `one background container does not pause a second visible library container`() = runTest {
		val gate = FavouritesWorkloadGate()
		gate.register(1, "NORMAL", 0)
		gate.visibility(10, true, "main")
		gate.visibility(20, true, "private")
		val source = MutableStateFlow(1)
		backgroundScope.launch { gate.gate(1, source).collect() }
		runCurrent()
		gate.visibility(10, false, "main hidden")
		runCurrent()
		assertEquals(1, source.subscriptionCount.value)
		gate.removeContainer(20)
		runCurrent()
		assertEquals(0, source.subscriptionCount.value)
		assertTrue(gate.snapshot()["pausedConfirmed"] as Boolean)
	}
}
