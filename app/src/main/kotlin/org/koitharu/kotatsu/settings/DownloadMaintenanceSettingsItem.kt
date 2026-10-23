package org.koitharu.kotatsu.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenancePhase
import org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenanceReason
import org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenanceState
import org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenanceStatus
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem

@Composable
internal fun DownloadMaintenanceSettingsItem(stateFlow: StateFlow<DownloadMaintenanceState>, shape: Shape, onStart: () -> Unit) {
	val state by stateFlow.collectAsState()
	var showDetails by rememberSaveable { mutableStateOf(false) }
	val phase = stringResource(when (state.phase) {
		DownloadMaintenancePhase.PREPARING -> R.string.download_maintenance_preparing
		DownloadMaintenancePhase.SCANNING -> R.string.download_maintenance_scanning
		DownloadMaintenancePhase.IDENTIFYING -> R.string.download_maintenance_identifying
		DownloadMaintenancePhase.VERIFYING -> R.string.download_maintenance_verifying
		DownloadMaintenancePhase.REPAIRING -> R.string.download_maintenance_repairing
		DownloadMaintenancePhase.REFRESHING -> R.string.download_maintenance_refreshing
		DownloadMaintenancePhase.FINALIZING -> R.string.download_maintenance_finalizing
		DownloadMaintenancePhase.COMPLETED -> R.string.download_maintenance_completed
	})
	Column {
		ActionSettingsItem(
			title = stringResource(R.string.download_maintenance_title),
			modifier = Modifier.semantics(mergeDescendants = true) { if (state.running) disabled() },
			subtitle = if (state.running) phase else stringResource(R.string.download_maintenance_description),
			icon = R.drawable.ic_sync,
			shape = shape,
			enabled = !state.running,
			onClick = onStart,
		)
		Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
			if (state.running) {
				LinearProgressIndicator(Modifier.fillMaxWidth())
				Text(if (state.total == null) phase else stringResource(R.string.download_maintenance_progress, state.scanned, state.total ?: 0), style = MaterialTheme.typography.bodySmall)
			}
			state.result?.let { result ->
				Text(stringResource(R.string.download_maintenance_checked, result.checked), style = MaterialTheme.typography.titleSmall)
				Text(stringResource(R.string.download_maintenance_summary, result.repaired, result.healthy, result.attention, result.failed), style = MaterialTheme.typography.bodySmall)
				TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.download_maintenance_details)) }
			}
			Text(stringResource(R.string.download_maintenance_reassurance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
	if (showDetails && state.result != null) {
		val details = state.result!!.items.filter { it.status != DownloadMaintenanceStatus.HEALTHY && it.status != DownloadMaintenanceStatus.REPAIRED }
		AlertDialog(
			onDismissRequest = { showDetails = false },
			title = { Text(stringResource(R.string.download_maintenance_details)) },
			text = {
				if (details.isEmpty()) Text(stringResource(R.string.download_maintenance_no_problems))
				else LazyColumn(Modifier.heightIn(max = 420.dp)) {
					items(details) { item ->
						Column(Modifier.padding(vertical = 8.dp)) {
							Text(if (item.isPrivate) stringResource(R.string.download_maintenance_private_item) else item.path, style = MaterialTheme.typography.titleSmall)
							Text(stringResource(item.reason.messageRes()), style = MaterialTheme.typography.bodyMedium)
							if (item.physicalChapters > 0) Text(stringResource(R.string.download_maintenance_chapter_counts, item.recognizedChapters, item.physicalChapters), style = MaterialTheme.typography.bodySmall)
						}
					}
				}
			},
			confirmButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(android.R.string.ok)) } },
		)
	}
}

private fun DownloadMaintenanceReason.messageRes(): Int = when (this) {
	DownloadMaintenanceReason.VERIFIED -> R.string.download_maintenance_verified
	DownloadMaintenanceReason.LOCAL_CONTENT -> R.string.download_maintenance_local_content
	DownloadMaintenanceReason.IDENTITY_UNKNOWN -> R.string.download_maintenance_identity_unknown
	DownloadMaintenanceReason.SOURCE_MISMATCH -> R.string.download_maintenance_source_mismatch
	DownloadMaintenanceReason.DUPLICATE_CONTAINER -> R.string.download_maintenance_duplicate_container
	DownloadMaintenanceReason.PARTIAL_MATCH -> R.string.download_maintenance_partial_match
	DownloadMaintenanceReason.CORRUPT_INDEX -> R.string.download_maintenance_corrupt_index
	DownloadMaintenanceReason.UNSUPPORTED_ARTIFACT -> R.string.download_maintenance_unsupported
	DownloadMaintenanceReason.STORAGE_UNAVAILABLE -> R.string.download_maintenance_storage_unavailable
	DownloadMaintenanceReason.PROVIDER_UNAVAILABLE -> R.string.download_maintenance_provider_unavailable
	DownloadMaintenanceReason.METADATA_UNAVAILABLE -> R.string.download_maintenance_metadata_unavailable
	DownloadMaintenanceReason.OWNERSHIP_UNKNOWN -> R.string.download_maintenance_ownership_unknown
	DownloadMaintenanceReason.STORAGE_CHANGED -> R.string.download_maintenance_storage_changed
	DownloadMaintenanceReason.CANCELLED -> R.string.download_maintenance_cancelled
	DownloadMaintenanceReason.IO_ERROR -> R.string.download_maintenance_io_error
}
