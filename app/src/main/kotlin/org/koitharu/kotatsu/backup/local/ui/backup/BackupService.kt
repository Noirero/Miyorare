package org.koitharu.kotatsu.backup.local.ui.backup

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.widget.Toast
import androidx.annotation.CheckResult
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.BackupOperationTracker
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.backup.local.ui.BaseBackupRestoreService
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.CompositeResult
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.core.util.ext.withPartialWakeLock
import org.koitharu.kotatsu.core.util.progress.Progress
import java.io.FileNotFoundException
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
@SuppressLint("InlinedApi")
class BackupService : BaseBackupRestoreService() {

	override val notificationTag = TAG
	override val isRestoreService = false

	@Inject
	lateinit var repository: LocalBackupRepository

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		BackupOperationTracker.start(
			BackupOperationTracker.Kind.LOCAL_BACKUP,
			R.string.backup_operation_preparing,
		)
		setForeground(
			FOREGROUND_NOTIFICATION_ID,
			buildNotification(Progress.INDETERMINATE),
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
		val destination = intent.getStringExtra(AppRouter.KEY_DATA)?.toUriOrNull()
			?: FileNotFoundException().also {
				BackupOperationTracker.failed(BackupOperationTracker.Kind.LOCAL_BACKUP, it)
			}.let { throw it }
		applicationContext.powerManager.withPartialWakeLock(TAG) {
			val progress = MutableStateFlow(Progress.INDETERMINATE)
			val canNotify = applicationContext.checkNotificationPermission(CHANNEL_ID)
			val progressUpdateJob = launch {
				progress.collect { value ->
					BackupOperationTracker.update(
						BackupOperationTracker.Kind.LOCAL_BACKUP,
						value,
						R.string.backup_operation_creating,
					)
					if (canNotify) {
						notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification(value))
					}
				}
			}
			try {
				ZipOutputStream(contentResolver.openOutputStream(destination)).use { output ->
					repository.createBackup(output, progress)
				}
			} catch (e: CancellationException) {
				deletePartialBackup(destination, e)
				BackupOperationTracker.cancelled(
					BackupOperationTracker.Kind.LOCAL_BACKUP,
					applicationContext.getString(R.string.backup_operation_cancelled_by_user),
				)
				throw e
			} catch (e: Throwable) {
				deletePartialBackup(destination, e)
				BackupOperationTracker.failed(BackupOperationTracker.Kind.LOCAL_BACKUP, e)
				throw e
			} finally {
				progressUpdateJob.cancelAndJoin()
			}
			contentResolver.notifyChange(destination, null)
			showResultNotification(destination, CompositeResult.success())
			BackupOperationTracker.success(
				BackupOperationTracker.Kind.LOCAL_BACKUP,
				getFileDisplayName(destination),
			)
			withContext(Dispatchers.Main) {
				Toast.makeText(this@BackupService, R.string.backup_saved, Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun deletePartialBackup(destination: Uri, error: Throwable) {
		try {
			DocumentFile.fromSingleUri(applicationContext, destination)?.delete()
		} catch (deleteError: Throwable) {
			error.addSuppressed(deleteError)
		}
	}

	private fun IntentJobContext.buildNotification(progress: Progress): Notification {
		return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(getString(R.string.creating_backup))
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
			.setSmallIcon(android.R.drawable.stat_sys_upload)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.addAction(
				appcompatR.drawable.abc_ic_clear_material,
				applicationContext.getString(android.R.string.cancel),
				getCancelIntent(),
			).build()
	}

	companion object {

		private const val TAG = "BACKUP"
		private const val FOREGROUND_NOTIFICATION_ID = 33

		@CheckResult
		fun start(context: Context, uri: Uri): Boolean = try {
			val intent = Intent(context, BackupService::class.java)
			intent.putExtra(AppRouter.KEY_DATA, uri.toString())
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (e: Exception) {
			e.printStackTraceDebug()
			BackupOperationTracker.start(
				BackupOperationTracker.Kind.LOCAL_BACKUP,
				R.string.backup_operation_preparing,
			)
			BackupOperationTracker.failed(BackupOperationTracker.Kind.LOCAL_BACKUP, e)
			false
		}
	}
}
