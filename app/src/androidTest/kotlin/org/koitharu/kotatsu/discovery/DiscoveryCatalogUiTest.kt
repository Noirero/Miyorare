package org.koitharu.kotatsu.discovery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.discovery.ui.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService as Service

class DiscoveryCatalogUiTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val loader = ImageLoader.Builder(context).build()
	private val request = CatalogHomeRequest(Service.ANILIST, CatalogFilter.ALL, false)
	private val item = TrackerCatalogItem(TrackerCatalogKey(Service.ANILIST, CatalogMediaKind.MANGA, "42"), "Catalogue work", adult = false)
	private val section = CatalogSectionKey(Service.ANILIST, CatalogMediaKind.MANGA, CatalogSectionType.TRENDING)
	@After fun close() { loader.shutdown() }

	@Test fun loadingErrorRateLimitAndEmptyHaveHonestRetryStates() {
		val state = mutableStateOf(DiscoveryHubState(request))
		var retries = 0
		compose.setContent { MaterialTheme { DiscoveryHubScreen(state.value, listOf(Service.ANILIST, Service.MAL, Service.KITSU), loader, {}, {}, { retries++ }, {}, {}, {}, {}, MutableSharedFlow()) } }
		compose.onNodeWithTag("catalog_loading").assertExists()
		compose.runOnIdle { state.value = DiscoveryHubState(request, loading = false, failure = CatalogFailure(CatalogFailureKind.RATE_LIMITED, System.currentTimeMillis() + 60000)) }
		compose.onNodeWithTag("catalog_error_RATE_LIMITED").assertExists(); compose.onNodeWithTag("catalog_retry").performClick(); assertEquals(1, retries)
		compose.runOnIdle { state.value = DiscoveryHubState(request, loading = false, home = TrackerCatalogHome(emptyList())) }
		compose.onNodeWithTag("catalog_empty").assertExists(); compose.onNodeWithTag("catalog_error_RATE_LIMITED").assertDoesNotExist()
	}

	@Test fun providerSelectionKeepsKindAndDoesNotSelectFallbackOnFailure() {
		var chosen: Service? = null; var kind: CatalogFilter? = null
		compose.setContent { MaterialTheme { DiscoveryHubScreen(DiscoveryHubState(request.copy(filter = CatalogFilter.NOVEL), loading = false, failure = CatalogFailure(CatalogFailureKind.PROVIDER)), listOf(Service.ANILIST, Service.MAL, Service.KITSU), loader, { chosen = it }, { kind = it }, {}, {}, {}, {}, {}, MutableSharedFlow()) } }
		compose.onNodeWithTag("catalog_filter_NOVEL").assertIsSelected(); assertNull(chosen)
		compose.onNodeWithTag("catalog_provider_selector").performClick(); compose.onNodeWithTag("catalog_provider_KITSU").performClick()
		assertEquals(Service.KITSU, chosen); assertNull(kind)
	}

	@Test fun homeHeaderUsesCohesiveControlsAndOnlyFallsBackToLocalSearchWhenNeeded() {
		val sharedSearchAvailable = mutableStateOf(false)
		val state = DiscoveryHubState(request, home = TrackerCatalogHome(emptyList()), loading = false)
		compose.setContent {
			MaterialTheme {
				DiscoveryHubScreen(
					state, listOf(Service.ANILIST, Service.MAL, Service.KITSU), loader,
					{}, {}, {}, {}, {}, {}, {}, MutableSharedFlow(),
					showLocalSearch = !sharedSearchAvailable.value,
				)
			}
		}
		compose.onNodeWithTag("catalog_provider_selector").assertExists().assertHasClickAction()
		compose.onNodeWithTag("catalog_refresh").assertExists().assertHasClickAction()
		compose.onNodeWithTag("catalog_filter_ALL").assertIsSelected().assertHasClickAction()
		compose.onNodeWithTag("catalog_search_action").assertExists()
		compose.runOnIdle { sharedSearchAvailable.value = true }
		compose.onNodeWithTag("catalog_search_action").assertDoesNotExist()
	}

	@Test fun heroAndSeeAllCarryExactProviderSectionWithoutSourceResolution() {
		var clicked: TrackerCatalogKey? = null; var browsed: CatalogSectionKey? = null
		val home = TrackerCatalogHome(listOf(TrackerCatalogSection(section, listOf(item))))
		compose.setContent { MaterialTheme { DiscoveryHubScreen(DiscoveryHubState(request, home, loading = false), listOf(Service.ANILIST), loader, {}, {}, {}, {}, { clicked = it }, { browsed = it }, {}, MutableSharedFlow()) } }
		assertNull(clicked); compose.onNodeWithTag("catalog_spotlight").performClick(); assertEquals(item.key, clicked)
		compose.onNodeWithTag("catalog_see_all_MANGA_TRENDING").performClick(); assertEquals(section, browsed)
	}

	@Test fun partialHomeIsExplicitAndKeepsRealRowsVisibleInDarkTheme() {
		val home = TrackerCatalogHome(listOf(TrackerCatalogSection(section, listOf(item), failure = CatalogFailure(CatalogFailureKind.OFFLINE))))
		compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { DiscoveryHubScreen(DiscoveryHubState(request, home, loading = false, stale = true), listOf(Service.ANILIST), loader, {}, {}, {}, {}, {}, {}, {}, MutableSharedFlow()) } }
		compose.onNodeWithTag("catalog_status_PARTIAL").assertExists(); compose.onNodeWithTag("catalog_spotlight").assertExists()
		compose.onNodeWithTag("catalog_section_MANGA_TRENDING").assertExists()
	}

	@Test fun cachedOfflineErrorKeepsRowsAndNamesOfflineState() {
		val home = TrackerCatalogHome(listOf(TrackerCatalogSection(section, listOf(item))))
		compose.setContent {
			MaterialTheme {
				DiscoveryHubScreen(
					DiscoveryHubState(request, home, loading = false, stale = true, failure = CatalogFailure(CatalogFailureKind.OFFLINE)),
					listOf(Service.ANILIST), loader, {}, {}, {}, {}, {}, {}, {}, MutableSharedFlow(),
				)
			}
		}
		compose.onNodeWithTag("catalog_status_CACHED_ERROR").assertExists()
		compose.onNodeWithText(context.getString(R.string.hub_offline_cached)).assertExists()
		compose.onNodeWithTag("catalog_spotlight").assertExists()
	}

	@androidx.compose.runtime.Composable
	private fun screen(state: CatalogScreenState, onRead: () -> Unit = {}, onSource: (org.koitharu.kotatsu.parsers.model.Manga) -> Unit = {}, onSearch: () -> Unit = {}, onNext: () -> Unit = {}, onGlobal: () -> Unit = {}, onProvider: () -> Unit = {}) {
		TrackerCatalogScreen(state, null, loader, {}, {}, onSearch, {}, {}, onNext, {}, onProvider, onRead, {}, {}, onGlobal, onSource)
	}

	@Test fun searchAndSeeAllUseCatalogueCardsAndRealPaginationAction() {
		var search = 0; var next = 0
		compose.setContent { MaterialTheme { screen(CatalogScreenState(Service.ANILIST, CatalogScreenMode.SEARCH, CatalogFilter.ALL, "title", listOf(item), nextPage = 2), onSearch = { search++ }, onNext = { next++ }) } }
		compose.onNodeWithTag("catalog_search_submit").performClick(); assertEquals(1, search)
		compose.onNodeWithTag("catalog_card_ANILIST_MANGA_42").assertExists(); compose.onNodeWithTag("catalog_load_more").performScrollTo().performClick(); assertEquals(1, next)
	}

	@Test fun metadataDetailCtaDoesNotClaimReadableSourceBeforeResolution() {
		var pressed = false
		var provider = false
		compose.setContent { MaterialTheme { screen(CatalogScreenState(Service.ANILIST, CatalogScreenMode.DETAILS, CatalogFilter.ALL, details = TrackerCatalogDetails(item.copy(providerUrl = "https://anilist.co/manga/42"), description = "Real synopsis")), onRead = { pressed = true }, onProvider = { provider = true }) } }
		compose.onNodeWithText(context.getString(R.string.hub_source_cta)).assertExists(); assertFalse(pressed)
		compose.onNodeWithTag("catalog_read").performClick(); assertTrue(pressed)
		compose.onNodeWithTag("catalog_provider_page").performClick(); assertTrue(provider)
	}

	@Test fun evenOneSearchCandidateRequiresExplicitSelection() {
		val manga = SampleData.manga
		var selected: org.koitharu.kotatsu.parsers.model.Manga? = null
		compose.setContent { MaterialTheme { screen(CatalogScreenState(Service.ANILIST, CatalogScreenMode.DETAILS, CatalogFilter.ALL, details = TrackerCatalogDetails(item), resolution = CatalogResolutionState(visible = true, candidates = listOf(manga))), onSource = { selected = it }) } }
		compose.onNodeWithTag("catalog_source_sheet").assertExists(); assertNull(selected)
		compose.onNodeWithTag("catalog_source_${manga.source.name}_${manga.id}").performClick(); assertEquals(manga, selected)
	}

	@Test fun multipleCandidatesRemainExplicitAndEmptyResultsOfferSourceSearch() {
		val state = mutableStateOf(CatalogScreenState(Service.ANILIST, CatalogScreenMode.DETAILS, CatalogFilter.ALL, details = TrackerCatalogDetails(item), resolution = CatalogResolutionState(visible = true, candidates = listOf(SampleData.manga, SampleData.manga.copy(id = 123L, title = "Another source result")))))
		var fallback = false
		compose.setContent { MaterialTheme { screen(state.value, onGlobal = { fallback = true }) } }
		compose.onNodeWithText("Another source result").assertExists()
		compose.runOnIdle { state.value = state.value.copy(resolution = CatalogResolutionState(visible = true)) }
		compose.onNodeWithTag("catalog_source_empty").assertExists(); compose.onNodeWithTag("catalog_all_sources").assertExists()
		compose.onNodeWithTag("catalog_global_search").performClick(); assertTrue(fallback)
	}
}
