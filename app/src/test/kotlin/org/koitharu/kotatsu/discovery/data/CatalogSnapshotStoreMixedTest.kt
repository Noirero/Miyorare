package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.discovery.domain.CatalogCapability
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogHomeRequest
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogSectionKey
import org.koitharu.kotatsu.discovery.domain.CatalogSectionType
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogDetails
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogHome
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogPage
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogProvider
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogSection
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class CatalogSnapshotStoreMixedTest {
	@get:Rule val folder = TemporaryFolder()

	@Test
	fun `mixed Home cache round trip preserves each item kind and provider type`() {
		val now = 1_800_000_000_000L
		val request = CatalogHomeRequest(ScrobblerService.MANGABAKA, CatalogFilter.ALL, false)
		val manga = mangaItem()
		val novel = novelItem()
		val home = mixedHome(now, manga, novel)
		val store = FileCatalogSnapshotStore(folder.root)
		assertEquals(true, store.write(request, home))
		val restored = requireNotNull(store.read(request, now))
		assertEquals(CatalogMediaKind.MIXED, restored.sections.single().key.kind)
		assertEquals(listOf(CatalogMediaKind.MANGA, CatalogMediaKind.NOVEL), restored.sections.single().items.map { it.key.kind })
		assertEquals(listOf("manhwa", "novel"), restored.sections.single().items.map { it.providerType })
	}

	@Test
	fun `repository accepts mixed section while still validating concrete item identities`() = runBlocking {
		val now = 1_800_000_000_000L
		val request = CatalogHomeRequest(ScrobblerService.MANGABAKA, CatalogFilter.ALL, false)
		val mixedKey = CatalogSectionKey(ScrobblerService.MANGABAKA, CatalogMediaKind.MIXED, CatalogSectionType.RISING)
		val manga = mangaItem()
		val novel = novelItem()
		val provider = object : TrackerCatalogProvider {
			override val catalogService = ScrobblerService.MANGABAKA
			override val catalogCapabilities = emptySet<CatalogCapability>()
			override suspend fun home(filter: CatalogFilter, allowAdult: Boolean) = mixedHome(now, manga, novel)
			override suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int): TrackerCatalogPage {
				assertEquals(mixedKey, key)
				return TrackerCatalogPage(listOf(manga, novel))
			}
			override suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int): TrackerCatalogPage = error("unused")
			override suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean): TrackerCatalogDetails = error("unused")
		}
		val repository = TrackerCatalogRepository(
			setOf(provider),
			FileCatalogSnapshotStore(folder.newFolder("repository-cache")),
			now = { now },
		)
		val refreshed = repository.refresh(request)
		assertEquals(CatalogMediaKind.MIXED, refreshed.sections.single().key.kind)
		assertEquals(listOf(CatalogMediaKind.MANGA, CatalogMediaKind.NOVEL), refreshed.sections.single().items.map { it.key.kind })
		val page = repository.browse(mixedKey, adult = false, page = 1)
		assertEquals(listOf(CatalogMediaKind.MANGA, CatalogMediaKind.NOVEL), page.items.map { it.key.kind })
	}

	@Test
	fun `mixed is section only and cannot become public item identity`() {
		try {
			TrackerCatalogKey(ScrobblerService.MANGABAKA, CatalogMediaKind.MIXED, "1")
			fail("MIXED must never become a public item identity")
		} catch (_: IllegalArgumentException) {
			// expected
		}
	}

	private fun mangaItem() = TrackerCatalogItem(
		TrackerCatalogKey(ScrobblerService.MANGABAKA, CatalogMediaKind.MANGA, "801"),
		"Manhwa",
		providerType = "manhwa",
	)

	private fun novelItem() = TrackerCatalogItem(
		TrackerCatalogKey(ScrobblerService.MANGABAKA, CatalogMediaKind.NOVEL, "802"),
		"Novel",
		providerType = "novel",
	)

	private fun mixedHome(now: Long, manga: TrackerCatalogItem, novel: TrackerCatalogItem) = TrackerCatalogHome(
		listOf(
			TrackerCatalogSection(
				CatalogSectionKey(ScrobblerService.MANGABAKA, CatalogMediaKind.MIXED, CatalogSectionType.RISING),
				listOf(manga, novel),
			),
		),
		now,
	)
}
