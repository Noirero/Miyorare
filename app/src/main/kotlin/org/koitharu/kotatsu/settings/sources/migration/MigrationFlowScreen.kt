package org.koitharu.kotatsu.settings.sources.migration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationFailureStage
import org.koitharu.kotatsu.alternatives.domain.MigrationConflictReason
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchPolicy
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLangCode
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageFlag
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.explore.ui.adapter.ExploreSourceOrigin
import org.koitharu.kotatsu.explore.ui.adapter.exploreSourceOrigin
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra

/**
 * The existing ViewModel and WorkManager remain the only execution authorities.
 * This screen only projects their states into Search -> Review -> Results.
 */
@Composable
internal fun MigrationFlowScreen(
	state: SourceMangaListState,
	imageLoader: ImageLoader,
	onRetry: () -> Unit,
	onSearch: () -> Unit,
	onMigrateReady: () -> Unit,
	onCopyReady: () -> Unit,
	onCancel: (String) -> Unit,
	onFilter: (MigrationBatchStatus?) -> Unit,
	onToggleSource: (String) -> Unit,
	onSelectAllSources: () -> Unit,
	onClearSources: () -> Unit,
	onMoveSource: (String, Int) -> Unit,
	onStrategy: (MigrationSourceStrategy) -> Unit,
	onExtraSearch: (String) -> Unit,
	onSkipSetup: (Boolean) -> Unit,
	onRetrySources: () -> Unit,
	onMangaClick: (Manga) -> Unit,
	onMangaLongClick: (Manga) -> Unit,
	onMigrateOne: (Long) -> Unit = {},
	onCopyOne: (Long) -> Unit = {},
	onSkipOne: (Long) -> Unit = {},
	onOpenTarget: (Manga) -> Unit = {},
    onOpenSource: (Manga) -> Unit = {},
) {
	var step by rememberSaveable { mutableIntStateOf(0) }
	var pendingAction by remember { mutableStateOf<MigrationBatchAction?>(null) }
	// Capture the exact batch the user agreed to stop, not whatever is active later.
	var pendingCancellationBatch by remember { mutableStateOf<String?>(null) }
	val busy = state.batchBusy || state.scheduling
	BackHandler(enabled = step > 0 && !busy) { step = 0 }

	if (state.isLoading) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
		return
	}
	if (state.explicitUnavailable && state.manga.isEmpty() &&
        state.completedResults.isEmpty() && !state.isLoading
    ) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(if (state.explicitNotFavourite)
                stringResource(R.string.migration_flow_not_favourite)
                else stringResource(R.string.migration_flow_unavailable))
        }
        return
    }
    if (state.failed && state.manga.isEmpty()) {
		Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally) {
			Text(stringResource(R.string.migration_flow_session_failed))
			TextButton(onClick = onRetry) { Text(stringResource(R.string.migration_retry)) }
		}
		return
	}

	// Source setup and the live results are the only two navigation surfaces.
	// The same result list holds discovery, manual intervention, progress and completed outcomes.
	when (step) {
		0 -> MigrationSearchSetup(
			state = state,
			enabled = !busy,
			onToggleSource = onToggleSource,
			onSelectAllSources = onSelectAllSources,
			onClearSources = onClearSources,
			onMoveSource = onMoveSource,
			onStrategy = onStrategy,
			onExtraSearch = onExtraSearch,
			onSkipSetup = onSkipSetup,
			onRetrySources = onRetrySources,
			onSearch = { onSearch(); step = 1 },
			onViewProgress = { step = 1 },
			onCancelActive = { pendingCancellationBatch = state.activeBatchName },
		)
		else -> MigrationReviewStep(
			state = state,
			imageLoader = imageLoader,
			onMangaClick = onMangaClick,
            onOpenSource = onOpenSource,
			onOpenTarget = onOpenTarget,
			onRetry = { step = 0 },
			onContinue = { pendingAction = MigrationBatchAction.MIGRATE },
			onCopy = { pendingAction = MigrationBatchAction.COPY },
			onCancel = { pendingCancellationBatch = state.activeBatchName },
			onMigrateOne = onMigrateOne,
			onCopyOne = onCopyOne,
			onSkipOne = onSkipOne,
		)
	}

	pendingAction?.let { action ->
		val readyIds = MigrationBatchPolicy.readyRequests(
			manga = state.manga, snapshots = state.batchItems,
			discoveryMode = MigrationBatchDiscoveryMode.YOKAI, configKey = state.configKey,
		).mapTo(hashSetOf()) { it.mangaId }
		val ready = state.manga.filter { it.id in readyIds }
		AlertDialog(
			onDismissRequest = { pendingAction = null },
			title = { Text(if (action == MigrationBatchAction.COPY) stringResource(R.string.migration_flow_confirm_copy) else stringResource(R.string.migration_flow_confirm_migrate)) },
			text = { Text(
				if (action == MigrationBatchAction.COPY) stringResource(R.string.migration_flow_confirm_copy_count, ready.size)
				else stringResource(R.string.migration_flow_confirm_migrate_count, ready.size)
			) },
			confirmButton = {
				TextButton(
					enabled = ready.isNotEmpty() && !busy,
					onClick = {
						pendingAction = null
						if (action == MigrationBatchAction.COPY) onCopyReady() else onMigrateReady()
						step = 1
					},
					modifier = Modifier.testTag("migration-batch-confirm"),
				) { Text(if (action == MigrationBatchAction.COPY) stringResource(R.string.migration_flow_copy) else stringResource(R.string.migration_migrate)) }
			},
			dismissButton = { TextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.migration_flow_cancel)) } },
		)
	}
	pendingCancellationBatch?.let { confirmedBatch ->
		AlertDialog(
			onDismissRequest = { pendingCancellationBatch = null },
			title = { Text(stringResource(R.string.migration_flow_stop_search)) },
			text = { Text(if (state.ownedBatchName != confirmedBatch)
				stringResource(R.string.migration_flow_stop_other)
				else stringResource(R.string.migration_flow_stop_pending)) },
			confirmButton = { TextButton(onClick = {
				pendingCancellationBatch = null
				// The active session may have changed while confirmation was open.
				if (state.activeBatchName == confirmedBatch) onCancel(confirmedBatch)
			}) { Text(stringResource(R.string.migration_flow_stop)) } },
			dismissButton = { TextButton(onClick = { pendingCancellationBatch = null }) { Text(stringResource(R.string.migration_flow_cancel)) } },
		)
	}
}

/**
 * Group by installed package/plugin identity, never by display title or source-language count.
 * The input pool is already resolved from installed runtime providers by the ViewModel.
 */
internal data class MigrationDestinationGroup(
	val key: String,
	val label: String,
	val origin: ExploreSourceOrigin?,
	val sources: List<org.koitharu.kotatsu.parsers.model.MangaSource>,
	/** Non-null for per-language groups of Tsuki/UMA/Gekkoushi providers. */
	val languageCode: String? = null,
)

internal fun destinationGroups(
	sources: List<org.koitharu.kotatsu.parsers.model.MangaSource>,
	context: android.content.Context,
): List<MigrationDestinationGroup> = sources
	.groupBy { source ->
		when (val raw = source.unwrap()) {
			is MihonMangaSource -> "store:" + raw.pkgName
			is TsukiMangaSource -> "language:" + raw.plugin.provider.name + ":" + migrationLanguageCode(raw.language)
			is LnMangaSource -> "novel:" + raw.pluginId
			else -> "source:" + source.name
		}
	}
	.map { (key, variants) ->
		val first = variants.first().unwrap()
		val lang = (first as? TsukiMangaSource)?.let { migrationLanguageCode(it.language) }
		MigrationDestinationGroup(
			key = key,
			label = when {
				lang != null -> migrationLanguageLabel(lang)
				first is MihonMangaSource -> first.displayName
				else -> variants.first().getTitle(context).toString()
			},
			origin = variants.first().exploreSourceOrigin(),
			sources = variants.sortedBy { source ->
				val raw = source.unwrap()
				if (raw is MihonMangaSource) raw.language else source.getTitle(context).toString().lowercase()
			},
			languageCode = lang,
		)
	}

/** Presentation-only language identity; migration selection continues to use source.name. */
internal fun migrationLanguageCode(raw: String): String {
	val normalized = getExternalExtensionLangCode(raw).substringBefore('-').substringBefore('_').lowercase()
	return normalized.takeUnless { it.isBlank() || it in setOf("all", "mul", "und", "other", "global") } ?: "other"
}

internal fun migrationLanguageLabel(code: String): String = when (code) {
	"id" -> "🇮🇩 Indonesia"
	"en" -> "🇬🇧 English"
	"other" -> "🏳️ Other / Global"
	else -> getExternalExtensionLanguageFlag(code) + " " + getExternalExtensionLanguageDisplayName(code)
}

internal fun migrationLanguageRank(code: String?): Int = when (code) {
	"id" -> 0
	"en" -> 1
	"other" -> 999
	else -> 2
}

private fun ExploreSourceOrigin?.migrationTabTitle(): String = when (this) {
	ExploreSourceOrigin.MIYORARE_PACK -> "Miyorare"
	ExploreSourceOrigin.EXTENSION_STORE -> "Store"
	ExploreSourceOrigin.UMA -> "UMA"
	ExploreSourceOrigin.GEKKOUSHI -> "Gekkoushi"
	else -> "Other"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MigrationSearchSetup(
	state: SourceMangaListState,
	enabled: Boolean,
	onToggleSource: (String) -> Unit,
	onSelectAllSources: () -> Unit,
	onClearSources: () -> Unit,
	onMoveSource: (String, Int) -> Unit,
	onStrategy: (MigrationSourceStrategy) -> Unit,
	onExtraSearch: (String) -> Unit,
	onSkipSetup: (Boolean) -> Unit,
	onRetrySources: () -> Unit,
	onSearch: () -> Unit,
	onViewProgress: () -> Unit,
	onCancelActive: () -> Unit,
) {
	val context = LocalContext.current
	var showOptions by rememberSaveable { mutableStateOf(false) }
	var activeTab by rememberSaveable { mutableStateOf("all") }
	var sortAscending by rememberSaveable { mutableStateOf(true) }
	var expandedKeys by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
	val installed = state.installedSources
	val groups = remember(installed, context) { destinationGroups(installed, context) }
	val eligibleNames = state.availableSources.mapTo(hashSetOf(), MangaSource::name)
	val order = listOf("Miyorare", "Store", "UMA", "Gekkoushi", "Other")
	val tabs = listOf("all") + order.filter { name -> groups.any { it.origin.migrationTabTitle() == name } }
	val selectedTab = activeTab.takeIf { it in tabs } ?: "all"
	val visibleGroups = groups.filter { selectedTab == "all" || it.origin.migrationTabTitle() == selectedTab }
		.let { filtered ->
			if (selectedTab in setOf("Miyorare", "UMA", "Gekkoushi"))
				filtered.sortedWith(compareBy<MigrationDestinationGroup> { migrationLanguageRank(it.languageCode) }
					.thenBy { it.label.lowercase() })
			else if (sortAscending) filtered.sortedBy { it.label.lowercase() }
			else filtered.sortedByDescending { it.label.lowercase() }
		}
	val selectedSet = state.selectedSourceNames.toSet()
	Column(Modifier.fillMaxSize()) {
		if (state.batchBusy && state.ownedBatchName == null) {
			Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(stringResource(R.string.migration_flow_other_running), modifier = Modifier.weight(1f),
					style = MaterialTheme.typography.bodySmall)
				TextButton(onClick = onCancelActive, enabled = state.activeBatchName != null) {
					Text(stringResource(R.string.migration_flow_cancel_batch))
				}
			}
		}
		Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.migration_flow_choose_destination), style = MaterialTheme.typography.titleLarge,
				modifier = Modifier.weight(1f))
			TextButton(onClick = { sortAscending = !sortAscending }) {
				Text(if (sortAscending) "A–Z" else "Z–A")
			}
			TextButton(onClick = { showOptions = true }) { Text(stringResource(R.string.migration_flow_options)) }
		}
		when {
			state.sourceSelectionLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
			state.sourceSelectionFailed -> Column(Modifier.padding(16.dp)) {
				Text(stringResource(R.string.migration_source_list_failed))
				TextButton(onClick = onRetrySources) { Text(stringResource(R.string.migration_retry)) }
			}
			groups.isEmpty() -> Text(stringResource(R.string.migration_flow_no_plugins), modifier = Modifier.padding(16.dp))
			else -> {
				ScrollableTabRow(
					selectedTabIndex = tabs.indexOf(selectedTab),
					edgePadding = 12.dp,
					divider = {},
				) {
					tabs.forEach { tab ->
						val count = groups
							.filter { tab == "all" || it.origin.migrationTabTitle() == tab }
							.sumOf { if (it.languageCode == null) 1 else it.sources.size }
						Tab(selected = selectedTab == tab, onClick = { activeTab = tab },
							text = { Text(stringResource(R.string.migration_scope_with_count,
								when (tab) {
									"all" -> stringResource(R.string.migration_flow_all)
									"Other" -> stringResource(R.string.migration_source_origin_other)
									else -> tab
								}, count)) })
					}
				}
				Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically) {
					TextButton(onClick = onSelectAllSources, enabled = enabled) { Text(stringResource(R.string.migration_select_all_visible)) }
					TextButton(onClick = onClearSources, enabled = enabled) { Text(stringResource(R.string.migration_deselect_all_visible)) }
					Text(stringResource(R.string.migration_sources_selected, state.selectedSourceNames.size),
						style = MaterialTheme.typography.bodySmall)
				}
				if (eligibleNames.isEmpty()) {
					Text(stringResource(R.string.migration_flow_no_compatible),
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
						color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				if (state.sourceStrategy == MigrationSourceStrategy.FIRST_MATCH && selectedSet.isNotEmpty()) {
					Text(stringResource(R.string.migration_flow_priority_hint),
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				LazyColumn(Modifier.weight(1f),
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp)) {
					items(visibleGroups, key = MigrationDestinationGroup::key) { group ->
						val isExpanded = group.key in expandedKeys
						val eligibleGroup = group.sources.filter { it.name in eligibleNames }
						val allChecked = eligibleGroup.isNotEmpty() && eligibleGroup.all { it.name in selectedSet }
						if (group.sources.size > 1 || group.languageCode != null) {
							Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
								verticalAlignment = Alignment.CenterVertically) {
								Checkbox(checked = allChecked, enabled = enabled && eligibleGroup.isNotEmpty(), onCheckedChange = {
									eligibleGroup.forEach { source ->
										if ((source.name in selectedSet) == allChecked) onToggleSource(source.name)
									}
								})
								Row(Modifier.weight(1f).clickable {
									expandedKeys = ArrayList(expandedKeys.toMutableSet().apply {
										if (!add(group.key)) remove(group.key)
									})
								}.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
									Column(Modifier.weight(1f)) {
										Text(group.label + " (" + group.sources.size + ")",
											maxLines = 2, overflow = TextOverflow.Ellipsis)
										val groupPriorities = eligibleGroup.mapNotNull { source ->
											state.selectedSourceNames.indexOf(source.name).takeIf { it >= 0 }?.plus(1)
										}.sorted()
										if (groupPriorities.isNotEmpty()) Text(
											stringResource(R.string.migration_flow_priorities, groupPriorities.joinToString(", ")),
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.primary,
											maxLines = 1, overflow = TextOverflow.Ellipsis,
										)
									}
									Text(if (isExpanded) "⌄" else "›", style = MaterialTheme.typography.titleMedium)
								}
							}
						}
						if ((group.languageCode == null && group.sources.size == 1) || isExpanded) {
							val orderedSources = if (sortAscending) group.sources else group.sources.reversed()
							orderedSources.forEach { source ->
								val index = state.selectedSourceNames.indexOf(source.name)
								val sourceEnabled = enabled && source.name in eligibleNames
								val raw = source.unwrap()
								val label = if (raw is MihonMangaSource && group.sources.size > 1)
									raw.displayName + " (" + raw.language + ")"
								else source.getTitle(context).toString()
								Row(Modifier.fillMaxWidth().clickable(enabled = sourceEnabled) {
									onToggleSource(source.name)
								}.padding(start = if (group.sources.size > 1 || group.languageCode != null) 28.dp else 0.dp, top = 4.dp, bottom = 4.dp),
									verticalAlignment = Alignment.CenterVertically) {
									Checkbox(checked = index >= 0, enabled = sourceEnabled,
										onCheckedChange = { onToggleSource(source.name) })
									Column(modifier = Modifier.weight(1f)) {
										Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
										if (!sourceEnabled && source.name !in eligibleNames) Text(
											stringResource(R.string.migration_flow_incompatible),
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
										)
									}
									if (index >= 0) {
										Text(stringResource(R.string.migration_flow_priority, index + 1),
											modifier = Modifier.testTag("migration-priority-" + source.name),
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.primary)
										TextButton(onClick = { onMoveSource(source.name, -1) },
											enabled = enabled && index > 0) { Text("↑") }
										TextButton(onClick = { onMoveSource(source.name, 1) },
											enabled = enabled && index < state.selectedSourceNames.lastIndex) { Text("↓") }
									}
								}
							}
						}
					}
				}
			}
		}
		Surface(tonalElevation = 3.dp) {
			Column(Modifier.fillMaxWidth().padding(12.dp)) {
				if (state.batchBusy || state.completedResults.isNotEmpty()) OutlinedButton(onClick = onViewProgress,
					modifier = Modifier.fillMaxWidth()) {
					Text(if (state.batchBusy) stringResource(R.string.migration_flow_view_search) else stringResource(R.string.migration_flow_view_results))
				}
				Button(onClick = onSearch,
					enabled = enabled && state.manga.isNotEmpty() &&
						state.selectedSourceNames.any { it in eligibleNames } &&
						!state.sourceSelectionLoading && !state.sourceSelectionFailed,
					modifier = Modifier.fillMaxWidth().height(52.dp).testTag("migration-batch-search")) {
					Text(stringResource(R.string.migration_yokai_search_selected_count, state.manga.size))
				}
			}
		}
	}
	if (showOptions) {
		ModalBottomSheet(onDismissRequest = { showOptions = false }) {
			Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(stringResource(R.string.migration_flow_search_options), style = MaterialTheme.typography.titleLarge)
				for (strategy in MigrationSourceStrategy.entries) {
					Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onStrategy(strategy) },
						verticalAlignment = Alignment.CenterVertically) {
						RadioButton(selected = state.sourceStrategy == strategy,
							onClick = { onStrategy(strategy) }, enabled = enabled)
						Text(if (strategy == MigrationSourceStrategy.FIRST_MATCH) stringResource(R.string.migration_yokai_strategy_first)
							else stringResource(R.string.migration_yokai_strategy_most))
					}
				}
				OutlinedTextField(value = state.extraSearchParams, onValueChange = onExtraSearch,
					enabled = enabled, label = { Text(stringResource(R.string.migration_yokai_extra_search)) },
					modifier = Modifier.fillMaxWidth())
				Row(verticalAlignment = Alignment.CenterVertically) {
					Checkbox(checked = state.skipSetup, onCheckedChange = onSkipSetup, enabled = enabled)
					Text(stringResource(R.string.migration_flow_reuse_setup))
				}
				Button(onClick = { showOptions = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_flow_done)) }
			}
		}
	}
}

@Composable
private fun MigrationReviewStep(
	state: SourceMangaListState,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
    onOpenSource: (Manga) -> Unit,
	onOpenTarget: (Manga) -> Unit,
	onRetry: () -> Unit,
	onContinue: () -> Unit,
	onCopy: () -> Unit,
	onCancel: () -> Unit,
	onMigrateOne: (Long) -> Unit,
	onCopyOne: (Long) -> Unit,
	onSkipOne: (Long) -> Unit,
) {
	val readyIds = MigrationBatchPolicy.readyRequests(
		manga = state.manga, snapshots = state.batchItems,
		discoveryMode = MigrationBatchDiscoveryMode.YOKAI, configKey = state.configKey,
	).mapTo(hashSetOf()) { it.mangaId }
	val busy = state.batchBusy || state.scheduling
	Column(Modifier.fillMaxSize()) {
		Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.migration_flow_results), style = MaterialTheme.typography.titleLarge,
				modifier = Modifier.weight(1f))
			TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.migration_change_sources)) }
		}
		if (busy) {
			LinearProgressIndicator(Modifier.fillMaxWidth())
			Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(if (state.batchTotal == 0) stringResource(R.string.migration_flow_other_running)
					else stringResource(R.string.migration_flow_processing, state.batchCompleted, state.batchTotal),
					modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
				TextButton(onClick = onCancel, enabled = state.activeBatchName != null) { Text(stringResource(R.string.migration_flow_cancel_batch)) }
			}
		}
		if (state.failed) {
			Text(stringResource(R.string.migration_flow_partial_failure),
				modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
		}
		LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)) {
			items(state.manga, key = Manga::id) { manga ->
				val status = state.status(manga.id)
				val outcome = state.batchItems[manga.id]?.outcome
				MigrationMangaReviewItem(manga, imageLoader, status,
					outcome?.destinationTitle, outcome?.destinationSource,
					onClick = { onMangaClick(manga) },
                    onOpenSource = onOpenSource,
					failureStage = outcome?.failureStage,
					conflictReason = outcome?.conflictReason,
					targetManga = state.readyTarget(manga.id) ?: state.completedTarget(manga.id),
					onOpenTarget = onOpenTarget,
					onMigrate = if (!busy && manga.id in readyIds) ({ onMigrateOne(manga.id) }) else null,
					onCopy = if (!busy && manga.id in readyIds) ({ onCopyOne(manga.id) }) else null,
					onSkip = if (!busy) ({ onSkipOne(manga.id) }) else null)
			}
			if (state.manga.isEmpty()) item {
                Text(when {
                    state.explicitNotFavourite ->
                        stringResource(R.string.migration_flow_not_favourite)
                    state.explicitUnavailable -> stringResource(R.string.migration_flow_unavailable)
                    else -> stringResource(R.string.migration_flow_empty_session)
                })
            }
			if (state.completedResults.isNotEmpty()) {
				item { Text(stringResource(R.string.migration_flow_previous_results), style = MaterialTheme.typography.titleSmall) }
				items(state.completedResults, key = { "done:" + it.mangaId + ":" + it.generation }) { snapshot ->
					val target = state.completedTarget(snapshot.mangaId)
					if (target != null) {
						MigrationCompletedResultItem(
							mangaId = snapshot.mangaId, manga = target,
							status = snapshot.outcome.status,
							imageLoader = imageLoader, onOpenTarget = onOpenTarget,
						)
					} else {
						Text((snapshot.outcome.destinationTitle ?: "Manga") + " · " +
							stringResource(snapshot.outcome.status.labelRes()), style = MaterialTheme.typography.bodySmall)
					}
				}
			}
		}
		Surface(tonalElevation = 3.dp) {
			Column(Modifier.fillMaxWidth().padding(12.dp)) {
				if (readyIds.isNotEmpty() && !busy) {
					if (state.preferredAction == MigrationBatchAction.COPY) {
						Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()
							.testTag("migration-batch-copy")) { Text(stringResource(R.string.migration_flow_copy_count, readyIds.size)) }
						OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()
							.testTag("migration-batch-migrate")) { Text(stringResource(R.string.migration_flow_migrate_count, readyIds.size)) }
					} else {
						Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()
							.testTag("migration-batch-migrate")) { Text(stringResource(R.string.migration_flow_migrate_count, readyIds.size)) }
						OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()
							.testTag("migration-batch-copy")) { Text(stringResource(R.string.migration_flow_copy_count, readyIds.size)) }
					}
				} else {
					Text(if (busy) stringResource(R.string.migration_flow_working)
						else stringResource(R.string.migration_flow_manual_hint),
						style = MaterialTheme.typography.bodySmall)
				}
			}
		}
	}
}

@Composable
private fun MigrationCompletedResultItem(
	mangaId: Long,
	manga: Manga,
	status: MigrationBatchStatus,
	imageLoader: ImageLoader,
	onOpenTarget: (Manga) -> Unit,
) {
	val context = LocalContext.current
	val request = remember(manga.id, manga.coverUrl, manga.source) {
		manga.coverUrl?.let { ImageRequest.Builder(context).data(it).mangaSourceExtra(manga.source).build() }
	}
	Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
		Row(Modifier.fillMaxWidth().padding(9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			if (request != null) AsyncImage(
				model = request, imageLoader = imageLoader, contentDescription = manga.title,
				modifier = Modifier.width(58.dp).height(76.dp)
					.testTag("migration-completed-cover-" + mangaId)
					.clickable { onOpenTarget(manga) },
			)
			Column(Modifier.weight(1f)
				.testTag("migration-completed-title-" + mangaId)
				.clickable { onOpenTarget(manga) }) {
				Text(manga.title, style = MaterialTheme.typography.bodyMedium,
					maxLines = 2, overflow = TextOverflow.Ellipsis)
				Text(manga.source.getTitle(context).toString(), style = MaterialTheme.typography.labelSmall)
				Text(stringResource(status.labelRes()), style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.primary)
			}
		}
	}
}

@Composable
private fun ReviewCount(title: String, count: Int, modifier: Modifier = Modifier) {
	Surface(modifier = modifier, shape = MaterialTheme.shapes.medium,
		color = MaterialTheme.colorScheme.surfaceContainerLow) {
		Column(Modifier.padding(9.dp)) {
			Text(count.toString(), style = MaterialTheme.typography.titleLarge)
			Text(title, style = MaterialTheme.typography.labelSmall)
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MigrationMangaReviewItem(
	manga: Manga, imageLoader: ImageLoader, status: MigrationBatchStatus,
	targetTitle: String?, targetSource: String?, onClick: () -> Unit,
	targetManga: Manga? = null,
	onOpenTarget: (Manga) -> Unit = {},
    onOpenSource: (Manga) -> Unit = {},
	onMigrate: (() -> Unit)? = null,
	onCopy: (() -> Unit)? = null,
	onSkip: (() -> Unit)? = null,
	failureStage: MigrationFailureStage? = null,
	conflictReason: MigrationConflictReason? = null,
) {
	val context = LocalContext.current
	var menuExpanded by remember { mutableStateOf(false) }
	Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
		Row(Modifier.fillMaxWidth().padding(9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(7.dp)) {
			Column(Modifier.weight(1f).clickable(onClick = onClick)) {
				val request = remember(manga.id, manga.coverUrl, manga.source) {
					manga.coverUrl?.let {
						ImageRequest.Builder(context).data(it).mangaSourceExtra(manga.source).build()
					}
				}
                Box(Modifier.width(58.dp).height(76.dp)
                    .testTag("migration-source-cover-" + manga.id)
                    .clickable { onOpenSource(manga) }) {
                    if (request != null) AsyncImage(model = request, imageLoader = imageLoader,
                        contentDescription = manga.title, modifier = Modifier.fillMaxSize())
                }
				Text(manga.title, style = MaterialTheme.typography.bodySmall,
					maxLines = 2, overflow = TextOverflow.Ellipsis)
				Text(manga.source.getTitle(context).toString(), style = MaterialTheme.typography.labelSmall,
					maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
			Text("→", style = MaterialTheme.typography.titleMedium)
			Column(Modifier.weight(1f)) {
				if (targetManga != null) {
					val targetRequest = remember(targetManga.id, targetManga.coverUrl, targetManga.source) {
						targetManga.coverUrl?.let {
							ImageRequest.Builder(context).data(it).mangaSourceExtra(targetManga.source).build()
						}
					}
					if (targetRequest != null) AsyncImage(
						model = targetRequest, imageLoader = imageLoader,
						contentDescription = targetManga.title,
						modifier = Modifier.width(58.dp).height(76.dp)
							.testTag("migration-target-cover-" + manga.id)
							.then(if (status == MigrationBatchStatus.READY) Modifier.combinedClickable(
								onClick = { onOpenTarget(targetManga) },
								onLongClick = onClick,
							) else Modifier.clickable { onOpenTarget(targetManga) }),
					)
				}
				Column(
					(if (targetManga == null) Modifier.clickable(onClick = onClick)
					else if (status == MigrationBatchStatus.READY) Modifier.combinedClickable(
						onClick = { onOpenTarget(targetManga) },
						onLongClick = onClick,
					) else Modifier.clickable { onOpenTarget(targetManga) })
						.testTag("migration-target-title-" + manga.id)
				) {
				Text(targetManga?.title ?: targetTitle ?: when (status) {
					MigrationBatchStatus.NO_REPLACEMENT -> stringResource(R.string.migration_flow_not_found)
					MigrationBatchStatus.ERROR -> stringResource(R.string.migration_flow_failed)
					MigrationBatchStatus.INTERRUPTED -> stringResource(R.string.migration_flow_interrupted)
					else -> stringResource(R.string.migration_flow_searching)
				},
					style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
				val publicSource = targetManga?.source?.getTitle(context)?.takeIf(String::isNotBlank)
					?: targetSource?.takeUnless { it.startsWith("MIHON_") || it.startsWith("TSUKI:")
						|| it.startsWith("LN_") }
				if (publicSource != null) Text(publicSource,
					style = MaterialTheme.typography.labelSmall, maxLines = 1)
				Text(stringResource(status.labelRes()), style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.primary)
                if (status == MigrationBatchStatus.MIGRATED_PRESERVED) {
                    Text(stringResource(R.string.migration_flow_preserved),
                        style = MaterialTheme.typography.bodySmall)
                }
				if (status == MigrationBatchStatus.ERROR) {
					val explanation = when (failureStage) {
						MigrationFailureStage.DESTINATION_DETAILS ->
							stringResource(R.string.migration_flow_details_failed)
						MigrationFailureStage.DESTINATION_STORAGE ->
							stringResource(R.string.migration_flow_storage_failed)
						MigrationFailureStage.DATA_CONFLICT -> when (conflictReason) {
                            MigrationConflictReason.CHAPTER_MAPPING ->
                                stringResource(R.string.migration_flow_chapter_mapping)
                            MigrationConflictReason.DESTINATION_HISTORY ->
                                stringResource(R.string.migration_flow_history_conflict)
                            MigrationConflictReason.LIBRARY_STATE ->
                                stringResource(R.string.migration_flow_library_conflict)
                            MigrationConflictReason.READER_SETTINGS ->
                                stringResource(R.string.migration_flow_reader_conflict)
                            MigrationConflictReason.BOOKMARKS ->
                                stringResource(R.string.migration_flow_bookmark_conflict)
                            MigrationConflictReason.TRACKING_STATE ->
                                stringResource(R.string.migration_flow_tracking_conflict)
                            MigrationConflictReason.CHAPTER_NOTES ->
                                stringResource(R.string.migration_flow_notes_conflict)
                            MigrationConflictReason.OTHER_DATA, null ->
                                stringResource(R.string.migration_flow_data_conflict)
                        }
						MigrationFailureStage.DATA_TRANSFER ->
							stringResource(R.string.migration_flow_transfer_failed)
						MigrationFailureStage.UNKNOWN, null ->
							stringResource(R.string.migration_flow_unknown_failure)
					}
					Text(explanation, style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.error)
				}
				}
			}
			Box {
				TextButton(onClick = { menuExpanded = true }) { Text("⋮") }
				DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
				DropdownMenuItem(text = { Text(stringResource(R.string.migration_yokai_manual_search)) }, onClick = {
					menuExpanded = false; onClick()
				})
				if (onMigrate != null) DropdownMenuItem(text = { Text(stringResource(R.string.migration_migrate)) }, onClick = {
					menuExpanded = false; onMigrate()
				})
				if (onCopy != null) DropdownMenuItem(text = { Text(stringResource(R.string.migration_flow_copy)) }, onClick = {
					menuExpanded = false; onCopy()
				})
				if (onSkip != null) DropdownMenuItem(text = { Text(stringResource(R.string.migration_yokai_skip_manga)) }, onClick = {
					menuExpanded = false; onSkip()
				})
			}
			}
		}
	}
}

@Composable
private fun MigrationResultsStep(
	state: SourceMangaListState,
	imageLoader: ImageLoader,
	onCancel: () -> Unit,
	onReview: (Manga) -> Unit,
	onSearchAgain: () -> Unit,
) {
	val inProgress = state.ownedBatchName != null && state.batchBusy
	val completed = state.manga.filter { state.status(it.id) in setOf(
		MigrationBatchStatus.MIGRATED, MigrationBatchStatus.MIGRATED_PRESERVED, MigrationBatchStatus.COPIED) }
	val failures = state.manga.filter { state.status(it.id) == MigrationBatchStatus.ERROR }
	val unresolved = state.manga.filter { state.status(it.id) in setOf(
		MigrationBatchStatus.INTERRUPTED, MigrationBatchStatus.NEEDS_REVIEW,
		MigrationBatchStatus.INCOMPLETE, MigrationBatchStatus.NO_REPLACEMENT) }
	val skipped = state.manga.filter { state.status(it.id) == MigrationBatchStatus.SKIPPED }
	Column(Modifier.fillMaxSize()) {
		LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)) {
			item {
				Text(stringResource(R.string.migration_flow_progress_results), style = MaterialTheme.typography.titleLarge)
				if (state.failed) {
					Text(stringResource(R.string.migration_flow_job_failed),
						color = MaterialTheme.colorScheme.error,
						style = MaterialTheme.typography.bodySmall)
				}
				if (inProgress) {
					Text(stringResource(R.string.migration_flow_running_count, state.batchCompleted, state.batchTotal))
					LinearProgressIndicator(progress = {
						if (state.batchTotal == 0) 0f else
							state.batchCompleted.toFloat() / state.batchTotal.toFloat()
					}, modifier = Modifier.fillMaxWidth())
					TextButton(onClick = onCancel) { Text(stringResource(R.string.migration_batch_cancel)) }
				} else if (state.batchBusy) {
					Text(stringResource(R.string.migration_batch_other_active))
				} else if (state.scheduling) {
					LinearProgressIndicator(Modifier.fillMaxWidth())
					Text(stringResource(R.string.migration_flow_preparing))
				} else {
					Text(stringResource(R.string.migration_flow_no_active),
						style = MaterialTheme.typography.bodySmall)
				}
			}
			if (completed.isNotEmpty() || state.completedResults.isNotEmpty()) item {
				Text(stringResource(R.string.migration_flow_successful), style = MaterialTheme.typography.titleMedium)
			}
			items(completed, key = Manga::id) { manga ->
				MigrationMangaReviewItem(manga, imageLoader, state.status(manga.id),
					state.batchItems[manga.id]?.outcome?.destinationTitle,
					state.batchItems[manga.id]?.outcome?.destinationSource,
					onClick = { onReview(manga) })
			}
			items(state.completedResults, key = { "complete:" + it.mangaId + ":" + it.generation }) { result ->
				Column {
					Text(result.outcome.destinationTitle ?: stringResource(R.string.migration_flow_completed_manga),
						style = MaterialTheme.typography.bodyMedium)
					Text(stringResource(result.outcome.status.labelRes()),
                        style = MaterialTheme.typography.bodySmall)
                    if (result.outcome.status == MigrationBatchStatus.MIGRATED_PRESERVED) {
                        state.preservedSourceManga[result.mangaId]?.let { source ->
                            TextButton(onClick = { onReview(source) }) {
                                Text(stringResource(R.string.migration_flow_open_original_recovery))
                            }
                        }
                    }
				}
			}
            val preserved = state.preservedSourceManga.values.toList()
            if (preserved.isNotEmpty()) item {
                Text(stringResource(R.string.migration_flow_original_data), style = MaterialTheme.typography.titleMedium)
            }
            items(preserved, key = { "preserved:" + it.id }) { source ->
                Column {
                    Text(source.title, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.migration_flow_original_data_hint),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onReview(source) }) { Text(stringResource(R.string.migration_flow_open_original)) }
                }
            }
			if (failures.isNotEmpty()) item { Text(stringResource(R.string.migration_flow_failed_header), style = MaterialTheme.typography.titleMedium) }
			items(failures, key = Manga::id) { manga ->
				MigrationMangaReviewItem(manga, imageLoader, state.status(manga.id),
					null, null, onClick = { onReview(manga) })
			}
			if (unresolved.isNotEmpty() || state.unconfirmedResults.isNotEmpty()) item {
				Text(stringResource(R.string.migration_flow_unresolved), style = MaterialTheme.typography.titleMedium)
			}
			items(unresolved, key = Manga::id) { manga ->
				MigrationMangaReviewItem(manga, imageLoader, state.status(manga.id),
					state.batchItems[manga.id]?.outcome?.destinationTitle,
					state.batchItems[manga.id]?.outcome?.destinationSource,
					onClick = { onReview(manga) })
			}
			items(state.unconfirmedResults, key = { "unconfirmed:" + it.mangaId + ":" + it.generation }) { result ->
				Column {
					Text(result.outcome.destinationTitle ?: stringResource(R.string.migration_flow_unconfirmed))
					Text(stringResource(R.string.migration_batch_check_library),
						style = MaterialTheme.typography.bodySmall)
				}
			}
			if (skipped.isNotEmpty()) item { Text(stringResource(R.string.migration_flow_skipped), style = MaterialTheme.typography.titleMedium) }
			items(skipped, key = Manga::id) { manga ->
				MigrationMangaReviewItem(manga, imageLoader, state.status(manga.id),
					null, null, onClick = { onReview(manga) })
			}
			if (!inProgress && completed.isEmpty() && failures.isEmpty() && unresolved.isEmpty() &&
				skipped.isEmpty() && state.completedResults.isEmpty() &&
				state.unconfirmedResults.isEmpty()) {
				item { Text(stringResource(R.string.migration_flow_no_results)) }
			}
		}
		Surface(tonalElevation = 3.dp) {
			Column(Modifier.fillMaxWidth().padding(12.dp)) {
				Button(onClick = onSearchAgain, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.migration_flow_back_search))
				}
			}
		}
	}
}
