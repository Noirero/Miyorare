package org.koitharu.kotatsu.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.CatalogFailure
import org.koitharu.kotatsu.discovery.domain.CatalogFailureKind
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogHomeRequest
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogSectionKey
import org.koitharu.kotatsu.discovery.domain.CatalogSectionType
import org.koitharu.kotatsu.discovery.domain.DiscoveryHubState
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogHome
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogSection
import org.koitharu.kotatsu.discovery.ui.DiscoveryHomeStatus
import org.koitharu.kotatsu.discovery.ui.discoverySectionOrdinal
import org.koitharu.kotatsu.discovery.ui.homeStatus
import org.koitharu.kotatsu.discovery.ui.selectDiscoverySpotlight
import org.koitharu.kotatsu.discovery.ui.visibleDiscoveryRailItems
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class DiscoveryHomePresentationTest {
	private val request = CatalogHomeRequest(ScrobblerService.ANILIST, CatalogFilter.ALL, false)

	@Test
	fun spotlightPrefersTruthfulTrendingAndFallsBackToFirstNonEmptySection() {
		val popular = section(CatalogSectionType.POPULAR, 10, 11, 12)
		val trending = section(CatalogSectionType.TRENDING, 20, 21, 22)
		val selected = selectDiscoverySpotlight(listOf(popular, trending))
		assertEquals(CatalogSectionType.TRENDING, selected?.section?.key?.type)
		assertEquals("20", selected?.item?.key?.remoteId)

		val emptyTrending = trending.copy(items = emptyList())
		val fallback = selectDiscoverySpotlight(listOf(emptyTrending, popular))
		assertEquals(CatalogSectionType.POPULAR, fallback?.section?.key?.type)
		assertEquals("10", fallback?.item?.key?.remoteId)
	}

	@Test
	fun spotlightDedupeOnlyChangesExactHomeRailPresentation() {
		val source = section(CatalogSectionType.TRENDING, 1, 2, 3, 4)
		val other = section(CatalogSectionType.POPULAR, 5, 6, 7)
		val spotlight = selectDiscoverySpotlight(listOf(source, other))

		assertEquals(listOf("2", "3", "4"), visibleDiscoveryRailItems(source, spotlight).map { it.key.remoteId })
		assertSame(other.items, visibleDiscoveryRailItems(other, spotlight))
		assertEquals(listOf("1", "2", "3", "4"), source.items.map { it.key.remoteId })
	}

	@Test
	fun sparseSpotlightRailIsKeptUseful() {
		val sparse = section(CatalogSectionType.TRENDING, 1, 2)
		val spotlight = selectDiscoverySpotlight(listOf(sparse))
		assertSame(sparse.items, visibleDiscoveryRailItems(sparse, spotlight))
	}

	@Test
	fun rankedOrdinalUsesOriginalSectionOrderAfterDedupe() {
		val ranked = section(CatalogSectionType.TOP_RANKED, 1, 2, 3, 4)
		val spotlight = selectDiscoverySpotlight(listOf(ranked))
		val visible = visibleDiscoveryRailItems(ranked, spotlight)
		assertEquals(listOf("2", "3", "4"), visible.map { it.key.remoteId })
		assertEquals(listOf(2, 3, 4), visible.map { discoverySectionOrdinal(ranked, it) })
	}

	@Test
	fun presentationStatusDistinguishesLoadingCachePartialAndFullError() {
		val fresh = section(CatalogSectionType.TRENDING, 1, 2, 3)
		val partial = fresh.copy(failure = CatalogFailure(CatalogFailureKind.OFFLINE))
		val home = TrackerCatalogHome(listOf(fresh))
		val partialHome = TrackerCatalogHome(listOf(partial))

		assertEquals(DiscoveryHomeStatus.LOADING, DiscoveryHubState(request).homeStatus())
		assertEquals(DiscoveryHomeStatus.CACHED_REFRESHING, DiscoveryHubState(request, home, loading = false, refreshing = true, stale = true).homeStatus())
		assertEquals(DiscoveryHomeStatus.FRESH, DiscoveryHubState(request, home, loading = false, refreshing = true, stale = false).homeStatus())
		assertEquals(DiscoveryHomeStatus.CACHED_ERROR, DiscoveryHubState(request, home, loading = false, stale = true, failure = CatalogFailure(CatalogFailureKind.OFFLINE)).homeStatus())
		assertEquals(DiscoveryHomeStatus.PARTIAL, DiscoveryHubState(request, partialHome, loading = false, stale = true).homeStatus())
		assertEquals(DiscoveryHomeStatus.FULL_ERROR, DiscoveryHubState(request, loading = false, failure = CatalogFailure(CatalogFailureKind.PROVIDER)).homeStatus())
		assertEquals(DiscoveryHomeStatus.EMPTY, DiscoveryHubState(request, TrackerCatalogHome(emptyList()), loading = false).homeStatus())
		assertEquals(DiscoveryHomeStatus.FRESH, DiscoveryHubState(request, home, loading = false).homeStatus())
	}

	private fun section(type: CatalogSectionType, vararg ids: Int): TrackerCatalogSection {
		val key = CatalogSectionKey(ScrobblerService.ANILIST, CatalogMediaKind.MANGA, type)
		return TrackerCatalogSection(
			key = key,
			items = ids.map { id ->
				TrackerCatalogItem(
					key = TrackerCatalogKey(ScrobblerService.ANILIST, CatalogMediaKind.MANGA, id.toString()),
					title = "Work $id",
				)
			},
		)
	}
}
