package org.koitharu.kotatsu.settings.sources.migration

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchPhase
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchScope

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
	MigrationBatchStatus.INTERRUPTED -> R.string.migration_batch_interrupted
	MigrationBatchStatus.ERROR -> R.string.migration_batch_error
}

@Composable
internal fun MigrationBatchControls(
	state: SourceMangaListState,
	onCheck: () -> Unit,
	onMigrateReady: () -> Unit,
	onCancel: () -> Unit,
	onFilter: (MigrationBatchStatus?) -> Unit,
	onSearchScope: (MigrationSearchScope) -> Unit = {},
	onDiscoveryMode: (MigrationBatchDiscoveryMode) -> Unit = {},
) {
	var expanded by remember { mutableStateOf(false) }
	val activeProgress = state.batchItems.values.firstOrNull { it.running && it.searchProgress != null }?.searchProgress
	Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(pluralStringResource(R.plurals.manga_count, state.manga.size, state.manga.size), style = MaterialTheme.typography.titleMedium)
		Text(stringResource(R.string.migration_batch_summary, state.readyCount,
			state.manga.count { state.status(it.id) == MigrationBatchStatus.NEEDS_REVIEW },
			state.manga.count { state.status(it.id) == MigrationBatchStatus.NO_REPLACEMENT }))
		Text(stringResource(R.string.migration_batch_safety), style = MaterialTheme.typography.bodySmall)

		Text(stringResource(R.string.migration_batch_mode_title), style = MaterialTheme.typography.titleSmall)
		Row(
			modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("migration-batch-mode-row"),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			FilterChip(
				selected = state.discoveryMode == MigrationBatchDiscoveryMode.CHECK_CURRENT,
				onClick = { onDiscoveryMode(MigrationBatchDiscoveryMode.CHECK_CURRENT) },
				enabled = !state.batchBusy && !state.scheduling,
				label = { Text(stringResource(R.string.migration_batch_mode_check)) },
				modifier = Modifier.testTag("migration-batch-mode-check"),
			)
			FilterChip(
				selected = state.discoveryMode == MigrationBatchDiscoveryMode.DIRECT_SEARCH,
				onClick = { onDiscoveryMode(MigrationBatchDiscoveryMode.DIRECT_SEARCH) },
				enabled = !state.batchBusy && !state.scheduling,
				label = { Text(stringResource(R.string.migration_batch_mode_direct)) },
				modifier = Modifier.testTag("migration-batch-mode-direct"),
			)
		}
		Text(
			stringResource(
				if (state.discoveryMode == MigrationBatchDiscoveryMode.CHECK_CURRENT) {
					R.string.migration_batch_mode_check_description
				} else {
					R.string.migration_batch_mode_direct_description
				},
			),
			style = MaterialTheme.typography.bodySmall,
		)

		Text(stringResource(R.string.migration_search_scope_title), style = MaterialTheme.typography.titleSmall)
		Row(
			modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("migration-batch-scope-row"),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			for (scope in MigrationSearchScope.entries) {
				FilterChip(
					selected = state.searchScope == scope,
					onClick = { onSearchScope(scope) },
					enabled = !state.batchBusy && !state.scheduling,
					label = { Text(stringResource(scope.labelRes())) },
					modifier = Modifier.testTag("migration-batch-scope-${scope.name.lowercase()}"),
				)
			}
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
			LinearProgressIndicator(progress = { state.batchCompleted.toFloat() / state.batchTotal }, modifier = Modifier.fillMaxWidth())
			Text(stringResource(R.string.migration_batch_progress, state.batchCompleted, state.batchTotal))
		}
		if (state.migratedCount > 0) Text(stringResource(R.string.migration_batch_done, state.migratedCount))
		if (state.unconfirmedCount > 0) Text(stringResource(R.string.migration_batch_check_library))
		if (state.batchBusy && state.ownedBatchName == null) Text(stringResource(R.string.migration_batch_other_active))
		Button(
			onClick = onCheck,
			enabled = !state.batchBusy && !state.scheduling && state.manga.isNotEmpty(),
			modifier = Modifier.fillMaxWidth().testTag("migration-batch-check"),
		) {
			Text(stringResource(
				if (state.discoveryMode == MigrationBatchDiscoveryMode.CHECK_CURRENT) {
					R.string.migration_batch_check
				} else {
					R.string.migration_batch_direct
				},
			))
		}
		if (state.readyCount > 0) Button(
			onClick = onMigrateReady,
			enabled = !state.batchBusy && !state.scheduling,
			modifier = Modifier.fillMaxWidth().testTag("migration-batch-execute"),
		) {
			Text(pluralStringResource(R.plurals.migration_batch_migrate_ready, state.readyCount, state.readyCount))
		}
		if (state.ownedBatchName != null) TextButton(onClick = onCancel, modifier = Modifier.testTag("migration-batch-cancel")) {
			Text(stringResource(R.string.migration_batch_cancel))
		}
		Box {
			TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("migration-batch-filter")) {
				Text(state.filter?.let { stringResource(it.labelRes()) } ?: stringResource(R.string.migration_batch_all))
			}
			DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
				DropdownMenuItem(text = { Text(stringResource(R.string.migration_batch_all)) }, onClick = { expanded = false; onFilter(null) })
				for (status in MigrationBatchStatus.entries) {
					val count = state.manga.count { state.status(it.id) == status }
					if (count == 0) continue
					DropdownMenuItem(
						text = { Text(stringResource(R.string.migration_batch_filter_count, stringResource(status.labelRes()), count)) },
						onClick = { expanded = false; onFilter(status) },
					)
				}
			}
		}
	}
}
