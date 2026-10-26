@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
import org.koitharu.kotatsu.discovery.domain.MangaBakaExploreType
import org.koitharu.kotatsu.discovery.domain.MangaBakaPersonalHomeState
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey

@Composable
internal fun MangaBakaPersonalHomeContent(
	state: MangaBakaPersonalHomeState,
	filter: CatalogFilter,
	knownTracking: Map<Long, CatalogTrackingEntry>,
	imageLoader: ImageLoader,
	onItem: (TrackerCatalogKey) -> Unit,
	onRetry: () -> Unit,
	onReconnect: () -> Unit,
	onLibrarySeeAll: () -> Unit,
	onExploreType: (MangaBakaExploreType) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.fillMaxWidth().testTag("mangabaka_personal_home"),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		if (state.loading && state.library.isEmpty() && state.recommendations.isEmpty()) {
			Row(
				Modifier.fillMaxWidth().padding(vertical = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
				Text(stringResource(R.string.hub_mangabaka_loading_personal), style = MaterialTheme.typography.bodySmall)
			}
		}

		if (state.failed) {
			MangaBakaPersonalFailure(state.authExpired, onRetry, onReconnect)
		}

		if (state.library.isNotEmpty()) {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MangaBakaPersonalSectionHeader(
					title = stringResource(R.string.hub_mangabaka_my_library),
					onSeeAll = onLibrarySeeAll,
				)
				LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					items(state.library, key = { it.item.key.stableId }) { libraryItem ->
						CatalogTrackedCard(
							item = libraryItem.item,
							imageLoader = imageLoader,
							tracking = libraryItem.tracking,
							onClick = { onItem(libraryItem.item.key) },
							modifier = Modifier.width(catalogHomeCardWidth()),
						)
					}
				}
			}
		} else if (!state.loading && !state.failed) {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				MangaBakaPersonalSectionHeader(stringResource(R.string.hub_mangabaka_my_library), onLibrarySeeAll)
				Text(
					stringResource(R.string.hub_mangabaka_library_empty),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}

		if (state.recommendations.isNotEmpty()) {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MangaBakaPersonalSectionHeader(stringResource(R.string.hub_mangabaka_for_you))
				Text(
					stringResource(R.string.hub_mangabaka_for_you_subtitle),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					items(state.recommendations, key = { it.key.stableId }) { item ->
						CatalogTrackedCard(
							item = item,
							imageLoader = imageLoader,
							tracking = item.key.remoteId.toLongOrNull()?.let(knownTracking::get),
							onClick = { onItem(item.key) },
							modifier = Modifier.width(catalogHomeCardWidth()),
						)
					}
				}
			}
		}

		if (state.partial) {
			Text(
				stringResource(R.string.hub_mangabaka_personal_partial),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		MangaBakaExploreByType(
			state = state,
			filter = filter,
			knownTracking = knownTracking,
			imageLoader = imageLoader,
			onItem = onItem,
			onExploreType = onExploreType,
		)
	}
}

@Composable
private fun MangaBakaExploreByType(
	state: MangaBakaPersonalHomeState,
	filter: CatalogFilter,
	knownTracking: Map<Long, CatalogTrackingEntry>,
	imageLoader: ImageLoader,
	onItem: (TrackerCatalogKey) -> Unit,
	onExploreType: (MangaBakaExploreType) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("mangabaka_explore_by_type")) {
		MangaBakaPersonalSectionHeader(stringResource(R.string.hub_mangabaka_explore_type))
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			MangaBakaExploreType.entries.filter { it.isVisibleFor(filter) }.forEach { type ->
				FilterChip(
					selected = state.selectedType == type,
					onClick = { onExploreType(type) },
					label = { Text(mangaBakaExploreTypeName(type)) },
					modifier = Modifier.heightIn(min = 48.dp).testTag("mangabaka_type_${type.name}"),
				)
			}
		}
		when {
			state.exploreLoading -> CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
			state.exploreFailed -> Text(
				stringResource(R.string.hub_mangabaka_explore_failed),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
			state.selectedType != null && state.exploreItems.isEmpty() -> Text(
				stringResource(R.string.hub_empty),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			state.exploreItems.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				items(state.exploreItems, key = { it.key.stableId }) { item ->
					CatalogTrackedCard(
						item = item,
						imageLoader = imageLoader,
						tracking = item.key.remoteId.toLongOrNull()?.let(knownTracking::get),
						onClick = { onItem(item.key) },
						modifier = Modifier.width(catalogHomeCardWidth()),
					)
				}
			}
		}
	}
}

@Composable
private fun MangaBakaPersonalSectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
	val palette = LocalMiyorareVisualPalette.current
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
		Box(
			Modifier.width(4.dp).height(24.dp).background(
				if (palette.isModern) palette.primary else MaterialTheme.colorScheme.primary,
				RoundedCornerShape(50),
			),
		)
		Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
		onSeeAll?.let { action ->
			TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) {
				Text(stringResource(R.string.hub_see_all))
			}
		}
	}
}

@Composable
private fun MangaBakaPersonalFailure(authExpired: Boolean, onRetry: () -> Unit, onReconnect: () -> Unit) {
	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.errorContainer,
		modifier = Modifier.fillMaxWidth().testTag("mangabaka_personal_error"),
	) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(
				stringResource(if (authExpired) R.string.hub_mangabaka_session_expired else R.string.hub_mangabaka_personal_error),
			)
			TextButton(onClick = if (authExpired) onReconnect else onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
				Text(stringResource(if (authExpired) R.string.hub_tracking_reconnect else R.string.hub_retry))
			}
		}
	}
}

@Composable
private fun mangaBakaExploreTypeName(type: MangaBakaExploreType): String = stringResource(
	when (type) {
		MangaBakaExploreType.MANGA -> R.string.hub_manga
		MangaBakaExploreType.NOVEL -> R.string.hub_novel
		MangaBakaExploreType.MANHWA -> R.string.hub_mangabaka_type_manhwa
		MangaBakaExploreType.MANHUA -> R.string.hub_mangabaka_type_manhua
		MangaBakaExploreType.OEL -> R.string.hub_mangabaka_type_oel
		MangaBakaExploreType.OTHER -> R.string.hub_mangabaka_type_other
	},
)
