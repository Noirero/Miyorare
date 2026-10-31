package org.koitharu.kotatsu.settings.sources.migration

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchPhase
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.explore.ui.adapter.ExploreSourceOrigin
import org.koitharu.kotatsu.explore.ui.adapter.exploreSourceOrigin
import org.koitharu.kotatsu.parsers.model.MangaSource

@StringRes
internal fun MigrationBatchStatus.labelRes(): Int = when (this) {
	MigrationBatchStatus.UNCHECKED -> R.string.migration_batch_unchecked
	MigrationBatchStatus.QUEUED -> R.string.migration_batch_queued
	MigrationBatchStatus.CHECKING -> R.string.migration_batch_checking
	MigrationBatchStatus.MIGRATING -> R.string.migration_batch_migrating
	MigrationBatchStatus.READY -> R.string.migration_batch_ready
	MigrationBatchStatus.NEEDS_REVIEW -> R.string.migration_batch_review
	MigrationBatchStatus.NO_REPLACEMENT -> R.string.migration_batch_no_replacement
	MigrationBatchStatus.INCOMPLETE -> R.string.migration_batch_incomplete
	MigrationBatchStatus.HEALTHY -> R.string.migration_batch_healthy
	MigrationBatchStatus.MIGRATED -> R.string.migration_completed
	MigrationBatchStatus.COPIED -> R.string.migration_batch_copied
	MigrationBatchStatus.SKIPPED -> R.string.migration_batch_skipped
	MigrationBatchStatus.INTERRUPTED -> R.string.migration_batch_interrupted
	MigrationBatchStatus.ERROR -> R.string.migration_batch_error
}

@Composable
internal fun MigrationBatchControls(
	state: SourceMangaListState,
	onSearch: () -> Unit,
	onMigrateReady: () -> Unit,
	onCopyReady: () -> Unit,
	onCancel: () -> Unit,
	onFilter: (MigrationBatchStatus?) -> Unit,
	onToggleSource: (String) -> Unit,
	onSelectAllSources: () -> Unit,
	onClearSources: () -> Unit,
	onMoveSource: (String, Int) -> Unit,
	onStrategy: (MigrationSourceStrategy) -> Unit,
	onExtraSearch: (String) -> Unit,
	onSkipSetup: (Boolean) -> Unit,
	onRetrySources: () -> Unit,
) {
	var filterExpanded by remember { mutableStateOf(false) }
	var pendingAction by remember { mutableStateOf<MigrationBatchAction?>(null) }
	val context = LocalContext.current
	val activeProgress = state.batchItems.values.firstOrNull { it.running && it.searchProgress != null }?.searchProgress
	val selectedSet = state.selectedSourceNames.toHashSet()
	val sourceByName = state.availableSources.associateBy(MangaSource::name)
	val orderedSources = buildList {
		state.selectedSourceNames.mapNotNullTo(this) { sourceByName[it] }
		state.availableSources
			.filterNot { it.name in selectedSet }
			.sortedBy { it.getTitle(context).toString() }
			.forEach(::add)
	}
	val availableOrigins = SOURCE_ORIGIN_ORDER.filter { origin ->
		state.availableSources.any { it.exploreSourceOrigin() == origin }
	}
	val defaultOrigin = when {
		ExploreSourceOrigin.MIYORARE_PACK in availableOrigins -> ExploreSourceOrigin.MIYORARE_PACK
		else -> availableOrigins.firstOrNull()
	}
	var sourceOriginFilter by remember(state.availableSources.map(MangaSource::name)) {
		mutableStateOf(defaultOrigin)
	}
	val visibleSources = orderedSources.filter { source ->
		sourceOriginFilter == null || source.exploreSourceOrigin() == sourceOriginFilter
	}
	val targetCount = state.selectedIds.size.takeIf { it > 0 } ?: state.manga.size
	val controlsEnabled = !state.batchBusy && !state.scheduling

	Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(pluralStringResource(R.plurals.manga_count, state.manga.size, state.manga.size), style = MaterialTheme.typography.titleMedium)
		Text(
			stringResource(
				R.string.migration_batch_summary,
				state.readyCount,
				state.manga.count { state.status(it.id) == MigrationBatchStatus.NEEDS_REVIEW },
				state.manga.count { state.status(it.id) == MigrationBatchStatus.NO_REPLACEMENT },
			),
			style = MaterialTheme.typography.bodyMedium,
		)
		Text(stringResource(R.string.migration_preserve_all_fixed), style = MaterialTheme.typography.bodySmall)

		Text(stringResource(R.string.migration_yokai_source_order_title), style = MaterialTheme.typography.titleSmall)
		Text(stringResource(R.string.migration_yokai_source_order_description), style = MaterialTheme.typography.bodySmall)

		when {
			state.sourceSelectionLoading -> Row(
				modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
				Text(stringResource(R.string.migration_loading_sources))
			}
			state.sourceSelectionFailed -> {
				Text(stringResource(R.string.migration_source_list_failed), color = MaterialTheme.colorScheme.error)
				TextButton(onClick = onRetrySources) { Text(stringResource(R.string.migration_retry)) }
			}
			state.availableSources.isEmpty() -> Text(stringResource(R.string.migration_no_sources_available))
			else -> {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					TextButton(
						onClick = {
							if (sourceOriginFilter == null) {
								onSelectAllSources()
							} else {
								visibleSources
									.asSequence()
									.map(MangaSource::name)
									.filterNot(selectedSet::contains)
									.forEach(onToggleSource)
							}
						},
						enabled = controlsEnabled,
					) {
						Text(stringResource(R.string.migration_select_all_visible))
					}
					TextButton(
						onClick = {
							if (sourceOriginFilter == null) {
								onClearSources()
							} else {
								visibleSources
									.asSequence()
									.map(MangaSource::name)
									.filter(selectedSet::contains)
									.forEach(onToggleSource)
							}
						},
						enabled = controlsEnabled,
					) {
						Text(stringResource(R.string.migration_deselect_all_visible))
					}
				}
				Text(stringResource(R.string.migration_sources_selected, state.selectedSourceNames.size), style = MaterialTheme.typography.bodySmall)

				if (state.selectedSourceNames.isNotEmpty()) {
					LazyColumn(
						modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).testTag("migration-selected-source-order"),
						verticalArrangement = Arrangement.spacedBy(2.dp),
					) {
						items(state.selectedSourceNames, key = { "selected:$it" }) { sourceName ->
							val source = sourceByName[sourceName]
							if (source != null) {
								val selectedIndex = state.selectedSourceNames.indexOf(sourceName)
								Row(
									modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
									verticalAlignment = Alignment.CenterVertically,
								) {
									Text(
										text = "${selectedIndex + 1}. ${source.getTitle(context)}",
										modifier = Modifier.weight(1f),
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
									)
									TextButton(
										onClick = { onMoveSource(sourceName, -1) },
										enabled = controlsEnabled && selectedIndex > 0,
										contentPadding = PaddingValues(horizontal = 8.dp),
									) { Text("↑") }
									TextButton(
										onClick = { onMoveSource(sourceName, 1) },
										enabled = controlsEnabled && selectedIndex < state.selectedSourceNames.lastIndex,
										contentPadding = PaddingValues(horizontal = 8.dp),
									) { Text("↓") }
								}
							}
						}
					}
				}

				Row(
					modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("migration-source-origin-row"),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					FilterChip(
						selected = sourceOriginFilter == null,
						onClick = { sourceOriginFilter = null },
						enabled = controlsEnabled,
						label = {
							Text(
								stringResource(
									R.string.migration_scope_with_count,
									stringResource(R.string.migration_scope_all),
									state.availableSources.size,
								),
							)
						},
					)
					for (origin in availableOrigins) {
						val count = state.availableSources.count { it.exploreSourceOrigin() == origin }
						FilterChip(
							selected = sourceOriginFilter == origin,
							onClick = { sourceOriginFilter = origin },
							enabled = controlsEnabled,
							label = {
								Text(
									stringResource(
										R.string.migration_scope_with_count,
										stringResource(origin.migrationTitleRes()),
										count,
									),
								)
							},
							modifier = Modifier.testTag("migration-source-origin-${origin.name.lowercase()}"),
						)
					}
				}

				if (visibleSources.isEmpty()) {
					Text(stringResource(R.string.migration_scope_empty), style = MaterialTheme.typography.bodySmall)
				} else {
					LazyColumn(
						modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("migration-source-picker"),
						verticalArrangement = Arrangement.spacedBy(2.dp),
					) {
						items(visibleSources, key = MangaSource::name) { source ->
							val selected = source.name in selectedSet
							Row(
								modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Checkbox(
									checked = selected,
									onCheckedChange = { onToggleSource(source.name) },
									enabled = controlsEnabled,
								)
								Text(
									text = source.getTitle(context).toString(),
									modifier = Modifier.weight(1f),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
						}
					}
				}
			}
		}

		Text(stringResource(R.string.migration_yokai_strategy_title), style = MaterialTheme.typography.titleSmall)
		MigrationStrategyRow(
			selected = state.sourceStrategy == MigrationSourceStrategy.FIRST_MATCH,
			title = stringResource(R.string.migration_yokai_strategy_first),
			description = stringResource(R.string.migration_yokai_strategy_first_description),
			enabled = controlsEnabled,
			onClick = { onStrategy(MigrationSourceStrategy.FIRST_MATCH) },
		)
		MigrationStrategyRow(
			selected = state.sourceStrategy == MigrationSourceStrategy.MOST_CHAPTERS,
			title = stringResource(R.string.migration_yokai_strategy_most),
			description = stringResource(R.string.migration_yokai_strategy_most_description),
			enabled = controlsEnabled,
			onClick = { onStrategy(MigrationSourceStrategy.MOST_CHAPTERS) },
		)

		OutlinedTextField(
			value = state.extraSearchParams,
			onValueChange = onExtraSearch,
			enabled = controlsEnabled,
			modifier = Modifier.fillMaxWidth().testTag("migration-extra-search"),
			label = { Text(stringResource(R.string.migration_yokai_extra_search)) },
			supportingText = { Text(stringResource(R.string.migration_yokai_extra_search_hint)) },
			singleLine = true,
		)

		Row(verticalAlignment = Alignment.CenterVertically) {
			Checkbox(checked = state.skipSetup, onCheckedChange = onSkipSetup, enabled = controlsEnabled)
			Column(Modifier.weight(1f)) {
				Text(stringResource(R.string.migration_yokai_skip_setup))
				Text(stringResource(R.string.migration_yokai_skip_setup_description), style = MaterialTheme.typography.bodySmall)
			}
		}

		if (state.sourcePoolFailureCount > 0) {
			Text(stringResource(R.string.migration_partial_provider_pool), style = MaterialTheme.typography.bodySmall)
		}

		activeProgress?.let { progress ->
			val text = when (progress.phase) {
				MigrationSearchPhase.CHECKING_SOURCE -> stringResource(R.string.migration_batch_checking_current)
				MigrationSearchPhase.SEARCHING_SOURCES -> if (progress.totalSources > 0) {
					stringResource(R.string.migration_batch_searching_progress, progress.completedSources, progress.totalSources)
				} else stringResource(R.string.migration_batch_searching)
				MigrationSearchPhase.VERIFYING_CANDIDATES -> stringResource(R.string.migration_batch_verifying, progress.candidateCount)
			}
			Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("migration-batch-live-progress"))
		}
		if (state.batchTotal > 0) {
			LinearProgressIndicator(
				progress = { state.batchCompleted.toFloat() / state.batchTotal },
				modifier = Modifier.fillMaxWidth(),
			)
			Text(stringResource(R.string.migration_batch_progress, state.batchCompleted, state.batchTotal))
		}
		if (state.migratedCount > 0) Text(stringResource(R.string.migration_batch_done, state.migratedCount))
		if (state.unconfirmedCount > 0) Text(stringResource(R.string.migration_batch_check_library))
		if (state.batchBusy && state.ownedBatchName == null) Text(stringResource(R.string.migration_batch_other_active))

		Button(
			onClick = onSearch,
			enabled = controlsEnabled && targetCount > 0 && state.selectedSourceNames.isNotEmpty() && !state.sourceSelectionLoading,
			modifier = Modifier.fillMaxWidth().testTag("migration-batch-search"),
		) {
			Text(
				if (targetCount == 1) stringResource(R.string.migration_yokai_search_selected)
				else stringResource(R.string.migration_yokai_search_selected_count, targetCount),
			)
		}
		if (state.readyCount > 0) {
			Button(
				onClick = { pendingAction = MigrationBatchAction.MIGRATE },
				enabled = controlsEnabled,
				modifier = Modifier.fillMaxWidth().testTag("migration-batch-migrate"),
			) {
				Text(pluralStringResource(R.plurals.migration_batch_migrate_ready, state.readyCount, state.readyCount))
			}
			OutlinedButton(
				onClick = { pendingAction = MigrationBatchAction.COPY },
				enabled = controlsEnabled,
				modifier = Modifier.fillMaxWidth().testTag("migration-batch-copy"),
			) {
				Text(pluralStringResource(R.plurals.migration_batch_copy_ready, state.readyCount, state.readyCount))
			}
		}
		if (state.ownedBatchName != null) TextButton(onClick = onCancel, modifier = Modifier.testTag("migration-batch-cancel")) {
			Text(stringResource(R.string.migration_batch_cancel))
		}

		Box {
			TextButton(onClick = { filterExpanded = true }, modifier = Modifier.testTag("migration-batch-filter")) {
				Text(state.filter?.let { stringResource(it.labelRes()) } ?: stringResource(R.string.migration_batch_all))
			}
			DropdownMenu(expanded = filterExpanded, onDismissRequest = { filterExpanded = false }) {
				DropdownMenuItem(text = { Text(stringResource(R.string.migration_batch_all)) }, onClick = { filterExpanded = false; onFilter(null) })
				for (status in MigrationBatchStatus.entries) {
					val count = state.manga.count { state.status(it.id) == status }
					if (count == 0) continue
					DropdownMenuItem(
						text = { Text(stringResource(R.string.migration_batch_filter_count, stringResource(status.labelRes()), count)) },
						onClick = { filterExpanded = false; onFilter(status) },
					)
				}
			}
		}
	}
	pendingAction?.let { action ->
		AlertDialog(
			onDismissRequest = { pendingAction = null },
			title = { Text(stringResource(R.string.manga_migration)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(pluralStringResource(
						if (action == MigrationBatchAction.MIGRATE) R.plurals.migration_batch_migrate_ready
						else R.plurals.migration_batch_copy_ready,
						state.readyCount, state.readyCount,
					))
					Text(stringResource(
						R.string.migration_batch_summary,
						state.readyCount,
						state.manga.count { state.status(it.id) == MigrationBatchStatus.NEEDS_REVIEW },
						state.manga.count { state.status(it.id) == MigrationBatchStatus.NO_REPLACEMENT },
					))
				}
			},
			confirmButton = {
				TextButton(onClick = {
					pendingAction = null
					if (action == MigrationBatchAction.MIGRATE) onMigrateReady() else onCopyReady()
				}) {
					Text(pluralStringResource(
						if (action == MigrationBatchAction.MIGRATE) R.plurals.migration_batch_migrate_ready
						else R.plurals.migration_batch_copy_ready,
						state.readyCount, state.readyCount,
					))
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingAction = null }) { Text(stringResource(android.R.string.cancel)) }
			},
		)
	}
}

@StringRes
private fun ExploreSourceOrigin.migrationTitleRes(): Int = when (this) {
	ExploreSourceOrigin.MIYORARE_PACK -> R.string.migration_scope_miyorare
	ExploreSourceOrigin.EXTENSION_STORE -> R.string.migration_scope_store
	ExploreSourceOrigin.UMA -> R.string.migration_scope_uma
	ExploreSourceOrigin.GEKKOUSHI -> R.string.migration_scope_gekkoushi
	ExploreSourceOrigin.LNREADER -> R.string.explore_source_origin_lnreader
	ExploreSourceOrigin.CUSTOM_PLUGIN -> R.string.explore_source_origin_custom
}

private val SOURCE_ORIGIN_ORDER = listOf(
	ExploreSourceOrigin.MIYORARE_PACK,
	ExploreSourceOrigin.EXTENSION_STORE,
	ExploreSourceOrigin.UMA,
	ExploreSourceOrigin.GEKKOUSHI,
	ExploreSourceOrigin.LNREADER,
	ExploreSourceOrigin.CUSTOM_PLUGIN,
)

@Composable
private fun MigrationStrategyRow(
	selected: Boolean,
	title: String,
	description: String,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.Top,
	) {
		RadioButton(selected = selected, onClick = onClick, enabled = enabled)
		Column(Modifier.weight(1f).padding(top = 10.dp)) {
			Text(title, style = MaterialTheme.typography.bodyLarge)
			Text(description, style = MaterialTheme.typography.bodySmall)
		}
	}
}
