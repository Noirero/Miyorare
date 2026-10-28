package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogStatistics
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType
import java.net.URI
import java.text.NumberFormat
import java.util.Locale

private const val MAL_CHARACTER_PREVIEW_COUNT = 10

@Composable
internal fun CatalogSupplementalDetails(
	details: TrackerCatalogDetails,
	imageLoader: ImageLoader,
	onItem: (TrackerCatalogKey) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
		details.statistics?.let { CatalogStatisticsSection(it) }
		if (details.characters.isNotEmpty()) {
			CatalogPeopleLane(
				title = stringResource(R.string.hub_characters),
				people = details.characters,
				imageLoader = imageLoader,
				tag = "characters",
				viewAll = details.item.key.service == ScrobblerService.MAL,
			)
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
private fun CatalogStatisticsSection(statistics: TrackerCatalogStatistics) {
	var expanded by rememberSaveable { mutableStateOf(false) }
	Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("catalog_statistics")) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = 48.dp)
				.clip(RoundedCornerShape(12.dp))
				.clickable { expanded = !expanded }
				.testTag("catalog_statistics_toggle")
				.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(stringResource(R.string.hub_statistics), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
			statistics.total?.let {
				Text(formatCatalogPeopleCount(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Icon(
				imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
				contentDescription = stringResource(if (expanded) R.string.hub_collapse_statistics else R.string.hub_expand_statistics),
			)
		}
		if (expanded) {
			Column(
				modifier = Modifier.fillMaxWidth().testTag("catalog_statistics_content"),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				statistics.reading?.let { CatalogStatisticRow(stringResource(R.string.hub_reading), formatCatalogPeopleCount(it)) }
				statistics.completed?.let { CatalogStatisticRow(stringResource(R.string.hub_completed), formatCatalogPeopleCount(it)) }
				statistics.onHold?.let { CatalogStatisticRow(stringResource(R.string.hub_on_hold), formatCatalogPeopleCount(it)) }
				statistics.dropped?.let { CatalogStatisticRow(stringResource(R.string.hub_dropped), formatCatalogPeopleCount(it)) }
				statistics.planToRead?.let { CatalogStatisticRow(stringResource(R.string.hub_plan_to_read), formatCatalogPeopleCount(it)) }
				statistics.total?.let { CatalogStatisticRow(stringResource(R.string.hub_total), formatCatalogPeopleCount(it)) }
				if (statistics.scores.isNotEmpty()) {
					Text(
						stringResource(R.string.hub_score_distribution),
						style = MaterialTheme.typography.labelLarge,
						modifier = Modifier.padding(top = 4.dp),
					)
					statistics.scores.forEach { score ->
						val percentage = score.percentage?.let { String.format(Locale.getDefault(), "%.1f%%", it) }
						CatalogStatisticRow(
							label = stringResource(R.string.hub_score_value, score.score),
							value = listOfNotNull(formatCatalogPeopleCount(score.votes), percentage).joinToString(" · "),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun CatalogStatisticRow(label: String, value: String) {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
		Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
	}
}

@Composable
private fun CatalogPeopleLane(
	title: String,
	people: List<TrackerCatalogPerson>,
	imageLoader: ImageLoader,
	tag: String,
	viewAll: Boolean = false,
) {
	var expanded by rememberSaveable(tag, people.size) { mutableStateOf(false) }
	val canExpand = viewAll && people.size > MAL_CHARACTER_PREVIEW_COUNT
	val visiblePeople = if (canExpand && !expanded) people.take(MAL_CHARACTER_PREVIEW_COUNT) else people
	Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("catalog_$tag")) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
			if (canExpand) {
				TextButton(
					onClick = { expanded = !expanded },
					modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_${tag}_view_all"),
				) {
					Text(stringResource(if (expanded) R.string.hub_show_less else R.string.hub_view_all))
				}
			}
		}
		LazyRow(
			modifier = Modifier.testTag("catalog_${tag}_list"),
			contentPadding = PaddingValues(end = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			items(visiblePeople, key = { person -> "${person.id}:${person.name}:${person.roles.joinToString()}" }) { person ->
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

private fun formatCatalogPeopleCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

private fun safeCatalogHttps(value: String?): String? = value?.let {
	runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
		uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
	}?.toString()
}
