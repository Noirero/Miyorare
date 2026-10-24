package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService as Service
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerCatalogRepositoryTest {
	@get:Rule val folder = TemporaryFolder()
	private val key = CatalogHomeRequest(Service.ANILIST, CatalogFilter.MANGA, false)
	private val section = CatalogSectionKey(Service.ANILIST, CatalogMediaKind.MANGA, CatalogSectionType.TRENDING)
	private val item = TrackerCatalogItem(TrackerCatalogKey(Service.ANILIST, CatalogMediaKind.MANGA, "42"), "Last good", adult = false)
	private val now = 1_000_000_000L
	private fun home(title: String = "Last good", time: Long = now) = TrackerCatalogHome(listOf(TrackerCatalogSection(section, listOf(item.copy(title = title)))), time)
	private class Provider(override val catalogService: Service = Service.ANILIST, var read: suspend (CatalogFilter) -> TrackerCatalogHome) : TrackerCatalogProvider {
		@Volatile var calls = 0
		override val catalogCapabilities = setOf(CatalogCapability.HOME)
		override suspend fun home(filter: CatalogFilter, allowAdult: Boolean): TrackerCatalogHome { calls++; return read(filter) }
		override suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int) = TrackerCatalogPage(emptyList())
		override suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean): TrackerCatalogDetails = error("No details requested")
		override suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int) = TrackerCatalogPage(emptyList())
	}
	@Test fun `same request joins one flight until both subscribers leave`() = runTest {
		val gate = CompletableDeferred<Unit>()
		val p = Provider { gate.await(); home() }
		val repo = TrackerCatalogRepository(setOf(p), FileCatalogSnapshotStore(folder.root), backgroundScope, { now })
		val first = async { repo.refresh(key) }; val second = async { repo.refresh(key) }
		runCurrent(); awaitCondition { p.calls == 1 }; assertEquals(1, p.calls)
		first.cancelAndJoin(); assertFalse(second.isCompleted)
		gate.complete(Unit); assertEquals("Last good", second.await().sections.single().items.single().title)
	}
	@Test fun `last subscriber cancellation terminates underlying provider work`() = runTest {
		val canceled = CompletableDeferred<Unit>()
		val p = Provider { try { awaitCancellation() } finally { canceled.complete(Unit) } }
		val repo = TrackerCatalogRepository(setOf(p), FileCatalogSnapshotStore(folder.root), backgroundScope, { now })
		val flight = async { repo.refresh(key) }; runCurrent(); awaitCondition { p.calls == 1 }; flight.cancelAndJoin(); runCurrent(); awaitCondition { canceled.isCompleted }
		assertTrue(canceled.isCompleted); assertNull(repo.cached(key))
	}
	@Test fun `failed forced refresh retains last good and reports stale offline`() = runTest {
		val store = FileCatalogSnapshotStore(folder.root); store.write(key, home(time = now - CatalogCachePolicy.FRESH_MS - 1))
		val p = Provider { throw IOException("fixture offline") }
		val repo = TrackerCatalogRepository(setOf(p), store, backgroundScope, { now })
		val controller = DiscoveryHubController(repo, backgroundScope, key, { now })
		controller.load(force = true)
		// Disk reads use IO; await the production controller's terminal state.
		awaitCondition { controller.state.value.failure != null }
		assertEquals("Last good", controller.state.value.home!!.sections.single().items.single().title)
		assertEquals(CatalogFailureKind.OFFLINE, controller.state.value.failure!!.kind); assertTrue(controller.state.value.stale)
		assertEquals(now - CatalogCachePolicy.FRESH_MS - 1, store.read(key, now)!!.savedAt)
	}
	@Test fun `fresh cache skips automatic network while explicit refresh retains rows until success`() = runTest {
		val store = FileCatalogSnapshotStore(folder.root); store.write(key, home())
		val gate = CompletableDeferred<Unit>()
		val p = Provider { gate.await(); home("Replacement") }
		val repo = TrackerCatalogRepository(setOf(p), store, backgroundScope, { now })
		val controller = DiscoveryHubController(repo, backgroundScope, key, { now })
		controller.load(); awaitCondition { controller.state.value.home != null && !controller.state.value.refreshing }
		assertEquals(0, p.calls); assertFalse(controller.state.value.loading)
		controller.load(force = true); awaitCondition { p.calls == 1 }
		assertEquals("Last good", controller.state.value.home!!.sections.single().items.single().title); assertTrue(controller.state.value.refreshing)
		gate.complete(Unit); awaitCondition { !controller.state.value.refreshing }
		assertEquals("Replacement", controller.state.value.home!!.sections.single().items.single().title)
	}
	@Test fun `offline first load has no cached rows and no implicit alternative provider`() = runTest {
		val p = Provider { throw IOException("fixture offline") }
		val controller = DiscoveryHubController(TrackerCatalogRepository(setOf(p), FileCatalogSnapshotStore(folder.root), backgroundScope), backgroundScope, key)
		controller.load(); awaitCondition { controller.state.value.failure != null }
		assertFalse(controller.state.value.loading); assertNull(controller.state.value.home); assertEquals(Service.ANILIST, controller.state.value.request.service)
		assertEquals(CatalogFailureKind.OFFLINE, controller.state.value.failure!!.kind)
	}
	@Test fun `media filter switch rejects late Manga data after Novel becomes authoritative`() = runTest {
		val gate = CompletableDeferred<Unit>()
		val p = Provider { filter ->
			if (filter == CatalogFilter.MANGA) { withContext(NonCancellable) { gate.await() }; home("Old Manga") }
			else TrackerCatalogHome(listOf(TrackerCatalogSection(section.copy(kind = CatalogMediaKind.NOVEL), listOf(item.copy(key = item.key.copy(kind = CatalogMediaKind.NOVEL), title = "Current Novel")))))
		}
		val repo = TrackerCatalogRepository(setOf(p), FileCatalogSnapshotStore(folder.root), backgroundScope, { now })
		val controller = DiscoveryHubController(repo, backgroundScope, key, { now })
		controller.load(); awaitCondition { p.calls == 1 }; controller.load(key.copy(filter = CatalogFilter.NOVEL)); gate.complete(Unit)
		awaitCondition { controller.state.value.home != null }
		assertEquals(CatalogFilter.NOVEL, controller.state.value.request.filter)
		assertEquals("Current Novel", controller.state.value.home!!.sections.single().items.single().title)
	}
	@Test fun `partial refresh keeps only failed section while replacing successful section`() = runTest {
		val store = FileCatalogSnapshotStore(folder.root); store.write(key, home())
		val other = section.copy(type = CatalogSectionType.POPULAR)
		val p = Provider { TrackerCatalogHome(listOf(TrackerCatalogSection(section, emptyList(), failure = CatalogFailure(CatalogFailureKind.PROVIDER)), TrackerCatalogSection(other, listOf(item.copy(title = "Fresh"))))) }
		val repo = TrackerCatalogRepository(setOf(p), store, backgroundScope, { now })
		val result = repo.refresh(key)
		assertEquals("Last good", result.sections[0].items.single().title); assertEquals("Fresh", result.sections[1].items.single().title)
		assertFalse(CatalogCachePolicy.isFresh(result.savedAt, now))
	}
	@Test fun `genuine successful empty refresh can replace old rows`() = runTest {
		val store = FileCatalogSnapshotStore(folder.root); store.write(key, home())
		val repo = TrackerCatalogRepository(setOf(Provider { TrackerCatalogHome(listOf(TrackerCatalogSection(section, emptyList()))) }), store, backgroundScope, { now })
		assertTrue(repo.refresh(key).sections.single().items.isEmpty()); assertTrue(repo.cached(key)!!.sections.single().items.isEmpty())
	}
	@Test fun `provider switch rejects late noncooperative response and does not silently fallback`() = runTest {
		val gate = CompletableDeferred<Unit>()
		val old = Provider { withContext(NonCancellable) { gate.await() }; home("Old") }
		val next = Provider(Service.KITSU) { throw CatalogException(CatalogFailure(CatalogFailureKind.RATE_LIMITED, now + 20000)) }
		val repo = TrackerCatalogRepository(setOf(old, next), FileCatalogSnapshotStore(folder.root), backgroundScope, { now })
		val controller = DiscoveryHubController(repo, backgroundScope, key, { now })
		controller.load(); runCurrent()
		// Wait for disk lookup before switching; no sleep on the test scheduler's virtual clock.
		awaitCondition { old.calls == 1 }
		controller.load(key.copy(service = Service.KITSU)); runCurrent(); gate.complete(Unit)
		awaitCondition { controller.state.value.failure != null }
		assertEquals(Service.KITSU, controller.state.value.request.service); assertNull(controller.state.value.home)
		assertEquals(CatalogFailureKind.RATE_LIMITED, controller.state.value.failure!!.kind)
	}
	@Test fun `snapshot round trip is disposable and isolated by provider filter and adult policy`() {
		val store = FileCatalogSnapshotStore(folder.root)
		assertTrue(store.write(key, home())); assertEquals(home(), store.read(key, now))
		assertNull(store.read(key.copy(service = Service.MAL), now)); assertNull(store.read(key.copy(filter = CatalogFilter.ALL), now)); assertNull(store.read(key.copy(allowAdult = true), now))
		assertNull(store.read(key, now + CatalogCachePolicy.MAX_STALE_MS + 1))
	}
	@Test fun `corrupt and future snapshots cannot prevent fresh load`() {
		val store = FileCatalogSnapshotStore(folder.root); store.write(key, home())
		folder.root.listFiles()!!.single().writeText("{broken")
		assertNull(store.read(key, now)); assertTrue(folder.root.listFiles()!!.isEmpty())
		store.write(key, home(time = now + 1000)); assertNull(store.read(key, now))
	}
	private suspend fun awaitCondition(check: () -> Boolean) = withContext(Dispatchers.Default) { withTimeout(5000) { while (!check()) delay(5) } }
	@Test fun `cache pruning remains bounded`() {
		val store = FileCatalogSnapshotStore(folder.root)
		for (service in listOf(Service.ANILIST, Service.MAL, Service.KITSU, Service.SHIKIMORI)) for (filter in CatalogFilter.entries) for (adult in listOf(false, true)) {
			val request = CatalogHomeRequest(service, filter, adult)
			store.write(request, TrackerCatalogHome(emptyList(), now))
		}
		assertTrue(folder.root.listFiles()!!.size <= CatalogCachePolicy.MAX_SNAPSHOTS)
	}
}
