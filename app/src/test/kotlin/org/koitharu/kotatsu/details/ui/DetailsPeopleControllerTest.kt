package org.koitharu.kotatsu.details.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.TrackerDetailsProvider
import org.koitharu.kotatsu.scrobbling.common.domain.readAssociatedTrackerDetails
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException

class DetailsPeopleControllerTest {
	private val service = ScrobblerService.ANILIST
	private val person = TrackerPerson("1", "Person", null, listOf("MAIN"))
	private val policy = TrackerDetailsReadPolicy(true, false, false, false)
	private val requested = mapOf(TrackerContent.CHARACTERS to TrackerPage(), TrackerContent.STAFF to TrackerPage())

	@Test fun `no association and unsupported provider do not request remote metadata`() = runTest {
		var calls = 0
		val controller = DetailsPeopleController(this, { _, _ -> calls++; emptyList() }, { true })
		controller.updateContext(key().copy(associations = emptySet(), services = emptySet()))
		controller.setActive(true); controller.request(); advanceUntilIdle()
		assertEquals(0, calls)
		assertTrue(controller.state.value.providers.isEmpty())
		assertFalse(controller.state.value.isLoading)
	}

	@Test fun `only demand in an active screen starts a read and repeated demand coalesces`() = runTest {
		val gate = CompletableDeferred<Unit>()
		var calls = 0
		val controller = DetailsPeopleController(this, { _, s -> calls++; gate.await(); listOf(result(s)) }, { true })
		controller.updateContext(key()); advanceUntilIdle()
		assertEquals(0, calls)
		controller.request(); runCurrent(); assertEquals(0, calls)
		controller.setActive(true); runCurrent(); assertEquals(1, calls)
		repeat(5) { controller.request() }; runCurrent(); assertEquals(1, calls)
		gate.complete(Unit); advanceUntilIdle()
		controller.request(); controller.setActive(false); controller.setActive(true); advanceUntilIdle()
		assertEquals(1, calls)
	}

	@Test fun `initial demand survives the first asynchronous mapping snapshot`() = runTest {
		var calls = 0
		val controller = DetailsPeopleController(this, { _, s -> calls++; listOf(result(s)) }, { true })
		controller.setActive(true); controller.request(); controller.updateContext(key()); advanceUntilIdle()
		assertEquals(1, calls)
	}

	@Test fun `staff only provider preserves Unsupported characters without fabricating them`() = runTest {
		val fake = FakeProvider(ScrobblerService.MAL).apply { detailsCapabilities = setOf(TrackerContent.STAFF) }
		val controller = using(fake)
		controller.updateContext(key(ScrobblerService.MAL)); activate(controller); advanceUntilIdle()
		val data = controller.state.value.providers.single()
		assertSame(TrackerResult.Unsupported, data.characters)
		assertEquals(listOf(person), data.staff.peopleItems())
		assertSame(TrackerResult.NotRequested, data.recommendations)
		assertEquals(listOf(TrackerContent.STAFF), fake.calls)
	}

	@Test fun `character and staff success are independent from recommendation capability`() = runTest {
		val fake = FakeProvider(service)
		val controller = using(fake)
		controller.updateContext(key()); activate(controller); advanceUntilIdle()
		val data = controller.state.value.providers.single()
		assertEquals(listOf(person), data.characters.peopleItems())
		assertEquals(listOf(person), data.staff.peopleItems())
		assertSame(TrackerResult.NotRequested, data.recommendations)
		assertEquals(listOf(TrackerContent.CHARACTERS, TrackerContent.STAFF), fake.calls)
	}

	@Test fun `Empty and Partial retain B1 meanings and available data`() = runTest {
		val fake = FakeProvider(service).apply {
			characters = TrackerResult.Empty()
			staff = TrackerResult.Partial(listOf(person), setOf(TrackerPartialReason.MISSING_RECORDS))
		}
		val controller = using(fake)
		controller.updateContext(key()); activate(controller); advanceUntilIdle()
		val data = controller.state.value.providers.single()
		assertTrue(data.characters is TrackerResult.Empty)
		assertTrue(data.staff is TrackerResult.Partial)
		assertEquals(listOf(person), data.staff.peopleItems())
		assertFalse(controller.state.value.isUnavailable)
	}

	@Test fun `provider failure does not discard other provider and retry is provider scoped`() = runTest {
		val counts = mutableMapOf<ScrobblerService, Int>()
		val controller = DetailsPeopleController(this, { _, s ->
			counts[s] = counts.getOrDefault(s, 0) + 1
			listOf(if (s == ScrobblerService.SHIKIMORI && counts[s] == 1) result(s).copy(
				characters = TrackerResult.Error(IOException("offline")),
			) else result(s))
		}, { true })
		controller.updateContext(key().copy(services = setOf(service, ScrobblerService.SHIKIMORI)))
		activate(controller); advanceUntilIdle()
		assertEquals(listOf(person), controller.state.value.providers.first { it.service == service }.characters.peopleItems())
		assertTrue(controller.state.value.providers.first { it.service == ScrobblerService.SHIKIMORI }.characters is TrackerResult.Error)
		controller.retry(ScrobblerService.SHIKIMORI); advanceUntilIdle()
		assertEquals(1, counts[service]); assertEquals(2, counts[ScrobblerService.SHIKIMORI])
		assertEquals(2, controller.state.value.providers.size)
	}

	@Test fun `equal numeric target and person ids from different providers stay distinct`() = runTest {
		val controller = DetailsPeopleController(this, { _, s -> listOf(result(s)) }, { true })
		controller.updateContext(key().copy(services = setOf(service, ScrobblerService.KITSU)))
		activate(controller); advanceUntilIdle()
		val data = controller.state.value.providers
		assertEquals(2, data.size)
		assertNotEquals(data[0].target?.identity, data[1].target?.identity)
		assertEquals("1", data[0].characters.peopleItems().single().id)
		assertEquals("1", data[1].characters.peopleItems().single().id)
	}

	@Test fun `manga and source switch cancel and reject even noncooperative old completion`() = runTest {
		for (newKey in listOf(key().copy(mangaId = 101), key().copy(source = "another"), key().copy(url = "other"))) {
			staleCompletionIsRejected(newKey)
		}
	}

	@Test fun `association generation rejects old completion`() = runTest {
		staleCompletionIsRejected(key().copy(associations = setOf(PeopleAssociation(service.id, 2, 99))))
	}

	@Test fun `logout and account replacement invalidate old session generation`() = runTest {
		staleCompletionIsRejected(key().copy(sessions = mapOf(service to 2L)))
		val controller = DetailsPeopleController(this, { _, s -> listOf(result(s)) }, { true })
		controller.updateContext(key()); activate(controller); advanceUntilIdle()
		controller.updateContext(key().copy(sessions = mapOf(service to 3L), services = emptySet()))
		assertTrue(controller.state.value.providers.isEmpty())
	}

	@Test fun `incognito private and on device contexts suppress reads`() = runTest {
		var calls = 0
		val controller = DetailsPeopleController(this, { _, _ -> calls++; emptyList() }, { true })
		for (denied in listOf(policy.copy(incognito = true), policy.copy(privateOnly = true), policy.copy(onDevice = true))) {
			controller.updateContext(key().copy(policy = denied)); activate(controller); advanceUntilIdle()
		}
		assertEquals(0, calls)
	}

	@Test fun `in flight result cannot survive privacy transition`() = runTest {
		val gate = CompletableDeferred<Unit>()
		val controller = DetailsPeopleController(this, { _, s -> withContext(NonCancellable) { gate.await() }; listOf(result(s)) }, { true })
		controller.updateContext(key()); activate(controller); runCurrent()
		controller.updateContext(key().copy(policy = policy.copy(incognito = true)))
		gate.complete(Unit); advanceUntilIdle()
		assertTrue(controller.state.value.providers.isEmpty())
		assertFalse(controller.state.value.isLoading)
		assertFalse(controller.state.value.isUnavailable)
	}

	@Test fun `authority recheck rejects response before queued observation arrives`() = runTest {
		var current = true
		val controller = DetailsPeopleController(this, { _, s -> current = false; listOf(result(s)) }, { current })
		controller.updateContext(key()); activate(controller); advanceUntilIdle()
		assertTrue(controller.state.value.providers.isEmpty())
		assertFalse(controller.state.value.isUnavailable)
	}

	@Test fun `pause cancels flight and resume reloads only unfinished providers`() = runTest {
		val gate = CompletableDeferred<Unit>()
		val calls = mutableListOf<ScrobblerService>()
		val controller = DetailsPeopleController(this, { _, s ->
			calls += s
			if (s == service) gate.await()
			listOf(result(s))
		}, { true })
		controller.updateContext(key().copy(services = setOf(ScrobblerService.SHIKIMORI, service)))
		activate(controller); runCurrent()
		assertEquals(1, controller.state.value.providers.size)
		controller.setActive(false); runCurrent()
		assertFalse(controller.state.value.isLoading)
		gate.complete(Unit); controller.setActive(true); advanceUntilIdle()
		assertEquals(1, calls.count { it == ScrobblerService.SHIKIMORI })
		assertEquals(2, calls.count { it == service })
		assertEquals(2, controller.state.value.providers.size)
	}

	@Test fun `cancellation is never displayed as Error`() = runTest {
		val controller = DetailsPeopleController(this, { _, _ -> throw CancellationException() }, { true })
		controller.updateContext(key()); activate(controller); advanceUntilIdle()
		assertFalse(controller.state.value.isUnavailable)
		assertFalse(controller.state.value.isLoading)
		assertTrue(controller.state.value.providers.isEmpty())
	}

	@Test fun `supplemental boundary failure is represented locally without escaping the flight`() = runTest {
		val controller = DetailsPeopleController(this, { _, _ -> throw IOException("DB read unavailable") }, { true })
		controller.updateContext(key()); activate(controller); advanceUntilIdle()
		assertTrue(controller.state.value.isUnavailable)
		assertFalse(controller.state.value.isLoading)
	}

	@Test fun `refresh replaces supplemental snapshot without duplicate flights`() = runTest {
		var calls = 0
		val controller = DetailsPeopleController(this, { _, s -> calls++; listOf(result(s)) }, { true })
		controller.updateContext(key()); activate(controller); advanceUntilIdle()
		controller.refresh(); controller.request(); advanceUntilIdle()
		assertEquals(2, calls)
	}

	private suspend fun TestScope.staleCompletionIsRejected(newKey: DetailsPeopleContext) {
		val old = key()
		val gate = CompletableDeferred<Unit>()
		val controller = DetailsPeopleController(this, { k, s ->
			if (k == old) withContext(NonCancellable) { gate.await() }
			listOf(result(s).copy(characters = TrackerResult.Success(listOf(person.copy(name = if (k == old) "OLD" else "NEW")))))
		}, { true })
		controller.updateContext(old); activate(controller); runCurrent()
		controller.updateContext(newKey); controller.request(); runCurrent()
		gate.complete(Unit); advanceUntilIdle()
		assertEquals("NEW", controller.state.value.providers.single().characters.peopleItems().single().name)
	}

	private fun key(s: ScrobblerService = service) = DetailsPeopleContext(
		100, "source", "https://source.invalid/manga", setOf(PeopleAssociation(s.id, 1, 42)),
		mapOf(s to 1L), setOf(s), policy,
	)
	private fun result(s: ScrobblerService) = AssociatedTrackerDetails(
		s, TrackerTarget(s, "42"), TrackerDetailsReadState.LOADED,
		TrackerResult.Success(listOf(person)), TrackerResult.Success(listOf(person)),
	)
	private fun activate(controller: DetailsPeopleController) { controller.setActive(true); controller.request() }
	private fun TestScope.using(fake: FakeProvider) = DetailsPeopleController(
		this, { _, s -> listOf(readAssociatedTrackerDetails(TrackerTarget(s, "42"), fake, requested)) }, { true },
	)
	private inner class FakeProvider(override val detailsService: ScrobblerService) : TrackerDetailsProvider {
		override var detailsCapabilities = TrackerContent.entries.toSet()
		override val isAuthorized = true
		val calls = mutableListOf<TrackerContent>()
		var characters: TrackerResult<TrackerPerson> = TrackerResult.Success(listOf(person))
		var staff: TrackerResult<TrackerPerson> = TrackerResult.Success(listOf(person))
		override suspend fun loadCharacters(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> {
			calls += TrackerContent.CHARACTERS; return characters
		}
		override suspend fun loadStaff(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> {
			calls += TrackerContent.STAFF; return staff
		}
	}
}
