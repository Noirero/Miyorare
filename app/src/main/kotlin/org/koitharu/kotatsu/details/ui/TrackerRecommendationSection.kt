package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

@Composable
internal fun TrackerRecommendationSection(
	state: DetailsPeopleUiState,
	imageLoader: ImageLoader,
	onClick: (TrackerRecommendation) -> Unit,
	onProviderPage: (TrackerRecommendation) -> Unit,
	onRetry: (ScrobblerService) -> Unit,
	onRefresh: () -> Unit,
) {
	Column(Modifier.fillMaxWidth()) {
		for (provider in state.providers) {
			val result = provider.recommendations
			for ((kind, recommendations) in result.recommendationItems().groupBy { it.kind }) {
				var expanded by remember(provider.service, provider.target, kind, result) { mutableStateOf(false) }
				Column(Modifier.padding(vertical = 12.dp).testTag("tracker-recommendations:${provider.service.name}:$kind")) {
					Text(
						stringResource(if (kind == TrackerRecommendationKind.SIMILAR_MANGA) R.string.tracker_similar_manga else R.string.tracker_recommendations, stringResource(provider.service.titleResId)),
						style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
					)
					LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						itemsIndexed(if (expanded) recommendations else recommendations.take(10), key = { index, item -> "${item.target.service}:${item.target.id}:$index" }) { _, item ->
							TrackerRecommendationCard(item, imageLoader, onClick, onProviderPage)
						}
					}
					if (recommendations.size > 10) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 16.dp)) {
						Text(stringResource(if (expanded) R.string.tracker_people_show_less else R.string.tracker_people_show_more))
					}
					if (result is TrackerResult.Partial || result is TrackerResult.Success && result.next != null) Text(
						stringResource(R.string.tracker_people_partial), style = MaterialTheme.typography.bodySmall,
						modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
					)
				}
			}
			if (result is TrackerResult.Error) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(stringResource(R.string.tracker_recommendations_unavailable, stringResource(provider.service.titleResId)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
				TextButton(onClick = { onRetry(provider.service) }, enabled = !state.isLoading) { Text(stringResource(R.string.tracker_people_retry)) }
			}
		}
		if (state.isLoading) Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp).testTag("tracker-recommendations-loading"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
			CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
			Text(stringResource(R.string.tracker_recommendations_loading), style = MaterialTheme.typography.bodySmall)
		}
		if (state.isUnavailable) TextButton(onClick = onRefresh, enabled = !state.isLoading, modifier = Modifier.padding(horizontal = 16.dp)) {
			Text(stringResource(R.string.tracker_people_retry))
		}
	}
}

@Composable
private fun TrackerRecommendationCard(
	item: TrackerRecommendation, imageLoader: ImageLoader,
	onClick: (TrackerRecommendation) -> Unit, onProviderPage: (TrackerRecommendation) -> Unit,
) {
	val context = LocalContext.current
	val image = trackerPortraitUrl(item.image)
	Column(Modifier.width(128.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { onClick(item) }.testTag("tracker-recommendation:${item.target.service}:${item.target.id}")) {
			val coverModifier = Modifier.fillMaxWidth().height(176.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)
			if (image != null) AsyncImage(
				model = remember(image, context) { ImageRequest.Builder(context).data(image).build() }, imageLoader = imageLoader,
				contentDescription = null, contentScale = ContentScale.Crop, modifier = coverModifier,
			) else Box(coverModifier, contentAlignment = Alignment.Center) { Text(item.title.take(1), style = MaterialTheme.typography.headlineMedium) }
			Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
		}
		if (trackerPortraitUrl(item.target.url) != null) TextButton(onClick = { onProviderPage(item) }, contentPadding = PaddingValues(0.dp)) {
			Text(stringResource(R.string.tracker_provider_page), style = MaterialTheme.typography.labelSmall)
		}
	}
}
