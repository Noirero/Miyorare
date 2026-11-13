package org.koitharu.kotatsu.discovery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.discovery.ui.*
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
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
		TrackerCatalogScreen(state, null, loader, {}, {}, onSearch, {}, {}, onNext, {}, onProvider, onRead, { _, _ -> }, {}, onGlobal, onSource)
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
		compose.runOnIdle { state.value = state.value.copy(resolution = CatalogResolutionState(visible = true, canExpand = true)) }
		compose.onNodeWithTag("catalog_source_empty").assertExists(); compose.onNodeWithTag("catalog_all_sources").assertExists()
		compose.runOnIdle { state.value = state.value.copy(resolution = CatalogResolutionState(visible = true, canExpand = false)) }
		compose.onNodeWithTag("catalog_all_sources").assertDoesNotExist()
		compose.onNodeWithTag("catalog_global_search").performClick(); assertTrue(fallback)
	}

	@Test fun readingSourcePickerSelectsOneScopeWithoutOpeningManga() {
		val descriptor = TsukiSourceDescriptor("fixture-source", "Same catalogue", "en", "MANGA")
		val pinned = readingSourceFixture(TsukiPluginProvider.MIYORARE, descriptor)
		val source = readingSourceFixture(TsukiPluginProvider.GEKKOUSHI, descriptor)
		val picker = mutableStateOf(CatalogSourcePickerState(
			visible = true, sources = listOf(CatalogReadingSource(pinned, pinned = true), CatalogReadingSource(source, pinned = false)),
		))
		val resolution = mutableStateOf(CatalogResolutionState(visible = true))
		var selected: CatalogSearchScope? = null
		var opened = false
		compose.setContent {
			MaterialTheme {
				CatalogReadingSourceSheet(
					resolution = resolution.value,
					picker = picker.value,
					imageLoader = loader,
					onScope = { scope, label ->
						selected = scope
						resolution.value = resolution.value.copy(scope = scope, sourceLabel = label)
						picker.value = CatalogSourcePickerState()
					},
					onOpenPicker = {},
					onClosePicker = {},
					onDismiss = {},
					onRetry = {},
					onGlobalSearch = {},
					onSource = { opened = true },
				)
			}
		}
		compose.onNodeWithTag("catalog_scope_source_query").performScrollTo().performTextInput("no-such-source")
		compose.onNodeWithTag("catalog_scope_source_query").performTextClearance()
		val list = compose.onNodeWithTag("catalog_scope_picker")
		list.performScrollToKey(pinned.name)
		compose.onNodeWithText(context.getString(R.string.hub_scope_pinned_section, 1)).assertExists()
		val pinnedRow = hasTestTag("catalog_scope_option_${pinned.name}")
		compose.onNode(
			hasText(context.getString(R.string.explore_source_origin_miyorare_pack)) and hasAnyAncestor(pinnedRow),
			useUnmergedTree = true,
		).assertIsDisplayed()
		list.performScrollToKey(source.name)
		compose.onNodeWithText(context.getString(R.string.hub_scope_other_section, 1)).assertExists()
		val selectedRow = hasTestTag("catalog_scope_option_${source.name}")
		compose.onNode(
			hasText(context.getString(R.string.explore_source_origin_gekkoushi)) and hasAnyAncestor(selectedRow),
			useUnmergedTree = true,
		).assertIsDisplayed()
		compose.onNode(selectedRow).performClick()
		compose.onNode(
			hasText(context.getString(
				R.string.hub_scope_source_with_origin,
				descriptor.title,
				context.getString(R.string.explore_source_origin_gekkoushi),
			)) and hasAnyAncestor(hasTestTag("catalog_source_scope")),
			useUnmergedTree = true,
		).assertIsDisplayed()
		compose.runOnIdle {
			assertEquals(CatalogSearchScope.Single(source.name), selected)
			assertFalse(opened)
		}
	}

	@Test fun storeLanguagesExpandWithoutSearchAndSelectTheExactVariant() {
		fun store(id: Long, language: String, pkg: String = "fixture.akuma") =
			MihonMangaSource(object : CatalogueSource {
				override val id = id
				override val name = "Akuma"
				override val lang = language
			}, pkg)
		val all = store(1001, "all")
		val english = store(1002, "en")
		val indonesian = store(1003, "id")
		val otherPackage = store(1004, "en", "other.akuma")
		val sources = listOf(all, english, indonesian, otherPackage).map {
			CatalogReadingSource(it, pinned = it == english)
		}
		var selected: CatalogSearchScope? = null
		compose.setContent {
			MaterialTheme {
				CatalogReadingSourceSheet(
					resolution = CatalogResolutionState(visible = true),
					picker = CatalogSourcePickerState(visible = true, sources = sources),
					imageLoader = loader,
					onScope = { scope, _ -> selected = scope },
					onOpenPicker = {}, onClosePicker = {}, onDismiss = {}, onRetry = {},
					onGlobalSearch = {}, onSource = { error("A picker row must not open manga") },
				)
			}
		}
		val group = "store:fixture.akuma:Akuma"
		val list = compose.onNodeWithTag("catalog_scope_picker")
		list.performScrollToKey(group)
		compose.onNodeWithText("Akuma (3)").assertExists()
		compose.onNodeWithTag("catalog_scope_option_${indonesian.name}").assertDoesNotExist()
		list.performScrollToKey(otherPackage.name)
		compose.onNodeWithTag("catalog_scope_option_${otherPackage.name}").assertExists()
		list.performScrollToKey(group)
		compose.onNodeWithTag("catalog_scope_group_$group").performClick()
		compose.runOnIdle { assertNull(selected) }
		list.performScrollToKey(indonesian.name)
		compose.onNodeWithTag("catalog_scope_option_${indonesian.name}").performClick()
		compose.runOnIdle { assertEquals(CatalogSearchScope.Single(indonesian.name), selected) }
	}

}
