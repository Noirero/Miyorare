package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
import org.koitharu.kotatsu.discovery.domain.MangaBakaExploreType
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaBakaSeeAllScreen(
	state: MangaBakaSeeAllState,
	knownTracking: Map<Long, CatalogTrackingEntry>,
	imageLoader: ImageLoader,
	onBack: () -> Unit,
	onRetry: () -> Unit,
	onNext: () -> Unit,
	onReconnect: () -> Unit,
	onItem: (TrackerCatalogKey) -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	val scaffoldColor = if (palette.isModern) Color.Transparent else MaterialTheme.colorScheme.background
	val title = when (state.mode) {
		MangaBakaSeeAllMode.LIBRARY -> stringResource(R.string.hub_mangabaka_my_library)
		MangaBakaSeeAllMode.EXPLORE -> "${catalogProviderName(ScrobblerService.MANGABAKA)} · ${mangaBakaExploreTypeName(state.type)}"
	}
	Scaffold(
		modifier = Modifier.systemBarsPadding().testTag("mangabaka_see_all"),
		containerColor = scaffoldColor,
		topBar = {
			TopAppBar(
				title = { Text(title) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = if (palette.isModern) Color.Transparent else MaterialTheme.colorScheme.surface,
					scrolledContainerColor = if (palette.isModern) MaterialTheme.colorScheme.surface.copy(alpha = 0.86f) else MaterialTheme.colorScheme.surface,
				),
			)
		},
	) { padding ->
		LazyVerticalGrid(
			columns = GridCells.Adaptive(132.dp),
			modifier = Modifier.fillMaxSize().padding(padding).testTag("mangabaka_see_all_listing"),
			contentPadding = PaddingValues(16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			if (state.loading) {
				item(span = { GridItemSpan(maxLineSpan) }) { CatalogLoading() }
			}
			if (state.authExpired) {
				item(span = { GridItemSpan(maxLineSpan) }) {
					MangaBakaLibraryReconnect(onReconnect)
				}
			} else {
				state.failure?.let { failure ->
					item(span = { GridItemSpan(maxLineSpan) }) {
						CatalogFeedback(failure, ScrobblerService.MANGABAKA, onRetry)
					}
				}
			}
			if (state.items.isEmpty() && !state.loading && state.failure == null && !state.authExpired) {
				item(span = { GridItemSpan(maxLineSpan) }) {
					Text(
						stringResource(
							if (state.mode == MangaBakaSeeAllMode.LIBRARY) R.string.hub_mangabaka_library_empty else R.string.hub_empty,
						),
						Modifier.padding(24.dp).testTag("mangabaka_see_all_empty"),
					)
				}
			}
			items(state.items, key = { it.key.stableId }) { item ->
				CatalogTrackedCard(
					item = item,
					imageLoader = imageLoader,
					tracking = item.key.remoteId.toLongOrNull()?.let(knownTracking::get),
					onClick = { onItem(item.key) },
				)
			}
			if (state.loadingMore) {
				item(span = { GridItemSpan(maxLineSpan) }) { CatalogLoading() }
			} else if (state.nextPage != null && !state.authExpired) {
				item(span = { GridItemSpan(maxLineSpan) }) {
					TextButton(
						onClick = onNext,
						modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mangabaka_see_all_load_more"),
					) {
						Text(stringResource(R.string.hub_load_more))
					}
				}
			}
		}
	}
}

@Composable
private fun MangaBakaLibraryReconnect(onReconnect: () -> Unit) {
	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.errorContainer,
		modifier = Modifier.fillMaxWidth().testTag("mangabaka_see_all_reconnect"),
	) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(R.string.hub_mangabaka_session_expired))
			TextButton(onClick = onReconnect, modifier = Modifier.heightIn(min = 48.dp)) {
				Text(stringResource(R.string.hub_tracking_editor_reconnect))
			}
		}
	}
}

@Composable
private fun mangaBakaExploreTypeName(type: MangaBakaExploreType?): String = when (type) {
	MangaBakaExploreType.MANGA -> stringResource(R.string.hub_manga)
	MangaBakaExploreType.NOVEL -> stringResource(R.string.hub_novel)
	MangaBakaExploreType.MANHWA -> stringResource(R.string.hub_mangabaka_type_manhwa)
	MangaBakaExploreType.MANHUA -> stringResource(R.string.hub_mangabaka_type_manhua)
	MangaBakaExploreType.OEL -> stringResource(R.string.hub_mangabaka_type_oel)
	MangaBakaExploreType.OTHER -> stringResource(R.string.hub_mangabaka_type_other)
	null -> catalogProviderName(ScrobblerService.MANGABAKA)
}
