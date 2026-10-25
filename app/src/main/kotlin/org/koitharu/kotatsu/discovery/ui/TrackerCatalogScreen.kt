package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLanguageCode
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareSurface
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackerCatalogScreen(
	state: CatalogScreenState,
	section: CatalogSectionKey?,
	imageLoader: ImageLoader,
	onBack: () -> Unit,
	onQuery: (String) -> Unit,
	onSearch: () -> Unit,
	onFilter: (CatalogFilter) -> Unit,
	onRetry: () -> Unit,
	onNext: () -> Unit,
	onItem: (TrackerCatalogKey) -> Unit,
	onProviderPage: () -> Unit,
	onRead: () -> Unit,
	onAllSources: () -> Unit,
	onCloseResolution: () -> Unit,
	onGlobalSearch: () -> Unit,
	onSource: (Manga) -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	val scaffoldColor = if (palette.isModern) Color.Transparent else MaterialTheme.colorScheme.background
	Scaffold(
		modifier = Modifier.systemBarsPadding(),
		containerColor = scaffoldColor,
		topBar = {
			TopAppBar(
				title = { Text(section?.let { catalogSectionTitle(it) } ?: catalogProviderName(state.service), maxLines = 1, overflow = TextOverflow.Ellipsis) },
				navigationIcon = {
					IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = if (palette.isModern) Color.Transparent else MaterialTheme.colorScheme.surface,
					scrolledContainerColor = if (palette.isModern) MaterialTheme.colorScheme.surface.copy(alpha = 0.86f) else MaterialTheme.colorScheme.surface,
				),
			)
		},
	) { padding ->
		if (state.mode == CatalogScreenMode.DETAILS) {
			CatalogDetailsContent(state, imageLoader, onRetry, onItem, onProviderPage, onRead, Modifier.padding(padding))
		} else {
			CatalogListingContent(state, imageLoader, onQuery, onSearch, onFilter, onRetry, onNext, onItem, Modifier.padding(padding))
		}
	}

	if (state.resolution.visible) {
		ModalBottomSheet(
			onDismissRequest = onCloseResolution,
			modifier = Modifier.testTag("catalog_source_sheet"),
			containerColor = if (palette.isModern) MaterialTheme.colorScheme.surface.copy(alpha = 0.96f) else MaterialTheme.colorScheme.surface,
		) {
			LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				item { Text(stringResource(R.string.hub_source_choices), style = MaterialTheme.typography.titleLarge) }
				if (state.resolution.searching) item {
					LinearProgressIndicator(Modifier.fillMaxWidth())
					Text(stringResource(R.string.hub_source_searching))
				}
				if (state.resolution.failedSources) item { Text(stringResource(R.string.hub_source_partial)) }
				if (!state.resolution.searching && state.resolution.candidates.isEmpty()) item {
					Text(stringResource(R.string.hub_source_empty), Modifier.testTag("catalog_source_empty"))
				}
				items(state.resolution.candidates, key = { "${it.source.name}_${it.id}" }) { manga ->
					OutlinedCard(
						onClick = { onSource(manga) },
						modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("catalog_source_${manga.source.name}_${manga.id}"),
					) {
						ListItem(
							headlineContent = { Text(manga.title) },
							supportingContent = { Text(listOfNotNull(manga.source.getTitle(LocalContext.current), manga.source.getLanguageCode()).joinToString(" · ")) },
							colors = ListItemDefaults.colors(containerColor = Color.Transparent),
						)
					}
				}
				if (!state.resolution.allSources) item {
					TextButton(onClick = onAllSources, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_all_sources")) {
						Text(stringResource(R.string.hub_all_sources))
					}
				}
				item {
					TextButton(onClick = onGlobalSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_global_search")) {
						Text(stringResource(R.string.hub_global_search))
					}
				}
			}
		}
	}
}

@Composable
private fun CatalogListingContent(
	state: CatalogScreenState,
	imageLoader: ImageLoader,
	onQuery: (String) -> Unit,
	onSearch: () -> Unit,
	onFilter: (CatalogFilter) -> Unit,
	onRetry: () -> Unit,
	onNext: () -> Unit,
	onItem: (TrackerCatalogKey) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(modifier.fillMaxSize()) {
		if (state.mode == CatalogScreenMode.SEARCH) {
			Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
				OutlinedTextField(
					value = state.query,
					onValueChange = onQuery,
					modifier = Modifier.fillMaxWidth().testTag("catalog_search_input"),
					singleLine = true,
					label = { Text(stringResource(R.string.hub_search_hint, catalogProviderName(state.service))) },
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
					keyboardActions = KeyboardActions(onSearch = { onSearch() }),
					trailingIcon = {
						IconButton(onClick = onSearch, modifier = Modifier.testTag("catalog_search_submit")) {
							Icon(Icons.Default.Search, stringResource(R.string.hub_search_catalogue))
						}
					},
					shape = RoundedCornerShape(20.dp),
				)
				CatalogFilterRow(state.filter, onFilter)
			}
		}
		LazyVerticalGrid(
			columns = GridCells.Adaptive(132.dp),
			modifier = Modifier.weight(1f).testTag("catalog_listing"),
			contentPadding = PaddingValues(16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { CatalogLoading() }
			state.failure?.let { failure -> item(span = { GridItemSpan(maxLineSpan) }) { CatalogFeedback(failure, state.service, onRetry) } }
			if (state.items.isEmpty() && !state.loading && state.failure == null) {
				item(span = { GridItemSpan(maxLineSpan) }) {
					Text(
						stringResource(if (state.mode == CatalogScreenMode.SEARCH && !state.searched) R.string.hub_search_start else R.string.hub_empty),
						Modifier.padding(24.dp).testTag("catalog_empty"),
					)
				}
			}
			items(state.items, key = { it.key.stableId }) { item -> CatalogCard(item, imageLoader, { onItem(item.key) }) }
			if (state.loadingMore) {
				item(span = { GridItemSpan(maxLineSpan) }) { CatalogLoading() }
			} else if (state.nextPage != null) {
				item(span = { GridItemSpan(maxLineSpan) }) {
					TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_load_more")) {
						Text(stringResource(R.string.hub_load_more))
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogDetailsContent(
	state: CatalogScreenState,
	imageLoader: ImageLoader,
	onRetry: () -> Unit,
	onItem: (TrackerCatalogKey) -> Unit,
	onProviderPage: () -> Unit,
	onRead: () -> Unit,
	modifier: Modifier = Modifier,
) {
	LazyColumn(
		modifier.fillMaxSize().testTag("catalog_details"),
		contentPadding = PaddingValues(16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		if (state.loading) item { CatalogLoading() }
		state.failure?.let { failure -> item { CatalogFeedback(failure, state.service, onRetry) } }
		state.details?.let { details ->
			item { CatalogDetailHero(details, state.service, imageLoader) }
			item {
				Button(onClick = onRead, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("catalog_read")) {
					Text(
						stringResource(
							if (state.known.isEmpty()) R.string.hub_source_cta
							else if (details.item.key.kind == CatalogMediaKind.NOVEL) R.string.hub_open_novel
							else R.string.hub_open_manga,
						),
						fontWeight = FontWeight.SemiBold,
					)
				}
			}
			if (details.item.providerUrl != null) item {
				OutlinedButton(onClick = onProviderPage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_provider_page")) {
					Text(stringResource(R.string.hub_provider_page, catalogProviderName(state.service)))
				}
			}
			if (details.partial) item {
				MetadataSurface { Text(stringResource(R.string.hub_partial_metadata), color = MaterialTheme.colorScheme.onSurfaceVariant) }
			}
			details.description?.let { description ->
				item { MetadataBlock(stringResource(R.string.hub_description), HtmlCompat.fromHtml(description, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()) }
			}
			if (details.genres.isNotEmpty()) item {
				MetadataSurface {
					Text(stringResource(R.string.hub_genres), style = MaterialTheme.typography.titleMedium)
					FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
						details.genres.forEach { genre -> SuggestionChip(onClick = {}, label = { Text(genre) }, enabled = false) }
					}
				}
			}
			if (details.status != null || details.startDate != null || details.rank != null || details.popularity != null) item {
				MetadataSurface {
					details.status?.let { MetadataInfoRow(stringResource(R.string.hub_status), it) }
					details.startDate?.let { MetadataInfoRow(stringResource(R.string.hub_publication), it) }
					details.rank?.let { MetadataInfoRow(stringResource(R.string.hub_rank, it).substringBefore(':'), "#$it") }
					details.popularity?.let {
						MetadataInfoRow(
							if (state.service == ScrobblerService.ANILIST) stringResource(R.string.hub_readers, it).substringBefore(':') else stringResource(R.string.hub_popularity, it).substringBefore(':'),
							if (state.service == ScrobblerService.ANILIST) it.toString() else "#$it",
						)
					}
				}
			}
			if (details.alternativeTitles.isNotEmpty()) item {
				MetadataBlock(stringResource(R.string.hub_alternative_titles), details.alternativeTitles.joinToString("\n"))
			}
			if (details.staff.isNotEmpty()) item { MetadataBlock(stringResource(R.string.hub_staff), details.staff.joinToString("\n")) }
			if (details.recommendations.isNotEmpty()) item {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(stringResource(R.string.hub_recommendations), style = MaterialTheme.typography.titleMedium)
					LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						items(details.recommendations, key = { it.key.stableId }) { item ->
							CatalogCard(item, imageLoader, { onItem(item.key) }, Modifier.width(136.dp))
						}
					}
				}
			}
		}
	}
}

@Composable
private fun CatalogDetailHero(details: TrackerCatalogDetails, service: ScrobblerService, imageLoader: ImageLoader) {
	val palette = LocalMiyorareVisualPalette.current
	val shape = RoundedCornerShape(26.dp)
	Box(Modifier.fillMaxWidth().heightIn(min = 230.dp).miyorareSurface(palette, shape).background(Color.Transparent, shape)) {
		details.bannerUrl?.let {
			AsyncImage(
				model = it,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxWidth().height(230.dp),
			)
		}
		if (details.bannerUrl != null) {
			Box(
				Modifier.fillMaxWidth().height(230.dp).background(
					Brush.verticalGradient(
						listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.20f), MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
					),
				),
			)
		}
		Row(
			Modifier.fillMaxWidth().padding(18.dp).align(Alignment.BottomStart),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.Bottom,
		) {
			CatalogCover(details.item, imageLoader, Modifier.width(112.dp).height(166.dp))
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
				Text(details.item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
				Text("${catalogProviderName(service)} · ${catalogKindName(details.item.key.kind)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
				details.item.score?.let { Text(stringResource(R.string.hub_score, String.format(Locale.getDefault(), "%.1f", it)), style = MaterialTheme.typography.bodyMedium) }
			}
		}
	}
}

@Composable
private fun MetadataSurface(content: @Composable ColumnScope.() -> Unit) {
	val palette = LocalMiyorareVisualPalette.current
	val shape = RoundedCornerShape(20.dp)
	Surface(
		modifier = Modifier.fillMaxWidth().miyorareSurface(palette, shape),
		shape = shape,
		color = if (palette.isModern) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
	}
}

@Composable
private fun MetadataBlock(label: String, text: String) {
	MetadataSurface {
		Text(label, style = MaterialTheme.typography.titleMedium)
		Text(text, style = MaterialTheme.typography.bodyMedium)
	}
}

@Composable
private fun MetadataInfoRow(label: String, value: String) {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
		Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
	}
}
