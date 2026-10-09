package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MangaUpdatesWriteGateTest {
	@Test fun `failed attempts and parallel mutations still observe five second pacing`() = runTest {
		val gate = MangaUpdatesWriteGate({ testScheduler.currentTime }, { delay(it) })
		val attempts = mutableListOf<Long>()
		try { gate.execute { attempts += testScheduler.currentTime; throw IOException("fixture failure") } } catch (_: IOException) { }
		val next = async { gate.execute { attempts += testScheduler.currentTime } }
		val third = async { gate.execute { attempts += testScheduler.currentTime } }
		advanceUntilIdle(); next.await(); third.await()
		assertEquals(listOf(0L, 5000L, 10000L), attempts)
	}

	@Test fun `cancellation during pacing does not start an outbound write`() = runTest {
		val gate = MangaUpdatesWriteGate({ testScheduler.currentTime }, { delay(it) })
		gate.execute { }
		var writes = 0
		val next = async { gate.execute { writes++ } }
		runCurrent(); next.cancel(); advanceUntilIdle()
		assertEquals(0, writes)
	}

	@Test fun `slow reads inside an operation cannot shorten the gap between actual writes`() = runTest {
		val gate = MangaUpdatesWriteGate({ testScheduler.currentTime }, { delay(it) })
		val writes = mutableListOf<Long>()
		gate.execute { delay(10000); writes += testScheduler.currentTime }
		gate.execute { writes += testScheduler.currentTime }
		assertEquals(listOf(10000L, 15000L), writes)
	}
}
