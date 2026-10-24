package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun catalogProviderName(service: ScrobblerService): String = when (service) {
	ScrobblerService.ANILIST -> "AniList"
	ScrobblerService.MAL -> "MyAnimeList"
	ScrobblerService.KITSU -> "Kitsu"
	else -> service.name
}

@Composable fun catalogKindName(kind: CatalogMediaKind): String = stringResource(if (kind == CatalogMediaKind.MANGA) R.string.hub_manga else R.string.hub_novel)
@Composable fun catalogSectionTitle(key: CatalogSectionKey): String {
	val title = stringResource(when (key.type) {
		CatalogSectionType.RECENT -> R.string.hub_recent
		CatalogSectionType.TRENDING -> R.string.hub_trending
		CatalogSectionType.POPULAR -> R.string.hub_popular
		CatalogSectionType.TOP_RATED -> R.string.hub_top_rated
		CatalogSectionType.TOP_RANKED -> R.string.hub_top_ranked
		CatalogSectionType.MOST_FAVORITED -> R.string.hub_most_favorited
	})
	return stringResource(R.string.hub_section_title, title, catalogKindName(key.kind))
}

@Composable fun CatalogFilterRow(filter: CatalogFilter, onFilter: (CatalogFilter) -> Unit, modifier: Modifier = Modifier) {
	Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		CatalogFilter.entries.forEach { choice ->
			FilterChip(selected = choice == filter, onClick = { onFilter(choice) },
				label = { Text(stringResource(when (choice) { CatalogFilter.ALL -> R.string.hub_all; CatalogFilter.MANGA -> R.string.hub_manga; CatalogFilter.NOVEL -> R.string.hub_novel })) },
				modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_filter_${choice.name}"))
		}
	}
}

@Composable fun CatalogCover(item: TrackerCatalogItem, imageLoader: ImageLoader, modifier: Modifier = Modifier) {
	Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
		if (item.coverUrl != null) AsyncImage(model = item.coverUrl, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
		else Icon(painterResource(R.drawable.ic_discovery_hub), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
	}
}

@Composable fun CatalogCard(item: TrackerCatalogItem, imageLoader: ImageLoader, onClick: () -> Unit, modifier: Modifier = Modifier) {
	Card(onClick = onClick, modifier = modifier.testTag("catalog_card_${item.key.service.name}_${item.key.kind.name}_${item.key.remoteId}"), shape = RoundedCornerShape(12.dp)) {
		Column {
			CatalogCover(item, imageLoader, Modifier.fillMaxWidth().aspectRatio(0.68f))
			Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
				Text(catalogKindName(item.key.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				item.score?.let { Text(stringResource(R.string.hub_score, String.format(Locale.getDefault(), "%.1f", it)), style = MaterialTheme.typography.labelSmall) }
			}
		}
	}
}

@Composable fun CatalogLoading(modifier: Modifier = Modifier) {
	Column(modifier.fillMaxWidth().padding(32.dp).testTag("catalog_loading"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
		CircularProgressIndicator()
		Text(stringResource(R.string.hub_loading))
	}
}

@Composable fun CatalogFeedback(failure: CatalogFailure, service: ScrobblerService, onRetry: () -> Unit, modifier: Modifier = Modifier) {
	val message = when (failure.kind) {
		CatalogFailureKind.OFFLINE -> R.string.hub_offline
		CatalogFailureKind.RATE_LIMITED -> R.string.hub_rate_limited
		CatalogFailureKind.INVALID_DATA -> R.string.hub_invalid_data
		CatalogFailureKind.UNSUPPORTED -> R.string.hub_unsupported
		CatalogFailureKind.PROVIDER -> R.string.hub_provider_error
	}
	Surface(modifier.testTag("catalog_error_${failure.kind.name}"), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
		Column(Modifier.padding(16.dp)) {
			Text(if (failure.kind == CatalogFailureKind.UNSUPPORTED) stringResource(message) else stringResource(message, catalogProviderName(service)))
			failure.retryAt?.let { Text(stringResource(R.string.hub_retry_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))), style = MaterialTheme.typography.bodySmall) }
			TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_retry")) { Text(stringResource(R.string.hub_retry)) }
		}
	}
}
