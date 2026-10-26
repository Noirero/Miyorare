package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogDetails
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogPerson
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogRelatedWork
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType
import java.net.URI

@Composable
internal fun CatalogSupplementalDetails(
	details: TrackerCatalogDetails,
	imageLoader: ImageLoader,
	onItem: (TrackerCatalogKey) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
		if (details.characters.isNotEmpty()) {
			CatalogPeopleLane(stringResource(R.string.hub_characters), details.characters, imageLoader, "characters")
		}
		if (details.staffPeople.isNotEmpty()) {
			CatalogPeopleLane(stringResource(R.string.hub_staff), details.staffPeople, imageLoader, "staff")
		}
		if (details.relatedWorks.isNotEmpty()) {
			CatalogRelatedWorksLane(details.relatedWorks, imageLoader, onItem)
		}
	}
}

@Composable
private fun CatalogPeopleLane(
	title: String,
	people: List<TrackerCatalogPerson>,
	imageLoader: ImageLoader,
	tag: String,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("catalog_$tag")) {
		Text(title, style = MaterialTheme.typography.titleMedium)
		LazyRow(
			contentPadding = PaddingValues(end = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			items(people, key = { person -> "${person.id}:${person.name}:${person.roles.joinToString()}" }) { person ->
				CatalogPersonCard(person, imageLoader)
			}
		}
	}
}

@Composable
private fun CatalogPersonCard(person: TrackerCatalogPerson, imageLoader: ImageLoader) {
	val uriHandler = LocalUriHandler.current
	val providerUrl = safeCatalogHttps(person.providerUrl)
	val image = safeCatalogHttps(person.imageUrl)
	val cardModifier = Modifier.width(112.dp).let { base ->
		if (providerUrl == null) base else base.clickable { runCatching { uriHandler.openUri(providerUrl) } }
	}
	Column(cardModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
		val imageModifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp))
		if (image != null) {
			AsyncImage(
				model = image,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = imageModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
					.testTag("catalog_person_image"),
			)
		} else {
			Box(
				imageModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag("catalog_person_no_image"),
				contentAlignment = Alignment.Center,
			) {
				Text(person.name.take(1), style = MaterialTheme.typography.headlineMedium)
			}
		}
		Text(person.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
		if (person.roles.isNotEmpty()) {
			Text(
				person.roles.joinToString(" · "),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun CatalogRelatedWorksLane(
	items: List<TrackerCatalogRelatedWork>,
	imageLoader: ImageLoader,
	onItem: (TrackerCatalogKey) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("catalog_related_works")) {
		Text(stringResource(R.string.hub_related_works), style = MaterialTheme.typography.titleMedium)
		LazyRow(
			contentPadding = PaddingValues(end = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			items(items, key = { work -> "${work.service}:${work.mediaKind}:${work.remoteId}:${work.relation}" }) { work ->
				CatalogRelatedWorkCard(work, imageLoader, onItem)
			}
		}
	}
}

@Composable
private fun CatalogRelatedWorkCard(
	work: TrackerCatalogRelatedWork,
	imageLoader: ImageLoader,
	onItem: (TrackerCatalogKey) -> Unit,
) {
	val uriHandler = LocalUriHandler.current
	val image = safeCatalogHttps(work.coverUrl)
	val providerUrl = safeCatalogHttps(work.providerUrl)
	val relation = catalogRelationLabel(work)
	val media = catalogRelatedMediaName(work.mediaKind)
	val format = work.format?.replace('_', ' ')?.trim()?.takeIf { value ->
		value.isNotEmpty() && !value.equals(media, ignoreCase = true)
	}
	val details = listOfNotNull(media, format).joinToString(" · ")
	val click = work.catalogKey?.let { key -> { onItem(key) } }
		?: providerUrl?.let { url -> { runCatching { uriHandler.openUri(url) }; Unit } }
	val modifier = Modifier.width(128.dp).let { base -> if (click == null) base else base.clickable(onClick = click) }
	Column(modifier.testTag("catalog_related_${work.service}_${work.remoteId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		val cover = Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(16.dp))
			.background(MaterialTheme.colorScheme.surfaceContainerHighest)
		if (image != null) {
			AsyncImage(
				model = image,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = cover,
			)
		} else {
			Box(cover, contentAlignment = Alignment.Center) {
				Text(work.title.take(1), style = MaterialTheme.typography.headlineMedium)
			}
		}
		Text(relation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(work.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
		Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

@Composable
private fun catalogRelationLabel(work: TrackerCatalogRelatedWork): String = when (work.relation) {
	TrackerRelationType.ADAPTATION -> stringResource(R.string.tracker_relation_adaptation)
	TrackerRelationType.PREQUEL -> stringResource(R.string.tracker_relation_prequel)
	TrackerRelationType.SEQUEL -> stringResource(R.string.tracker_relation_sequel)
	TrackerRelationType.PARENT -> stringResource(R.string.tracker_relation_parent)
	TrackerRelationType.SIDE_STORY -> stringResource(R.string.tracker_relation_side_story)
	TrackerRelationType.CHARACTER -> stringResource(R.string.tracker_relation_character)
	TrackerRelationType.SUMMARY -> stringResource(R.string.tracker_relation_summary)
	TrackerRelationType.ALTERNATIVE -> stringResource(R.string.tracker_relation_alternative)
	TrackerRelationType.SPIN_OFF -> stringResource(R.string.tracker_relation_spin_off)
	TrackerRelationType.SOURCE -> stringResource(R.string.tracker_relation_source)
	TrackerRelationType.COMPILATION -> stringResource(R.string.tracker_relation_compilation)
	TrackerRelationType.CONTAINS -> stringResource(R.string.tracker_relation_contains)
	TrackerRelationType.SAME_UNIVERSE -> stringResource(R.string.tracker_relation_same_universe)
	TrackerRelationType.OTHER -> work.providerRelation?.replace('_', ' ')?.trim()?.takeIf(String::isNotEmpty)
		?: stringResource(R.string.tracker_relation_other)
}

@Composable
private fun catalogRelatedMediaName(kind: CatalogRelatedMediaKind): String = when (kind) {
	CatalogRelatedMediaKind.MANGA -> stringResource(R.string.hub_manga)
	CatalogRelatedMediaKind.NOVEL -> stringResource(R.string.hub_novel)
	CatalogRelatedMediaKind.ANIME -> stringResource(R.string.hub_anime)
	CatalogRelatedMediaKind.OTHER -> stringResource(R.string.hub_other_media)
}

private fun safeCatalogHttps(value: String?): String? = value?.let {
	runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
		uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
	}?.toString()
}
