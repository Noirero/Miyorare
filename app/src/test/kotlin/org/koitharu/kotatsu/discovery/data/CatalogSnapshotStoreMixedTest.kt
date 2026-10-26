package org.koitharu.kotatsu.discovery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogHomeRequest
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogSectionKey
import org.koitharu.kotatsu.discovery.domain.CatalogSectionType
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogHome
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogSection
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class CatalogSnapshotStoreMixedTest {
	@get:Rule val folder = TemporaryFolder()

	@Test
	fun `mixed Home cache round trip preserves each item kind and provider type`() {
		val now = 1_800_000_000_000L
		val request = CatalogHomeRequest(ScrobblerService.MANGABAKA, CatalogFilter.ALL, false)
		val manga = TrackerCatalogItem(
			TrackerCatalogKey(ScrobblerService.MANGABAKA, CatalogMediaKind.MANGA, "801"),
			"Manhwa",
			providerType = "manhwa",
		)
		val novel = TrackerCatalogItem(
			TrackerCatalogKey(ScrobblerService.MANGABAKA, CatalogMediaKind.NOVEL, "802"),
			"Novel",
			providerType = "novel",
		)
		val home = TrackerCatalogHome(
			listOf(
				TrackerCatalogSection(
					CatalogSectionKey(ScrobblerService.MANGABAKA, CatalogMediaKind.MIXED, CatalogSectionType.RISING),
					listOf(manga, novel),
				),
			),
			now,
		)
		val store = FileCatalogSnapshotStore(folder.root)
		assertEquals(true, store.write(request, home))
		val restored = requireNotNull(store.read(request, now))
		assertEquals(CatalogMediaKind.MIXED, restored.sections.single().key.kind)
		assertEquals(listOf(CatalogMediaKind.MANGA, CatalogMediaKind.NOVEL), restored.sections.single().items.map { it.key.kind })
		assertEquals(listOf("manhwa", "novel"), restored.sections.single().items.map { it.providerType })
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
}
