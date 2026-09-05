package org.koitharu.kotatsu.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.BackupOperationTracker
import org.koitharu.kotatsu.backup.MihonBackupExporter
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.MihonBackupManager.Options
import org.koitharu.kotatsu.backup.MihonBackupManager.RestoreReport
import org.koitharu.kotatsu.backup.MihonFavouriteRestoreRepair
import org.koitharu.kotatsu.backup.local.domain.BackupUtils
import org.koitharu.kotatsu.backup.local.ui.backup.BackupService
import org.koitharu.kotatsu.backup.local.ui.periodical.PeriodicalBackupSettingsFragment
import org.koitharu.kotatsu.backup.local.ui.restore.RestoreDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.core.util.progress.Progress
import org.koitharu.kotatsu.kotatsumigration.domain.KotatsuMigrationManager
import org.koitharu.kotatsu.kotatsumigration.domain.MigrationState
import org.koitharu.kotatsu.kotatsumigration.ui.KotatsuMigrationService
import org.koitharu.kotatsu.kotatsumigration.ui.showExtensionInstallPromptDialog
import org.koitharu.kotatsu.kotatsumigration.ui.showKotatsuMigrationCompleteDialog
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import java.text.NumberFormat
import javax.inject.Inject

@AndroidEntryPoint
class BackupSettingsFragment : BaseComposeSettingsFragment(R.string.backup_restore) {

	@Inject
	lateinit var backupManager: MihonBackupManager

	@Inject
	lateinit var mihonFavouriteRestoreRepair: MihonFavouriteRestoreRepair

	@Inject
	lateinit var migrationManager: KotatsuMigrationManager

	@Inject
	lateinit var mihonExporter: MihonBackupExporter

	private val restoreMihonBackupLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			runMihonRestoreJob(uri, options = Options())
		}
	}

	private val createLocalBackupLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument(BackupUtils.MIME_TYPE),
	) { uri ->
		if (uri != null && BackupService.start(requireContext(), uri)) {
			Toast.makeText(requireContext(), R.string.creating_backup, Toast.LENGTH_SHORT).show()
		}
	}

	private val exportMihonBackupLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument(MihonBackupExporter.MIME_TYPE),
	) { uri ->
		if (uri != null) {
			runMihonExportJob(uri)
		}
	}

	private val restoreLocalBackupLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			RestoreDialogFragment.show(parentFragmentManager, uri)
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val migrationState by migrationManager.state.collectAsState()
				val operationState by BackupOperationTracker.state.collectAsState()
				LaunchedEffect(operationState) {
					(operationState as? BackupOperationTracker.State.Finished)?.let(::showOperationResultDialog)
				}
				val migrationSubtitle = when (val s = migrationState) {
					is MigrationState.Running -> stringResource(
						R.string.kotatsu_migration_progress,
						s.done,
						s.total,
					)

					is MigrationState.Finished -> stringResource(
						R.string.kotatsu_migration_result,
						s.summary.converted,
						s.summary.total,
					)

					MigrationState.Idle -> stringResource(R.string.migrate_from_kotatsu_summary)
				}
				BackupScreen(
					operationState = operationState,
					onCreateBackup = {
						createLocalBackupLauncher.launch(
							BackupUtils.generateFileName(requireContext()),
						)
					},
					onRestoreLocal = {
						restoreLocalBackupLauncher.launch(
							arrayOf(BackupUtils.MIME_TYPE, "application/*", "*/*"),
						)
					},
					onOpenPeriodic = {
						(activity as? SettingsActivity)?.openFragment(
							PeriodicalBackupSettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					onRestoreFromTachiyomi = {
						restoreMihonBackupLauncher.launch(arrayOf("application/*", "*/*"))
					},
					migrationSubtitle = migrationSubtitle,
					onMigrateFromKotatsu = ::confirmAndStartKotatsuMigration,
					onExportToMihon = {
						exportMihonBackupLauncher.launch(MihonBackupExporter.generateFileName())
					},
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		migrationManager.onStarted.observeEvent(viewLifecycleOwner) {
			Toast.makeText(requireContext(), R.string.kotatsu_migration_started, Toast.LENGTH_SHORT).show()
		}
		migrationManager.onCompleted.observeEvent(viewLifecycleOwner) { summary ->
			requireContext().showKotatsuMigrationCompleteDialog(summary)
		}
	}

	private fun confirmAndStartKotatsuMigration() {
		if (migrationManager.isRunning) {
			Toast.makeText(requireContext(), R.string.kotatsu_migration_running, Toast.LENGTH_SHORT).show()
			return
		}
		buildAlertDialog(requireContext()) {
			setTitle(R.string.migrate_from_kotatsu)
			setMessage(R.string.migrate_from_kotatsu_confirm)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate_from_kotatsu) { _, _ ->
				if (KotatsuMigrationService.start(requireContext())) {
					Toast.makeText(requireContext(), R.string.kotatsu_migration_running, Toast.LENGTH_SHORT).show()
				}
			}
		}.show()
	}

	private fun runMihonExportJob(uri: Uri) {
		val appContext = requireContext().applicationContext
		runCatching {
			appContext.contentResolver.takePersistableUriPermission(
				uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
			)
		}
		BackupOperationTracker.start(
			BackupOperationTracker.Kind.MIHON_EXPORT,
			R.string.backup_operation_exporting_mihon,
		)
		processLifecycleScope.launch(Dispatchers.Main.immediate) {
			try {
				val report = mihonExporter.export(uri)
				BackupOperationTracker.success(
					BackupOperationTracker.Kind.MIHON_EXPORT,
					appContext.getString(
						R.string.backup_operation_exported_count,
						report.exportedCount,
						report.skippedCount,
					),
				)
			} catch (e: CancellationException) {
				BackupOperationTracker.cancelled(
					BackupOperationTracker.Kind.MIHON_EXPORT,
					appContext.getString(R.string.backup_operation_cancelled_by_user),
				)
			} catch (e: Throwable) {
				BackupOperationTracker.failed(BackupOperationTracker.Kind.MIHON_EXPORT, e)
			}
		}
	}

	private fun runMihonRestoreJob(uri: Uri, options: Options) {
		val appContext = requireContext().applicationContext
		runCatching {
			appContext.contentResolver.takePersistableUriPermission(
				uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION,
			)
		}

		BackupOperationTracker.start(
			BackupOperationTracker.Kind.MIHON_RESTORE,
			R.string.backup_operation_restoring,
		)
		processLifecycleScope.launch(Dispatchers.Main.immediate) {
			var restoreReport: RestoreReport? = null
			try {
				BackupOperationTracker.updateStage(
					BackupOperationTracker.Kind.MIHON_RESTORE,
					R.string.backup_operation_restoring,
					Progress(1, 2),
				)
				restoreReport = backupManager.restoreBackup(uri, options)
				if (options.libraryEntries) {
					BackupOperationTracker.updateStage(
						BackupOperationTracker.Kind.MIHON_RESTORE,
						R.string.backup_operation_verifying_favourites,
						Progress(2, 2),
					)
					mihonFavouriteRestoreRepair.repair(uri)
				}
				val report = restoreReport
				BackupOperationTracker.success(
					BackupOperationTracker.Kind.MIHON_RESTORE,
					report?.let {
						appContext.getString(R.string.backup_operation_restored_count, it.restoredMangaCount)
					},
				)
				report?.let {
					val activeActivity = activity?.takeIf {
						!it.isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
					}
					activeActivity?.showExtensionInstallPromptDialog(it.missingSources)
					showRestoreNotification(appContext, it)
				}
			} catch (e: CancellationException) {
				// The restore is process-scoped; leaving this Settings screen does not cancel it.
				BackupOperationTracker.cancelled(BackupOperationTracker.Kind.MIHON_RESTORE)
			} catch (e: Throwable) {
				BackupOperationTracker.failed(BackupOperationTracker.Kind.MIHON_RESTORE, e)
			}
		}
	}

	private fun showOperationResultDialog(state: BackupOperationTracker.State.Finished) {
		if (!isAdded || activity?.isFinishing != false) return
		val operationTitle = getString(state.kind.titleRes)
		val stage = getString(state.stageRes)
		val titleRes = when (state.outcome) {
			BackupOperationTracker.Outcome.SUCCESS -> R.string.backup_operation_finished_title
			BackupOperationTracker.Outcome.CANCELLED -> R.string.backup_operation_cancelled_title
			BackupOperationTracker.Outcome.FAILED -> R.string.backup_operation_failed_title
		}
		val message = buildString {
			append(
				getString(
					when (state.outcome) {
						BackupOperationTracker.Outcome.SUCCESS -> R.string.backup_operation_success_message
						BackupOperationTracker.Outcome.CANCELLED -> R.string.backup_operation_cancelled_message
						BackupOperationTracker.Outcome.FAILED -> R.string.backup_operation_failed_message
					},
					operationTitle,
				),
			)
			state.details?.takeIf { it.isNotBlank() }?.let {
				append("\n\n")
				append(it)
			}
			if (state.outcome != BackupOperationTracker.Outcome.SUCCESS) {
				append("\n\n")
				append(getString(R.string.backup_operation_stage_label, stage))
			}
			val error = listOfNotNull(state.errorType, state.errorMessage)
				.distinct()
				.joinToString(": ")
			if (error.isNotBlank()) {
				append('\n')
				append(getString(R.string.backup_operation_error_label, error))
			}
			state.errorLocation?.let {
				append('\n')
				append(getString(R.string.backup_operation_error_location_label, it))
			}
		}
		buildAlertDialog(requireContext()) {
			setTitle(titleRes)
			setMessage(message)
			setPositiveButton(android.R.string.ok) { _, _ ->
				BackupOperationTracker.acknowledge(state.id)
			}
			setOnCancelListener {
				BackupOperationTracker.acknowledge(state.id)
			}
		}.show()
	}

	private fun showRestoreNotification(ctx: Context, report: RestoreReport) {
		val manager = NotificationManagerCompat.from(ctx)
		val channel = NotificationChannelCompat.Builder(
			RESTORE_CHANNEL_ID,
			NotificationManagerCompat.IMPORTANCE_DEFAULT,
		)
			.setName(ctx.getString(R.string.backup_restore))
			.setShowBadge(false)
			.build()
		manager.createNotificationChannel(channel)
		if (!ctx.checkNotificationPermission(RESTORE_CHANNEL_ID)) return

		val details = buildString {
			append(ctx.getString(R.string.restore_report_restored_simple, report.restoredMangaCount))
			if (report.missingSources.isNotEmpty()) {
				append('\n')
				append(report.missingSources.joinToString())
			}
		}
		val notification = NotificationCompat.Builder(ctx, RESTORE_CHANNEL_ID)
			.setSmallIcon(R.drawable.general_notification)
			.setContentTitle(ctx.getString(R.string.data_restored_success))
			.setContentText(details)
			.setStyle(NotificationCompat.BigTextStyle().bigText(details))
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setSilent(true)
			.build()
		manager.notify(RESTORE_NOTIFICATION_ID, notification)
	}

	companion object {
		private const val RESTORE_CHANNEL_ID = "backup_restore"
		private const val RESTORE_NOTIFICATION_ID = 7002
	}
}

@Composable
private fun BackupScreen(
	operationState: BackupOperationTracker.State,
	onCreateBackup: () -> Unit,
	onRestoreLocal: () -> Unit,
	onOpenPeriodic: () -> Unit,
	onRestoreFromTachiyomi: () -> Unit,
	migrationSubtitle: String,
	onMigrateFromKotatsu: () -> Unit,
	onExportToMihon: () -> Unit,
) {
	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.backup_restore)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.create_backup),
						subtitle = stringResource(R.string.create_backup_summary),
						icon = R.drawable.ic_save,
						shape = pos.shape,
						onClick = onCreateBackup,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.restore_backup),
						subtitle = stringResource(R.string.restore_summary),
						icon = R.drawable.ic_revert,
						shape = pos.shape,
						onClick = onRestoreLocal,
					)
				}
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.periodic_backups),
						subtitle = stringResource(R.string.periodic_backups_summary),
						icon = R.drawable.ic_backup_restore,
						shape = pos.shape,
						onClick = onOpenPeriodic,
					)
				}
			}
		}

		(operationState as? BackupOperationTracker.State.Running)?.let { running ->
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item { BackupOperationProgress(running) }
		}

		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.other_apps)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.restore_from_tachiyomi),
						subtitle = stringResource(R.string.restore_tachiyomi_summary),
						icon = R.drawable.ic_revert,
						shape = pos.shape,
						onClick = onRestoreFromTachiyomi,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.migrate_from_kotatsu),
						subtitle = migrationSubtitle,
						icon = R.drawable.ic_backup_restore,
						shape = pos.shape,
						onClick = onMigrateFromKotatsu,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.export_to_mihon),
						subtitle = stringResource(R.string.export_to_mihon_summary),
						icon = R.drawable.ic_upload_file,
						shape = pos.shape,
						onClick = onExportToMihon,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

@Composable
private fun BackupOperationProgress(state: BackupOperationTracker.State.Running) {
	val stage = stringResource(state.stageRes)
	val progress = state.progress
	val subtitle = if (!progress.isIndeterminate && progress.total > 0) {
		"$stage • ${stringResource(R.string.backup_operation_progress_fraction, progress.progress, progress.total)} • " +
			formatProgressPercent(progress)
	} else {
		stage
	}
	SettingsGroup(title = stringResource(R.string.backup_operation_status)) {
		item { pos ->
			SettingsItem(
				title = stringResource(state.kind.titleRes),
				subtitle = subtitle,
				icon = if (state.kind.isRestore) R.drawable.ic_revert else R.drawable.ic_save,
				shape = pos.shape,
				trailing = {
					CircularProgressIndicator(
						modifier = Modifier.size(24.dp),
						strokeWidth = 2.dp,
					)
				},
			)
		}
	}
}

private fun formatProgressPercent(progress: Progress): String {
	if (progress.total <= 0) return "0%"
	val fractionDigits = when {
		progress.total <= 100 -> 0
		progress.total <= 1_000 -> 1
		progress.total <= 10_000 -> 2
		progress.total <= 100_000 -> 3
		else -> 4
	}
	val percent = (progress.progress.toDouble() / progress.total.toDouble() * 100.0).coerceIn(0.0, 100.0)
	val formatter = NumberFormat.getNumberInstance().apply {
		minimumFractionDigits = 0
		maximumFractionDigits = fractionDigits
	}
	return "${formatter.format(percent)}%"
}
