package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.getLanguageCode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.parsers.model.Manga
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerCatalogScreen(
	state: CatalogScreenState, section: CatalogSectionKey?, imageLoader: ImageLoader,
	onBack: () -> Unit, onQuery: (String) -> Unit, onSearch: () -> Unit, onFilter: (CatalogFilter) -> Unit,
	onRetry: () -> Unit, onNext: () -> Unit, onItem: (TrackerCatalogKey) -> Unit, onProviderPage: () -> Unit,
	onRead: () -> Unit, onAllSources: () -> Unit, onCloseResolution: () -> Unit,
	onGlobalSearch: () -> Unit, onSource: (Manga) -> Unit,
) {
	Scaffold(modifier = Modifier.systemBarsPadding(), topBar = {
		TopAppBar(title = { Text(section?.let { catalogSectionTitle(it) } ?: catalogProviderName(state.service)) },
			navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
	}) { padding ->
		if (state.mode == CatalogScreenMode.DETAILS) {
			LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("catalog_details"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
				if (state.loading) item { CatalogLoading() }
				state.failure?.let { failure -> item { CatalogFeedback(failure, state.service, onRetry) } }
				state.details?.let { details ->
					item {
						Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
							details.bannerUrl?.let { AsyncImage(it, null, imageLoader, modifier = Modifier.fillMaxWidth().height(120.dp)) }
							Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
								CatalogCover(details.item, imageLoader, Modifier.width(112.dp).height(166.dp))
								Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
									Text(details.item.title, style = MaterialTheme.typography.headlineSmall)
									Text("${catalogProviderName(state.service)} · ${catalogKindName(details.item.key.kind)}")
									details.item.score?.let { Text(stringResource(R.string.hub_score, String.format(Locale.getDefault(), "%.1f", it))) }
									details.rank?.let { Text(stringResource(R.string.hub_rank, it)) }
									details.popularity?.let { Text(stringResource(if (state.service == org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.ANILIST) R.string.hub_readers else R.string.hub_popularity, it)) }
								}
							}
							Button(onClick = onRead, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_read")) {
								Text(stringResource(if (state.known.isEmpty()) R.string.hub_source_cta else if (details.item.key.kind == CatalogMediaKind.NOVEL) R.string.hub_open_novel else R.string.hub_open_manga))
							}
							if (details.item.providerUrl != null) OutlinedButton(onClick = onProviderPage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_provider_page")) { Text(stringResource(R.string.hub_provider_page, catalogProviderName(state.service))) }
							if (details.partial) Text(stringResource(R.string.hub_partial_metadata), color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
					}
					details.description?.let { description -> item { MetadataBlock(stringResource(R.string.hub_description), HtmlCompat.fromHtml(description, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()) } }
					if (details.alternativeTitles.isNotEmpty()) item { MetadataBlock(stringResource(R.string.hub_alternative_titles), details.alternativeTitles.joinToString("\n")) }
					if (details.genres.isNotEmpty()) item { MetadataBlock(stringResource(R.string.hub_genres), details.genres.joinToString(" · ")) }
					if (details.staff.isNotEmpty()) item { MetadataBlock(stringResource(R.string.hub_staff), details.staff.joinToString("\n")) }
					details.status?.let { item { MetadataBlock(stringResource(R.string.hub_status), it) } }
					details.startDate?.let { item { MetadataBlock(stringResource(R.string.hub_publication), it) } }
					if (details.recommendations.isNotEmpty()) item {
						Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(stringResource(R.string.hub_recommendations), style = MaterialTheme.typography.titleMedium)
							LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(details.recommendations, key = { it.key.stableId }) { item -> CatalogCard(item, imageLoader, { onItem(item.key) }, Modifier.width(128.dp)) } }
						}
					}
				}
			}
		} else {
			Column(Modifier.fillMaxSize().padding(padding)) {
				if (state.mode == CatalogScreenMode.SEARCH) {
					OutlinedTextField(state.query, onQuery, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("catalog_search_input"), singleLine = true,
						label = { Text(stringResource(R.string.hub_search_hint, catalogProviderName(state.service))) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
						trailingIcon = { IconButton(onClick = onSearch, modifier = Modifier.testTag("catalog_search_submit")) { Icon(Icons.Default.Search, stringResource(R.string.hub_search_catalogue)) } })
					CatalogFilterRow(state.filter, onFilter, Modifier.padding(horizontal = 16.dp))
				}
				LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.weight(1f).testTag("catalog_listing"), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { CatalogLoading() }
					state.failure?.let { failure -> item(span = { GridItemSpan(maxLineSpan) }) { CatalogFeedback(failure, state.service, onRetry) } }
					if (state.items.isEmpty() && !state.loading && state.failure == null) item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(if (state.mode == CatalogScreenMode.SEARCH && !state.searched) R.string.hub_search_start else R.string.hub_empty), Modifier.padding(24.dp).testTag("catalog_empty")) }
					items(state.items, key = { it.key.stableId }) { item -> CatalogCard(item, imageLoader, { onItem(item.key) }) }
					if (state.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { CatalogLoading() }
					else if (state.nextPage != null) item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_load_more")) { Text(stringResource(R.string.hub_load_more)) } }
				}
			}
		}
	}
	if (state.resolution.visible) ModalBottomSheet(onDismissRequest = onCloseResolution, modifier = Modifier.testTag("catalog_source_sheet")) {
		LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			item { Text(stringResource(R.string.hub_source_choices), style = MaterialTheme.typography.titleLarge) }
			if (state.resolution.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.hub_source_searching)) }
			if (state.resolution.failedSources) item { Text(stringResource(R.string.hub_source_partial)) }
			if (!state.resolution.searching && state.resolution.candidates.isEmpty()) item { Text(stringResource(R.string.hub_source_empty), Modifier.testTag("catalog_source_empty")) }
			items(state.resolution.candidates, key = { "${it.source.name}_${it.id}" }) { manga ->
				OutlinedCard(onClick = { onSource(manga) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("catalog_source_${manga.source.name}_${manga.id}")) {
					ListItem(headlineContent = { Text(manga.title) }, supportingContent = { Text(listOfNotNull(manga.source.getTitle(LocalContext.current), manga.source.getLanguageCode()).joinToString(" · ")) })
				}
			}
			if (!state.resolution.allSources) item { TextButton(onClick = onAllSources, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_all_sources")) { Text(stringResource(R.string.hub_all_sources)) } }
			item { TextButton(onClick = onGlobalSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_global_search")) { Text(stringResource(R.string.hub_global_search)) } }
		}
	}
}

@Composable private fun MetadataBlock(label: String, text: String) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(label, style = MaterialTheme.typography.titleMedium)
		Text(text, style = MaterialTheme.typography.bodyMedium)
	}
}
