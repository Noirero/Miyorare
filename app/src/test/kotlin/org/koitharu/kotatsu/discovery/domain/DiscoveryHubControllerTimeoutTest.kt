package org.koitharu.kotatsu.discovery.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.discovery.data.FileCatalogSnapshotStore
import org.koitharu.kotatsu.discovery.data.TrackerCatalogRepository
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class DiscoveryHubControllerTimeoutTest {
	@get:Rule val folder = TemporaryFolder()

	private class HangingMalProvider(
		private val canceled: CompletableDeferred<Unit>,
	) : TrackerCatalogProvider {
		override val catalogService = ScrobblerService.MAL
		override val catalogCapabilities = setOf(CatalogCapability.HOME)
		@Volatile var calls = 0

		override suspend fun home(filter: CatalogFilter, allowAdult: Boolean): TrackerCatalogHome {
			calls++
			try {
				awaitCancellation()
			} finally {
				canceled.complete(Unit)
			}
		}

		override suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int) = TrackerCatalogPage(emptyList())
		override suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean): TrackerCatalogDetails = error("No details requested")
		override suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int) = TrackerCatalogPage(emptyList())
	}

	@Test
	fun `MAL home refresh deadline exits loading and cancels stuck provider work`() = runBlocking {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		try {
			val canceled = CompletableDeferred<Unit>()
			val provider = HangingMalProvider(canceled)
			val repository = TrackerCatalogRepository(
				setOf(provider),
				FileCatalogSnapshotStore(folder.root),
				scope,
			)
			val request = CatalogHomeRequest(ScrobblerService.MAL, CatalogFilter.ALL, false)
			val controller = DiscoveryHubController(
				repository = repository,
				scope = scope,
				initial = request,
				refreshTimeoutMs = 50L,
			)

			controller.load()
			withTimeout(2_000) { while (provider.calls == 0) delay(5) }
			withTimeout(2_000) { while (controller.state.value.failure == null) delay(5) }

			val state = controller.state.value
			assertFalse(state.loading)
			assertFalse(state.refreshing)
			assertNull(state.home)
			assertEquals(CatalogFailureKind.PROVIDER, state.failure?.kind)
			withTimeout(2_000) { canceled.await() }
			assertTrue(canceled.isCompleted)
		} finally {
			scope.cancel()
		}
	}
}
