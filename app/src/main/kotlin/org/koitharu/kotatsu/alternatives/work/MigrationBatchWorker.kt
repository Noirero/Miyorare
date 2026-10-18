package org.koitharu.kotatsu.alternatives.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.*
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.settings.SettingsActivity

@HiltWorker
class MigrationBatchWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted parameters: WorkerParameters,
	private val autoFix: AutoFixUseCase,
	private val database: MangaDatabase,
) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result {
		setForeground(getForegroundInfo())
		val request = MigrationBatchWork.request(inputData)
		setProgress(MigrationBatchWork.output(MigrationBatchOutcome(
			if (request.action == MigrationBatchAction.CHECK) MigrationBatchStatus.CHECKING else MigrationBatchStatus.MIGRATING,
		)))
		val outcome = MigrationBatchPolicy.process(request,
			canAccess = {
				database.getMangaDao().isMigrationEntryVisible(request.mangaId) &&
					database.getMangaDao().find(request.mangaId)?.manga?.source == request.source
			},
			discover = { autoFix.discover(request.mangaId) },
			migrate = { selection -> autoFix.migrateSelected(request.mangaId, selection, reviewed = false) },
		)
		// ERROR/INCOMPLETE/REVIEW are explicit item outcomes, not a claim that migration succeeded.
		// Completing this work item lets later independent manga continue after a partial failure.
		return Result.success(MigrationBatchWork.output(outcome))
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		val title = applicationContext.getString(R.string.manga_migration)
		val channel = "migration_batch"
		NotificationManagerCompat.from(applicationContext).createNotificationChannel(
			NotificationChannelCompat.Builder(channel, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(title).setShowBadge(false).setVibrationEnabled(false).setSound(null, null).build(),
		)
		// Always generic and secret: membership can become Private while a batch is running.
		val notification = NotificationCompat.Builder(applicationContext, channel)
			.setContentTitle(title).setSmallIcon(R.drawable.general_notification)
			.setVisibility(NotificationCompat.VISIBILITY_SECRET).setSilent(true).setOngoing(true)
			.setProgress(0, 0, true)
			.setContentIntent(PendingIntentCompat.getActivity(applicationContext, 0,
				Intent(applicationContext, SettingsActivity::class.java).setAction(AppRouter.ACTION_MIGRATION),
				PendingIntent.FLAG_UPDATE_CURRENT, false))
			.addAction(R.drawable.ic_delete, applicationContext.getString(R.string.migration_batch_cancel),
				WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
			.build()
		return ForegroundInfo(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
	}
}
