package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.data.TrackerCatalogRepository
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogCharacterDetails
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogCharacterWork
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

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CatalogCharacterDetailsEntryPoint {
	fun catalogRepository(): TrackerCatalogRepository
}

@Composable
internal fun CatalogSupplementalDetails(
	details: TrackerCatalogDetails,
	imageLoader: ImageLoader,
	onItem: (TrackerCatalogKey) -> Unit,
	loadCharacterDetails: (suspend (TrackerCatalogPerson) -> TrackerCatalogCharacterDetails)? = null,
) {
	var selectedCharacter by remember { mutableStateOf<TrackerCatalogPerson?>(null) }
	Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
		details.statistics?.let { CatalogStatisticsSection(it) }
		if (details.characters.isNotEmpty()) {
			CatalogPeopleLane(
				title = stringResource(R.string.hub_characters),
				people = details.characters,
				imageLoader = imageLoader,
				tag = "characters",
				viewAll = details.item.key.service == ScrobblerService.MAL,
				onPerson = if (details.item.key.service == ScrobblerService.MAL) ({ selectedCharacter = it }) else null,
			)
		}
		if (details.staffPeople.isNotEmpty()) {
			CatalogPeopleLane(stringResource(R.string.hub_staff), details.staffPeople, imageLoader, "staff")
		}
		if (details.relatedWorks.isNotEmpty()) {
			CatalogRelatedWorksLane(details.relatedWorks, imageLoader, onItem)
		}
	}
	selectedCharacter?.let { person ->
		val loader = loadCharacterDetails ?: rememberMALCharacterDetailsLoader()
		CatalogCharacterDetailsDialog(
			person = person,
			workTitle = details.item.title,
			imageLoader = imageLoader,
			loadDetails = loader,
			onItem = onItem,
			onDismiss = { selectedCharacter = null },
		)
	}
}

@Composable
private fun rememberMALCharacterDetailsLoader(): suspend (TrackerCatalogPerson) -> TrackerCatalogCharacterDetails {
	val context = LocalContext.current
	val repository = remember(context) {
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			CatalogCharacterDetailsEntryPoint::class.java,
		).catalogRepository()
	}
	return remember(repository) {
		val loader: suspend (TrackerCatalogPerson) -> TrackerCatalogCharacterDetails = { person ->
			repository.characterDetails(ScrobblerService.MAL, person)
		}
		loader
	}
}

@Composable
private fun CatalogStatisticsSection(statistics: TrackerCatalogStatistics) {
	var expanded by rememberSaveable { mutableStateOf(false) }
	val toggleLabel = stringResource(if (expanded) R.string.hub_collapse_statistics else R.string.hub_expand_statistics)
	Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("catalog_statistics")) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = 48.dp)
				.clip(RoundedCornerShape(12.dp))
				.clickable(onClickLabel = toggleLabel) { expanded = !expanded }
				.testTag("catalog_statistics_toggle")
				.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(stringResource(R.string.hub_statistics), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
			statistics.total?.let {
				Text(formatCatalogPeopleCount(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelLarge)
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
	onPerson: ((TrackerCatalogPerson) -> Unit)? = null,
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
				CatalogPersonCard(person, imageLoader, tag, onPerson?.let { handler -> { handler(person) } })
			}
		}
	}
}

@Composable
private fun CatalogPersonCard(
	person: TrackerCatalogPerson,
	imageLoader: ImageLoader,
	tag: String,
	onClick: (() -> Unit)? = null,
) {
	val uriHandler = LocalUriHandler.current
	val providerUrl = safeCatalogHttps(person.providerUrl)
	val image = safeCatalogHttps(person.imageUrl)
	val click = onClick ?: providerUrl?.let { url -> { runCatching { uriHandler.openUri(url) }; Unit } }
	val cardModifier = Modifier.width(112.dp).let { base -> if (click == null) base else base.clickable(onClick = click) }
	Column(
		cardModifier.testTag("catalog_${tag}_person_${person.id ?: person.name}"),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		val imageModifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp))
		if (image != null) {
			AsyncImage(
				model = image,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = imageModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag("catalog_person_image"),
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
private fun CatalogCharacterDetailsDialog(
	person: TrackerCatalogPerson,
	workTitle: String,
	imageLoader: ImageLoader,
	loadDetails: suspend (TrackerCatalogPerson) -> TrackerCatalogCharacterDetails,
	onItem: (TrackerCatalogKey) -> Unit,
	onDismiss: () -> Unit,
) {
	var details by remember(person.id) { mutableStateOf<TrackerCatalogCharacterDetails?>(null) }
	var loading by remember(person.id) { mutableStateOf(true) }
	var failed by remember(person.id) { mutableStateOf(false) }
	var retry by remember(person.id) { mutableIntStateOf(0) }
	var aboutExpanded by rememberSaveable(person.id) { mutableStateOf(false) }

	LaunchedEffect(person.id, retry) {
		loading = true
		failed = false
		try {
			details = loadDetails(person)
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			failed = true
		} finally {
			loading = false
		}
	}

	val shownPerson = details?.person ?: person
	val uriHandler = LocalUriHandler.current
	val providerUrl = safeCatalogHttps(shownPerson.providerUrl)
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier.fillMaxSize().testTag("catalog_character_details"),
			color = MaterialTheme.colorScheme.background,
		) {
			Column(Modifier.fillMaxSize().systemBarsPadding()) {
				Row(
					modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					IconButton(onClick = onDismiss, modifier = Modifier.testTag("catalog_character_back")) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
					}
					Text(
						shownPerson.name,
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(horizontal = 8.dp),
					)
				}
				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
					verticalArrangement = Arrangement.spacedBy(16.dp),
				) {
					item { CatalogCharacterHeader(shownPerson, details, workTitle, imageLoader) }
					if (loading) {
						item {
							Row(
								modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
								horizontalArrangement = Arrangement.Center,
								verticalAlignment = Alignment.CenterVertically,
							) {
								CircularProgressIndicator(Modifier.testTag("catalog_character_loading"))
							}
						}
					}
					if (failed) {
						item {
							Column(
								modifier = Modifier.fillMaxWidth().testTag("catalog_character_load_failed"),
								horizontalAlignment = Alignment.CenterHorizontally,
								verticalArrangement = Arrangement.spacedBy(8.dp),
							) {
								Text(stringResource(R.string.hub_character_load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
								TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
							}
						}
					}
					details?.about?.takeIf(String::isNotBlank)?.let { about ->
						item {
							Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("catalog_character_about")) {
								Text(stringResource(R.string.hub_character_about), style = MaterialTheme.typography.titleMedium)
								Text(
									about,
									style = MaterialTheme.typography.bodyMedium,
									maxLines = if (aboutExpanded) Int.MAX_VALUE else 8,
									overflow = if (aboutExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
								)
								if (about.length > 320) {
									TextButton(onClick = { aboutExpanded = !aboutExpanded }) {
										Text(stringResource(if (aboutExpanded) R.string.hub_show_less else R.string.hub_read_more))
									}
								}
							}
						}
					}
					details?.voices?.takeIf(List<TrackerCatalogPerson>::isNotEmpty)?.let { voices ->
						item {
							Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("catalog_character_voices")) {
								Text(stringResource(R.string.hub_character_voiced_by), style = MaterialTheme.typography.titleMedium)
								LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
									items(voices, key = { voice -> "voice_${voice.id}:${voice.name}" }) { voice ->
										CatalogPersonCard(voice, imageLoader, "voice")
									}
								}
							}
						}
					}
					details?.works?.takeIf(List<TrackerCatalogCharacterWork>::isNotEmpty)?.let { works ->
						item {
							Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("catalog_character_works")) {
								Text(stringResource(R.string.hub_appears_in), style = MaterialTheme.typography.titleMedium)
								LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
									items(works, key = { work -> "${work.mediaKind}:${work.remoteId}" }) { work ->
										CatalogCharacterWorkCard(work, imageLoader, onItem)
									}
								}
							}
						}
					}
					providerUrl?.let { url ->
						item {
							TextButton(
								onClick = { runCatching { uriHandler.openUri(url) } },
								modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_character_open_provider"),
							) {
								Text(stringResource(R.string.hub_open_character_page))
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun CatalogCharacterHeader(
	person: TrackerCatalogPerson,
	details: TrackerCatalogCharacterDetails?,
	workTitle: String,
	imageLoader: ImageLoader,
) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
		val image = safeCatalogHttps(person.imageUrl)
		val portrait = Modifier.width(180.dp).height(240.dp).clip(RoundedCornerShape(20.dp))
		if (image != null) {
			AsyncImage(
				model = image,
				imageLoader = imageLoader,
				contentDescription = person.name,
				contentScale = ContentScale.Crop,
				modifier = portrait.background(MaterialTheme.colorScheme.surfaceContainerHighest),
			)
		} else {
			Box(portrait.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
				Text(person.name.take(1), style = MaterialTheme.typography.displaySmall)
			}
		}
		Text(person.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
		details?.nativeName?.takeIf(String::isNotBlank)?.let {
			Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		if (person.roles.isNotEmpty()) {
			CatalogStatisticRow(stringResource(R.string.hub_character_role), person.roles.joinToString(" · "))
		}
		CatalogStatisticRow(stringResource(R.string.hub_appears_in), workTitle)
		details?.favorites?.let {
			CatalogStatisticRow(stringResource(R.string.hub_character_favorites), formatCatalogPeopleCount(it))
		}
		details?.nicknames?.takeIf(List<String>::isNotEmpty)?.let {
			CatalogStatisticRow(stringResource(R.string.hub_character_nicknames), it.joinToString(" · "))
		}
	}
}

@Composable
private fun CatalogCharacterWorkCard(
	work: TrackerCatalogCharacterWork,
	imageLoader: ImageLoader,
	onItem: (TrackerCatalogKey) -> Unit,
) {
	val uriHandler = LocalUriHandler.current
	val image = safeCatalogHttps(work.coverUrl)
	val providerUrl = safeCatalogHttps(work.providerUrl)
	val click = work.catalogKey?.let { key -> { onItem(key) } }
		?: providerUrl?.let { url -> { runCatching { uriHandler.openUri(url) }; Unit } }
	val modifier = Modifier.width(128.dp).let { base -> if (click == null) base else base.clickable(onClick = click) }
	Column(modifier.testTag("catalog_character_work_${work.mediaKind}_${work.remoteId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
		Text(catalogRelatedMediaName(work.mediaKind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
		Text(work.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
		work.role?.takeIf(String::isNotBlank)?.let {
			Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
	val metadata = listOfNotNull(media, format).joinToString(" · ")
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
		Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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