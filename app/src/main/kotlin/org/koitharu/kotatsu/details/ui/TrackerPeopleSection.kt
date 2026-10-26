package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerMediaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPerson
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelation
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerResult
import java.net.URI

/** Optional people/relation metadata only. A provider failure never replaces core Details content. */
@Composable
internal fun TrackerPeopleSection(
	state: DetailsPeopleUiState,
	imageLoader: ImageLoader,
	onRetry: (ScrobblerService) -> Unit,
	onRefresh: () -> Unit,
) {
	Column(Modifier.fillMaxWidth()) {
		// Keep all people lanes before relations so the single vertical flow remains:
		// Chapters -> Characters/Staff -> Relations -> Recommendations.
		for (provider in state.providers) {
			PeopleLane(provider.service, provider.target?.id, provider.characters, true, imageLoader)
			PeopleLane(provider.service, provider.target?.id, provider.staff, false, imageLoader)
			if (provider.characters is TrackerResult.Error || provider.staff is TrackerResult.Error) {
				Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
					Text(
						stringResource(R.string.tracker_people_unavailable, stringResource(provider.service.titleResId)),
						modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
					)
					TextButton(onClick = { onRetry(provider.service) }, enabled = !state.isLoading) {
						Text(stringResource(R.string.tracker_people_retry))
					}
				}
			}
		}
		for (provider in state.providers) {
			RelationLane(provider.service, provider.target?.id, provider.relations, imageLoader)
			if (provider.relations is TrackerResult.Error) {
				Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
					Text(
						stringResource(R.string.tracker_relations_unavailable, stringResource(provider.service.titleResId)),
						modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
					)
					TextButton(onClick = { onRetry(provider.service) }, enabled = !state.isLoading) {
						Text(stringResource(R.string.tracker_people_retry))
					}
				}
			}
		}
		if (state.isLoading) {
			Row(
				Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("tracker-people-loading"),
				horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
			) {
				CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
				Text(stringResource(R.string.tracker_people_loading), style = MaterialTheme.typography.bodySmall)
			}
		}
		if (state.isUnavailable) {
			TextButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 16.dp), enabled = !state.isLoading) {
				Text(stringResource(R.string.tracker_people_retry))
			}
		}
	}
}

@Composable
private fun PeopleLane(
	service: ScrobblerService,
	targetId: String?,
	result: TrackerResult<TrackerPerson>,
	characters: Boolean,
	imageLoader: ImageLoader,
) {
	val people = result.peopleItems()
	if (people.isEmpty()) return
	var expanded by remember(service, targetId, characters, result) { mutableStateOf(false) }
	val visible = if (expanded) people else people.take(10)
	val section = if (characters) "characters" else "staff"
	Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("tracker-people:${service.name}:$section")) {
		Text(
			stringResource(
				if (characters) R.string.tracker_people_characters else R.string.tracker_people_staff,
				stringResource(service.titleResId),
			),
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
		)
		LazyRow(
			contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			// Index distinguishes anonymous records; people are never merged by display name.
			itemsIndexed(visible, key = { index, person -> "${service.name}:$section:${person.id}:$index" }) { _, person ->
				TrackerPersonCard(person, imageLoader)
			}
		}
		if (people.size > 10) {
			TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 16.dp)) {
				Text(stringResource(if (expanded) R.string.tracker_people_show_less else R.string.tracker_people_show_more))
			}
		}
		if (result is TrackerResult.Partial || result is TrackerResult.Success && result.next != null) {
			Text(
				stringResource(R.string.tracker_people_partial), style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
			)
		}
	}
}

@Composable
private fun RelationLane(
	service: ScrobblerService,
	targetId: String?,
	result: TrackerResult<TrackerRelation>,
	imageLoader: ImageLoader,
) {
	val relations = result.relationItems()
	if (relations.isEmpty()) return
	var expanded by remember(service, targetId, result) { mutableStateOf(false) }
	val visible = if (expanded) relations else relations.take(10)
	Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("tracker-relations:${service.name}")) {
		Text(
			stringResource(R.string.tracker_relations, stringResource(service.titleResId)),
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
		)
		LazyRow(
			contentPadding = PaddingValues(horizontal = 24.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			itemsIndexed(
				visible,
				key = { index, relation -> "${service.name}:relations:${relation.target.id}:$index" },
			) { _, relation ->
				TrackerRelationCard(relation, imageLoader)
			}
		}
		if (relations.size > 10) {
			TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 16.dp)) {
				Text(stringResource(if (expanded) R.string.tracker_people_show_less else R.string.tracker_people_show_more))
			}
		}
		if (result is TrackerResult.Partial || result is TrackerResult.Success && result.next != null) {
			Text(
				stringResource(R.string.tracker_people_partial),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
			)
		}
	}
}

private fun TrackerResult<TrackerRelation>.relationItems(): List<TrackerRelation> = when (this) {
	is TrackerResult.Success -> items
	is TrackerResult.Partial -> items
	else -> emptyList()
}

private fun relationLabelRes(type: TrackerRelationType): Int = when (type) {
	TrackerRelationType.ADAPTATION -> R.string.tracker_relation_adaptation
	TrackerRelationType.PREQUEL -> R.string.tracker_relation_prequel
	TrackerRelationType.SEQUEL -> R.string.tracker_relation_sequel
	TrackerRelationType.PARENT -> R.string.tracker_relation_parent
	TrackerRelationType.SIDE_STORY -> R.string.tracker_relation_side_story
	TrackerRelationType.CHARACTER -> R.string.tracker_relation_character
	TrackerRelationType.SUMMARY -> R.string.tracker_relation_summary
	TrackerRelationType.ALTERNATIVE -> R.string.tracker_relation_alternative
	TrackerRelationType.SPIN_OFF -> R.string.tracker_relation_spin_off
	TrackerRelationType.SOURCE -> R.string.tracker_relation_source
	TrackerRelationType.COMPILATION -> R.string.tracker_relation_compilation
	TrackerRelationType.CONTAINS -> R.string.tracker_relation_contains
	TrackerRelationType.SAME_UNIVERSE -> R.string.tracker_relation_same_universe
	TrackerRelationType.OTHER -> R.string.tracker_relation_other
}

private fun relationMediaRes(type: TrackerMediaType): Int = when (type) {
	TrackerMediaType.ANIME -> R.string.tracker_relation_media_anime
	TrackerMediaType.MANGA -> R.string.tracker_relation_media_manga
	TrackerMediaType.OTHER -> R.string.tracker_relation_media_other
}

@Composable
private fun TrackerRelationCard(item: TrackerRelation, imageLoader: ImageLoader) {
	val context = LocalContext.current
	val uriHandler = LocalUriHandler.current
	val image = trackerPortraitUrl(item.image)
	val providerUrl = trackerPortraitUrl(item.target.url)
	val relationLabel = stringResource(relationLabelRes(item.relation))
	val mediaLabel = stringResource(relationMediaRes(item.mediaType))
	val format = item.format?.replace('_', ' ')?.trim()?.takeIf { it.isNotEmpty() }
	val details = if (format == null) mediaLabel else "$mediaLabel · $format"
	Column(Modifier.width(128.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		val card = Modifier.clip(RoundedCornerShape(16.dp)).let { base ->
			if (providerUrl == null) base else base.clickable { runCatching { uriHandler.openUri(providerUrl) } }
		}
		Column(card.testTag("tracker-relation:${item.target.service}:${item.target.id}")) {
			val coverModifier = Modifier.fillMaxWidth().height(176.dp)
				.background(MaterialTheme.colorScheme.surfaceContainerHighest)
			if (image != null) {
				AsyncImage(
					model = remember(image, context) { ImageRequest.Builder(context).data(image).build() },
					imageLoader = imageLoader,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = coverModifier,
				)
			} else {
				Box(coverModifier, contentAlignment = Alignment.Center) {
					Text(item.title.take(1), style = MaterialTheme.typography.headlineMedium)
				}
			}
			Text(
				relationLabel,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.primary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(top = 6.dp),
			)
			Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
			Text(
				details,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/** Never attach parser image extras or provider credentials to tracker portrait/cover hosts. */
internal fun trackerPortraitUrl(value: String?): String? = value?.let {
	runCatching { URI(it) }.getOrNull()?.takeIf { url ->
		url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null
	}?.toString()
}

@Composable
internal fun TrackerPersonCard(person: TrackerPerson, imageLoader: ImageLoader) {
	val context = LocalContext.current
	val image = trackerPortraitUrl(person.image)
	Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		val imageModifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp))
		if (image != null) {
			AsyncImage(
				model = remember(image, context) { ImageRequest.Builder(context).data(image).build() },
				imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop,
				modifier = imageModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag("tracker-person-image"),
			)
		} else {
			val description = stringResource(R.string.tracker_people_no_image, person.name)
			Box(
				imageModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
					.testTag("tracker-person-no-image").semantics { contentDescription = description },
				contentAlignment = Alignment.Center,
			) {
				// A neutral initial, not a fabricated provider portrait.
				Text(person.name.take(1), style = MaterialTheme.typography.headlineMedium)
			}
		}
		Text(person.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
		if (person.roles.isNotEmpty()) {
			Text(
				person.roles.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
			)
		}
	}
}
