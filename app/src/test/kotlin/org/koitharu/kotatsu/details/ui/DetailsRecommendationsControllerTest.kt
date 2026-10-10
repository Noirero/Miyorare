package org.koitharu.kotatsu.details.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.TrackerDetailsProvider
import org.koitharu.kotatsu.scrobbling.common.domain.readAssociatedTrackerDetails
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException

class DetailsRecommendationsControllerTest {
	private val services = setOf(ScrobblerService.ANILIST, ScrobblerService.MAL)
	private fun key() = DetailsPeopleContext(1, "SOURCE", "/manga/1", services.map { PeopleAssociation(it.id, 9, 42) }.toSet(), services.associateWith { 0L }, services, TrackerDetailsReadPolicy(true, false, false, false))
	private val item = TrackerRecommendation(TrackerTarget(ScrobblerService.ANILIST, "7"), "Title", null, TrackerRecommendationKind.RECOMMENDATION)

	@Test fun `one provider failure preserves another provider recommendation and provenance`() = runTest {
		val providers = services.associateWith { Fake(it) }
		providers.getValue(ScrobblerService.MAL).result = TrackerResult.Error(IOException("fixture failure"))
		val controller = DetailsPeopleController(this, { _, service -> listOf(readAssociatedTrackerDetails(TrackerTarget(service, "42"), providers.getValue(service), mapOf(TrackerContent.RECOMMENDATIONS to TrackerPage()))) }, { true })
		controller.updateContext(key()); controller.setActive(true); controller.request(); advanceUntilIdle()
		assertEquals(listOf(item), controller.state.value.providers.first().recommendations.recommendationItems())
		assertEquals(ScrobblerService.ANILIST, controller.state.value.providers.first().service)
		assertTrue(controller.state.value.providers.last().recommendations is TrackerResult.Error)
		assertEquals(1, providers.getValue(ScrobblerService.ANILIST).calls)
		controller.retry(ScrobblerService.MAL); advanceUntilIdle()
		assertEquals(1, providers.getValue(ScrobblerService.ANILIST).calls)
		assertEquals(2, providers.getValue(ScrobblerService.MAL).calls)
	}

	@Test fun `unsupported and empty recommendations neither fabricate data nor fetch people`() = runTest {
		val fake = Fake(ScrobblerService.ANILIST)
		fake.detailsCapabilities = emptySet()
		val unsupported = readAssociatedTrackerDetails(TrackerTarget(fake.detailsService, "42"), fake, mapOf(TrackerContent.RECOMMENDATIONS to TrackerPage()))
		assertSame(TrackerResult.Unsupported, unsupported.recommendations)
		assertEquals(0, fake.calls)
		fake.detailsCapabilities = setOf(TrackerContent.RECOMMENDATIONS)
		fake.result = TrackerResult.Empty()
		val empty = readAssociatedTrackerDetails(TrackerTarget(fake.detailsService, "42"), fake, mapOf(TrackerContent.RECOMMENDATIONS to TrackerPage()))
		assertTrue(empty.recommendations is TrackerResult.Empty)
		assertSame(TrackerResult.NotRequested, empty.staff)
		assertSame(TrackerResult.NotRequested, empty.characters)
	}

	@Test fun `recommendation demand coalesces and a stopped screen rejects a late result`() = runTest {
		val started = CompletableDeferred<Unit>()
		val gate = CompletableDeferred<Unit>()
		var calls = 0
		val controller = DetailsPeopleController(this, { _, service ->
			calls++; started.complete(Unit)
			withContext(NonCancellable) { gate.await() }
			listOf(AssociatedTrackerDetails(service, TrackerTarget(service, "42"), TrackerDetailsReadState.LOADED, recommendations = TrackerResult.Success(listOf(item))))
		}, { true })
		controller.updateContext(key().copy(services = setOf(ScrobblerService.ANILIST)))
		controller.setActive(true); controller.request(); started.await()
		repeat(5) { controller.request() }
		controller.setActive(false); gate.complete(Unit); advanceUntilIdle()
		assertEquals(1, calls)
		assertTrue(controller.state.value.providers.isEmpty())
	}

	@Test fun `session change clears recommendations and suppressed privacy does not load`() = runTest {
		var calls = 0
		val controller = DetailsPeopleController(this, { _, service -> calls++; listOf(AssociatedTrackerDetails(service, TrackerTarget(service, "42"), TrackerDetailsReadState.LOADED, recommendations = TrackerResult.Success(listOf(item)))) }, { true })
		controller.updateContext(key()); controller.setActive(true); controller.request(); advanceUntilIdle()
		assertEquals(2, calls)
		controller.updateContext(key().copy(sessions = services.associateWith { 1L }, policy = TrackerDetailsReadPolicy()))
		advanceUntilIdle()
		assertTrue(controller.state.value.providers.isEmpty())
		assertEquals(2, calls)
	}

	private inner class Fake(override val detailsService: ScrobblerService) : TrackerDetailsProvider {
		override val isAuthorized = true
		override val detailsSessionGeneration = MutableStateFlow(0L)
		override var detailsCapabilities = setOf(TrackerContent.RECOMMENDATIONS)
		var result: TrackerResult<TrackerRecommendation> = TrackerResult.Success(listOf(item))
		var calls = 0
		override suspend fun loadCharacters(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> = error("People must not load")
		override suspend fun loadStaff(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> = error("People must not load")
		override suspend fun loadRecommendations(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerRecommendation> { calls++; return result }
	}
}
