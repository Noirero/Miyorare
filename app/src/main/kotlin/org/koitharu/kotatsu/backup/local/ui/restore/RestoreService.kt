package org.koitharu.kotatsu.backup.local.ui.restore

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.annotation.CheckResult
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.BackupOperationTracker
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.backup.local.ui.BaseBackupRestoreService
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.core.util.ext.withPartialWakeLock
import org.koitharu.kotatsu.core.util.progress.Progress
import org.koitharu.kotatsu.kotatsumigration.domain.KotatsuMigrationUseCase
import org.koitharu.kotatsu.kotatsumigration.ui.KotatsuMigrationService
import java.io.FileNotFoundException
import java.util.EnumSet
import java.util.zip.ZipInputStream
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
@SuppressLint("InlinedApi")
class RestoreService : BaseBackupRestoreService() {

	override val notificationTag = TAG
	override val isRestoreService = true

	@Inject
	lateinit var repository: LocalBackupRepository

	@Inject
	lateinit var migrationUseCase: KotatsuMigrationUseCase

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		BackupOperationTracker.start(
			BackupOperationTracker.Kind.LOCAL_RESTORE,
			R.string.backup_operation_preparing,
		)
		setForeground(
			FOREGROUND_NOTIFICATION_ID,
			buildNotification(Progress.INDETERMINATE),
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
		val source = intent.getStringExtra(AppRouter.KEY_DATA)?.toUriOrNull()
			?: FileNotFoundException().also {
				BackupOperationTracker.failed(BackupOperationTracker.Kind.LOCAL_RESTORE, it)
			}.let { throw it }
		val sections = intent.getStringArrayExtra(AppRouter.KEY_ENTRIES)?.mapNotNullTo(
			EnumSet.noneOf(BackupSection::class.java),
		) { name ->
			runCatching { BackupSection.valueOf(name) }.getOrNull()
		}.orEmpty()
		if (sections.isEmpty()) {
			val error = IllegalArgumentException("No backup sections selected")
			BackupOperationTracker.failed(BackupOperationTracker.Kind.LOCAL_RESTORE, error)
			throw error
		}
		applicationContext.powerManager.withPartialWakeLock(TAG) {
			val progress = MutableStateFlow(Progress.INDETERMINATE)
			val canNotify = applicationContext.checkNotificationPermission(CHANNEL_ID)
			val progressUpdateJob = launch {
				progress.collect { value ->
					BackupOperationTracker.update(
						BackupOperationTracker.Kind.LOCAL_RESTORE,
						value,
						R.string.backup_operation_restoring,
					)
					if (canNotify) {
						notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification(value))
					}
				}
			}
			try {
				val result = ZipInputStream(contentResolver.openInputStream(source)).use { input ->
					repository.restoreBackup(input, sections, progress)
				}
				progressUpdateJob.cancelAndJoin()
				showResultNotification(source, result)
				if (result.isAllSuccess) {
					BackupOperationTracker.success(
						BackupOperationTracker.Kind.LOCAL_RESTORE,
						getFileDisplayName(source),
					)
				} else {
					val firstError = result.failures.firstOrNull()
					if (firstError != null) {
						BackupOperationTracker.failed(
							BackupOperationTracker.Kind.LOCAL_RESTORE,
							firstError,
							result.failures.joinToString("\n") { it.message.orEmpty() }.takeIf { it.isNotBlank() },
						)
					} else {
						BackupOperationTracker.success(BackupOperationTracker.Kind.LOCAL_RESTORE)
					}
				}
			} catch (e: CancellationException) {
				progressUpdateJob.cancelAndJoin()
				BackupOperationTracker.cancelled(
					BackupOperationTracker.Kind.LOCAL_RESTORE,
					applicationContext.getString(R.string.backup_operation_cancelled_by_user),
				)
				throw e
			} catch (e: Throwable) {
				progressUpdateJob.cancelAndJoin()
				BackupOperationTracker.failed(BackupOperationTracker.Kind.LOCAL_RESTORE, e)
				throw e
			}
			// If the restored backup came from another Kotatsu fork (it carries built-in source
			// names this app doesn't have), auto-convert its library onto the matching Mihon
			// extensions. Own-app backups only have MIHON_ sources, so the scan is empty and this
			// no-ops. Manual trigger still lives in Backup & Restore settings.
			runCatching {
				if (migrationUseCase.scan().isNotEmpty()) {
					KotatsuMigrationService.start(applicationContext)
				}
			}.onFailure { it.printStackTraceDebug() }
		}
	}

	private fun IntentJobContext.buildNotification(progress: Progress): Notification {
		return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(getString(R.string.restoring_backup))
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setDefaults(0)
			.setSilent(true)
			.setOngoing(true)
			.setProgress(
				progress.total.coerceAtLeast(0),
				progress.progress.coerceAtLeast(0),
				progress.isIndeterminate,
			)
			.setContentText(
				if (progress.isIndeterminate) {
					getString(R.string.processing_)
				} else {
					getString(R.string.fraction_pattern, progress.progress, progress.total)
				},
			)
			.setSmallIcon(android.R.drawable.stat_sys_download)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.addAction(
				appcompatR.drawable.abc_ic_clear_material,
				applicationContext.getString(android.R.string.cancel),
				getCancelIntent(),
			).build()
	}

	companion object {

		private const val TAG = "RESTORE"
		private const val FOREGROUND_NOTIFICATION_ID = 39

		@CheckResult
		fun start(context: Context, uri: Uri, sections: Set<BackupSection>): Boolean = try {
			require(sections.isNotEmpty())
			val intent = Intent(context, RestoreService::class.java)
			intent.putExtra(AppRouter.KEY_DATA, uri.toString())
			intent.putExtra(AppRouter.KEY_ENTRIES, sections.map { it.name }.toTypedArray())
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (e: Exception) {
			e.printStackTraceDebug()
			BackupOperationTracker.start(
				BackupOperationTracker.Kind.LOCAL_RESTORE,
				R.string.backup_operation_preparing,
			)
			BackupOperationTracker.failed(BackupOperationTracker.Kind.LOCAL_RESTORE, e)
			false
		}
	}
}
