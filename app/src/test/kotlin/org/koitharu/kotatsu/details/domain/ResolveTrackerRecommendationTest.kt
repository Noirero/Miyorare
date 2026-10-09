package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class ResolveTrackerRecommendationTest {
	private val item = TrackerRecommendation(TrackerTarget(ScrobblerService.ANILIST, "17360452316", "https://anilist.co/manga/17360452316"), "Real title", "https://image.invalid/cover", TrackerRecommendationKind.RECOMMENDATION)

	@Test fun `one real persisted source association resolves deterministically without replacing tracker identity`() = runTest {
		var lookedUp: TrackerTarget? = null
		val result = resolveTrackerRecommendation(item, { lookedUp = it; listOf(41L) }, { it }, { true })!!
		assertEquals(item.target, lookedUp)
		assertEquals(listOf(41L), result.candidates)
		assertSame(item, result.recommendation)
		assertNotEquals(item.target.id.toLong(), result.candidates.single())
	}

	@Test fun `multiple genuine source candidates remain an explicit choice`() = runTest {
		val result = resolveTrackerRecommendation(item, { listOf(41L, 42L, 41L) }, { it }, { true })!!
		assertEquals(listOf(41L, 42L), result.candidates)
	}

	@Test fun `unmapped recommendation falls back to the real title without manufacturing a manga`() = runTest {
		val result = resolveTrackerRecommendation<Long>(item, { emptyList() }, { it }, { true })!!
		assertTrue(result.candidates.isEmpty())
		assertEquals("Real title", result.recommendation.title)
	}

	@Test fun `invalid context does not query associations`() = runTest {
		var lookups = 0
		assertNull(resolveTrackerRecommendation<Long>(item, { lookups++; emptyList() }, { it }, { false }))
		assertEquals(0, lookups)
	}

	@Test fun `account or manga change during resolution rejects the stale candidate`() = runTest {
		var current = true
		val result = resolveTrackerRecommendation(item, { current = false; listOf(41L) }, { it }, { current })
		assertNull(result)
	}

	@Test fun `cancellation does not publish navigation`() = runTest {
		val started = CompletableDeferred<Unit>()
		val gate = CompletableDeferred<List<Long>>()
		val flight = async { resolveTrackerRecommendation(item, { started.complete(Unit); gate.await() }, { it }, { true }) }
		started.await()
		flight.cancel()
		gate.complete(listOf(41L))
		assertTrue(flight.isCancelled)
	}
}
