package org.koitharu.kotatsu.scrobbling.common.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException

class ReadTrackerDetailsUseCaseTest {
	private val target = TrackerTarget(ScrobblerService.ANILIST, "42")
	private val requested = TrackerContent.entries.associateWith { TrackerPage() }

	@Test fun `provider identities do not conflate equal numeric ids`() {
		assertNotEquals(target, TrackerTarget(ScrobblerService.MAL, "42"))
		assertEquals(target.identity, target.copy(url = "https://anilist.co/manga/42").identity)
	}

	@Test fun `association uses remote target rather than local manga or rate id`() {
		val row = association(ScrobblerService.ANILIST, targetId = 42)
		assertEquals(target, associatedTrackerTarget(ScrobblerService.ANILIST, listOf(row)))
		assertNull(associatedTrackerTarget(ScrobblerService.MAL, listOf(row)))
		assertNull(associatedTrackerTarget(ScrobblerService.ANILIST, emptyList()))
		assertNull(associatedTrackerTarget(ScrobblerService.ANILIST, listOf(row, association(ScrobblerService.ANILIST, 43))))
	}

	@Test fun `default privacy policy suppresses optional reads`() {
		assertFalse(TrackerDetailsReadPolicy().allowsNetwork)
		val allowed = TrackerDetailsReadPolicy(enabled = true, incognito = false, privateOnly = false, onDevice = false)
		assertTrue(allowed.allowsNetwork)
		assertFalse(allowed.copy(incognito = true).allowsNetwork)
		assertFalse(allowed.copy(privateOnly = true).allowsNetwork)
		assertFalse(allowed.copy(onDevice = true).allowsNetwork)
		assertFalse(allowed.copy(enabled = false).allowsNetwork)
	}

	@Test fun `requested capability failure does not discard independent sections or another provider`() = runTest {
		val provider = FakeProvider().apply { failRecommendations = IOException("offline") }
		val first = readAssociatedTrackerDetails(target, provider, requested)
		assertTrue(first.characters is TrackerResult.Success)
		assertTrue(first.staff is TrackerResult.Success)
		assertTrue(first.recommendations is TrackerResult.Error)
		val other = TrackerTarget(ScrobblerService.KITSU, "42")
		val second = readAssociatedTrackerDetails(other, FakeProvider(ScrobblerService.KITSU), requested)
		assertTrue(second.staff is TrackerResult.Success)
		assertEquals(other, second.target)
	}

	@Test fun `unsupported differs from empty and never calls unsupported loader`() = runTest {
		val provider = FakeProvider().apply { detailsCapabilities = setOf(TrackerContent.STAFF); emptyStaff = true }
		val result = readAssociatedTrackerDetails(target, provider, requested)
		assertSame(TrackerResult.Unsupported, result.characters)
		assertTrue(result.staff is TrackerResult.Empty)
		assertSame(TrackerResult.Unsupported, result.recommendations)
		assertEquals(listOf(TrackerContent.STAFF), provider.calls)
	}

	@Test fun `undemanded capabilities do not make requests`() = runTest {
		val provider = FakeProvider()
		val result = readAssociatedTrackerDetails(target, provider, mapOf(TrackerContent.CHARACTERS to TrackerPage(2)))
		assertSame(TrackerResult.NotRequested, result.staff)
		assertSame(TrackerResult.NotRequested, result.recommendations)
		assertEquals(listOf(TrackerContent.CHARACTERS), provider.calls)
		assertEquals(TrackerPage(2), provider.lastPage)
	}

	@Test fun `signed out and unsupported providers do not fetch`() = runTest {
		val provider = FakeProvider().apply { isAuthorized = false }
		assertEquals(TrackerDetailsReadState.AUTH_REQUIRED, readAssociatedTrackerDetails(target, provider, requested).state)
		assertTrue(provider.calls.isEmpty())
		assertEquals(TrackerDetailsReadState.UNSUPPORTED, readAssociatedTrackerDetails(target, null, requested).state)
	}

	@Test fun `logout during a read discards the completed optional data`() = runTest {
		val provider = FakeProvider().apply { logoutOnStaff = true }
		val result = readAssociatedTrackerDetails(target, provider, mapOf(TrackerContent.STAFF to TrackerPage()))
		assertEquals(TrackerDetailsReadState.AUTH_REQUIRED, result.state)
		assertSame(TrackerResult.NotRequested, result.staff)
	}

	@Test fun `cancellation propagates without becoming provider Error or launching more requests`() = runTest {
		val provider = FakeProvider().apply { cancelCharacters = true }
		try {
			readAssociatedTrackerDetails(target, provider, requested)
			fail("Expected cancellation")
		} catch (_: CancellationException) {
			assertEquals(listOf(TrackerContent.CHARACTERS), provider.calls)
		}
	}

	@Test fun `invalid opaque ids are not interpolated into provider requests`() {
		for (id in listOf("0", "-1", "001", "1) { Viewer { id }", "9223372036854775808")) {
			try { TrackerTarget(ScrobblerService.ANILIST, id); fail("Expected invalid identity") }
			catch (_: IllegalArgumentException) { /* expected */ }
		}
	}

	private fun association(service: ScrobblerService, targetId: Long) = ScrobblingEntity(
		scrobbler = service.id, id = 999, mangaId = 100, targetId = targetId,
		status = null, chapter = 0, comment = null, rating = 0f,
	)

	private class FakeProvider(override val detailsService: ScrobblerService = ScrobblerService.ANILIST) : TrackerDetailsProvider {
		override var detailsCapabilities = TrackerContent.entries.toSet()
		override var isAuthorized = true
		val calls = ArrayList<TrackerContent>()
		var lastPage: TrackerPage? = null
		var cancelCharacters = false
		var emptyStaff = false
		var logoutOnStaff = false
		var failRecommendations: Exception? = null
		override suspend fun loadCharacters(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> {
			calls += TrackerContent.CHARACTERS; lastPage = page
			if (cancelCharacters) throw CancellationException("screen stopped")
			return TrackerResult.Success(listOf(TrackerPerson("1", "C", null, listOf("MAIN"))))
		}
		override suspend fun loadStaff(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> {
			calls += TrackerContent.STAFF
			if (logoutOnStaff) isAuthorized = false
			return if (emptyStaff) TrackerResult.Empty() else TrackerResult.Success(listOf(TrackerPerson("1", "S", null, listOf("Art"))))
		}
		override suspend fun loadRecommendations(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerRecommendation> {
			calls += TrackerContent.RECOMMENDATIONS
			failRecommendations?.let { throw it }
			return TrackerResult.Empty()
		}
	}
}
