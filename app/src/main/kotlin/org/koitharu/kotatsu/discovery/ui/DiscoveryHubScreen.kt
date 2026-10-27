package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryHubScreen(
	state: DiscoveryHubState,
	services: List<ScrobblerService>,
	imageLoader: ImageLoader,
	onProvider: (ScrobblerService) -> Unit,
	onFilter: (CatalogFilter) -> Unit,
	onRefresh: () -> Unit,
	onSearch: () -> Unit,
	onItem: (TrackerCatalogKey) -> Unit,
	onSection: (CatalogSectionKey) -> Unit,
	onAttribution: () -> Unit,
	scrollRequests: Flow<Unit>,
	showLocalSearch: Boolean = true,
	account: CatalogAccountState = CatalogAccountState(state.request.service),
	knownTracking: Map<Long, CatalogTrackingEntry> = emptyMap(),
	mangaBakaPersonal: MangaBakaPersonalHomeState = MangaBakaPersonalHomeState(),
	onAccountLogin: () -> Unit = {},
	onAccountProfile: () -> Unit = {},
	onAccountMyList: () -> Unit = {},
	onAccountManage: () -> Unit = {},
	onAccountLogout: () -> Unit = {},
	onMangaBakaPersonalRetry: () -> Unit = {},
	onMangaBakaReconnect: () -> Unit = {},
	onMangaBakaLibrarySeeAll: () -> Unit = {},
	onMangaBakaExploreType: (MangaBakaExploreType) -> Unit = {},
	onMangaBakaExploreSeeAll: (MangaBakaExploreType) -> Unit = {},
) {
	val list = rememberLazyListState()
	val dataset = "${state.request.service}_${state.request.filter}_${state.request.allowAdult}"
	var previousDataset by rememberSaveable { mutableStateOf(dataset) }
	LaunchedEffect(dataset) {
		if (previousDataset != dataset) {
			list.scrollToItem(0)
			previousDataset = dataset
		}
	}
	LaunchedEffect(scrollRequests) { scrollRequests.collect { list.animateScrollToItem(0) } }

	val sections = state.home?.sections.orEmpty()
	val status = state.homeStatus()
	val spotlight = remember(sections) { selectDiscoverySpotlight(sections) }
	val cardWidth = catalogHomeCardWidth()
	// MainActivity augments the child system-bars inset by the visible floating-nav overlap when the
	// bar is pinned. Consume that propagated inset instead of guessing a device-specific nav height.
	val bottomContentPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 16.dp

	PullToRefreshBox(
		isRefreshing = state.refreshing,
		onRefresh = onRefresh,
		modifier = Modifier.fillMaxSize().testTag("discovery_hub"),
	) {
		LazyColumn(
			state = list,
			modifier = Modifier.testTag("catalog_home_list"),
			contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomContentPadding),
			verticalArrangement = Arrangement.spacedBy(18.dp),
		) {
			item(key = "header") {
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					CatalogHomeHeader(
						service = state.request.service,
						services = services,
						refreshing = state.refreshing,
						refreshEnabled = !state.loading && !state.refreshing,
						showLocalSearch = showLocalSearch,
						onProvider = onProvider,
						onRefresh = onRefresh,
						onSearch = onSearch,
					)
					CatalogAccountBar(
						account = account,
						imageLoader = imageLoader,
						onLogin = onAccountLogin,
						onProfile = onAccountProfile,
						onMyList = onAccountMyList,
						onManage = onAccountManage,
						onLogout = onAccountLogout,
					)
					CatalogFilterRow(state.request.filter, onFilter)
				}
			}

			if (status == DiscoveryHomeStatus.CACHED_REFRESHING || status == DiscoveryHomeStatus.CACHED_ERROR || status == DiscoveryHomeStatus.PARTIAL) {
				item(key = "status") {
					CatalogStatusBanner(status, state.failure, state.request.service, onRefresh)
				}
			}

			if (state.request.service == ScrobblerService.MANGABAKA && account.authorized) {
				item(key = "mangabaka_personal") {
					MangaBakaPersonalHomeContent(
						state = mangaBakaPersonal,
						knownTracking = knownTracking,
						imageLoader = imageLoader,
						onItem = onItem,
						onRetry = onMangaBakaPersonalRetry,
						onReconnect = onMangaBakaReconnect,
						onLibrarySeeAll = onMangaBakaLibrarySeeAll,
					)
				}
			}

			if (status == DiscoveryHomeStatus.FULL_ERROR) {
				state.failure?.let { error -> item(key = "error") { CatalogFeedback(error, state.request.service, onRefresh) } }
			}
			if (status == DiscoveryHomeStatus.LOADING) item(key = "loading") { CatalogHomeLoading() }

			if (spotlight != null && status != DiscoveryHomeStatus.LOADING && status != DiscoveryHomeStatus.FULL_ERROR) {
				item(key = "spotlight") {
					Box {
						CatalogSpotlight(spotlight, imageLoader, onClick = { onItem(spotlight.item.key) })
						spotlight.item.key.remoteId.toLongOrNull()?.let { knownTracking[it] }?.let {
							CatalogTrackingBadge(it, Modifier.align(Alignment.TopEnd).padding(12.dp))
						}
					}
				}
			}

			items(sections, key = { "${it.key.kind}_${it.key.type}" }) { section ->
				val visibleItems = visibleDiscoveryRailItems(section, spotlight)
				Column(
					verticalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier.testTag("catalog_section_${section.key.kind}_${section.key.type}"),
				) {
					CatalogSectionHeader(section.key) { onSection(section.key) }
					if (section.failure != null) CatalogSectionFailure(onRefresh)
					if (visibleItems.isNotEmpty()) {
						LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
							itemsIndexed(visibleItems, key = { _, item -> item.key.stableId }) { _, item ->
								CatalogTrackedCard(
									item = item,
									imageLoader = imageLoader,
									tracking = item.key.remoteId.toLongOrNull()?.let(knownTracking::get),
									onClick = { onItem(item.key) },
									modifier = Modifier.width(cardWidth),
									ordinal = discoverySectionOrdinal(section, item),
								)
							}
						}
					} else if (section.failure == null) {
						Text(stringResource(R.string.hub_empty), style = MaterialTheme.typography.bodySmall)
					}
				}
			}

			if (state.request.service == ScrobblerService.MANGABAKA) {
				item(key = "mangabaka_explore") {
					MangaBakaExploreByType(
						state = mangaBakaPersonal,
						filter = state.request.filter,
						knownTracking = knownTracking,
						imageLoader = imageLoader,
						onItem = onItem,
						onExploreType = onMangaBakaExploreType,
						onSeeAll = onMangaBakaExploreSeeAll,
					)
				}
			}

			if (status == DiscoveryHomeStatus.EMPTY) {
				item(key = "empty") {
					Text(stringResource(R.string.hub_empty), modifier = Modifier.padding(24.dp).testTag("catalog_empty"))
				}
			}

			item(key = "footer") {
				TextButton(onClick = onAttribution, modifier = Modifier.heightIn(min = 48.dp)) {
					Text(stringResource(R.string.hub_attribution, catalogProviderName(state.request.service)))
				}
			}
		}
	}
}
