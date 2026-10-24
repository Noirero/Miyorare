package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryHubScreen(
	state: DiscoveryHubState, services: List<ScrobblerService>, imageLoader: ImageLoader,
	onProvider: (ScrobblerService) -> Unit, onFilter: (CatalogFilter) -> Unit, onRefresh: () -> Unit,
	onSearch: () -> Unit, onItem: (TrackerCatalogKey) -> Unit, onSection: (CatalogSectionKey) -> Unit,
	onAttribution: () -> Unit, scrollRequests: Flow<Unit>,
) {
	val list = rememberLazyListState()
	var expanded by remember { mutableStateOf(false) }
	val dataset = "${state.request.service}_${state.request.filter}_${state.request.allowAdult}"
	var previousDataset by rememberSaveable { mutableStateOf(dataset) }
	LaunchedEffect(dataset) { if (previousDataset != dataset) { list.scrollToItem(0); previousDataset = dataset } }
	LaunchedEffect(scrollRequests) { scrollRequests.collect { list.animateScrollToItem(0) } }
	val sections = state.home?.sections.orEmpty()
	val spotlightSection = sections.firstOrNull { it.key.type == CatalogSectionType.TRENDING && it.items.isNotEmpty() } ?: sections.firstOrNull { it.items.isNotEmpty() }
	PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().testTag("discovery_hub")) {
		LazyColumn(state = list, modifier = Modifier.testTag("catalog_home_list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
			item(key = "header") {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(stringResource(R.string.discovery_hub), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
					Box {
						TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_provider_selector")) {
							Text(catalogProviderName(state.request.service), maxLines = 1)
							Icon(painterResource(R.drawable.ic_expand_more), stringResource(R.string.hub_provider))
						}
						DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
							services.forEach { service -> DropdownMenuItem(text = { Text(catalogProviderName(service)) }, onClick = { expanded = false; onProvider(service) }, modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_provider_${service.name}")) }
						}
					}
					IconButton(onClick = onSearch, modifier = Modifier.size(48.dp).testTag("catalog_search_action")) { Icon(painterResource(R.drawable.ic_search), stringResource(R.string.hub_search_catalogue)) }
				}
				CatalogFilterRow(state.request.filter, onFilter)
			}
			if (state.stale) item(key = "stale") { Text(stringResource(R.string.hub_stale, catalogProviderName(state.request.service)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("catalog_stale")) }
			state.failure?.let { error -> item(key = "error") { CatalogFeedback(error, state.request.service, onRefresh) } }
			if (state.loading) item(key = "loading") { CatalogLoading() }
			if (spotlightSection != null) item(key = "spotlight") {
				val item = spotlightSection.items.first()
				Card(onClick = { onItem(item.key) }, modifier = Modifier.fillMaxWidth().testTag("catalog_spotlight")) {
					Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
						CatalogCover(item, imageLoader, Modifier.width(96.dp).height(144.dp))
						Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(catalogSectionTitle(spotlightSection.key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
							Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
							Text(catalogProviderName(item.key.service), style = MaterialTheme.typography.labelSmall)
						}
					}
				}
			}
			items(sections, key = { "${it.key.kind}_${it.key.type}" }) { section ->
				Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("catalog_section_${section.key.kind}_${section.key.type}")) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(catalogSectionTitle(section.key), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
						TextButton(onClick = { onSection(section.key) }, modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_see_all_${section.key.kind}_${section.key.type}")) { Text(stringResource(R.string.hub_see_all)) }
					}
					section.failure?.let { CatalogFeedback(it, state.request.service, onRefresh) }
					if (section.items.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						items(section.items, key = { it.key.stableId }) { item -> CatalogCard(item, imageLoader, { onItem(item.key) }, Modifier.width(116.dp)) }
					} else if (section.failure == null) Text(stringResource(R.string.hub_empty), style = MaterialTheme.typography.bodySmall)
				}
			}
			if (!state.loading && state.failure == null && sections.isEmpty()) item(key = "empty") { Text(stringResource(R.string.hub_empty), modifier = Modifier.padding(24.dp).testTag("catalog_empty")) }
			item(key = "footer") {
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
					TextButton(onClick = onAttribution, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.hub_attribution, catalogProviderName(state.request.service))) }
					IconButton(onClick = onRefresh, enabled = !state.loading && !state.refreshing, modifier = Modifier.size(48.dp).testTag("catalog_refresh")) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.hub_refresh)) }
				}
			}
		}
	}
}
