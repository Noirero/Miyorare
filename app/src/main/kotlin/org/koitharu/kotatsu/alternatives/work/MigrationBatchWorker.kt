package org.koitharu.kotatsu.alternatives.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationChannelCompat
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
import org.koitharu.kotatsu.alternatives.domain.AutoFixUseCase
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchOutcome
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchPolicy
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.settings.SettingsActivity

@HiltWorker
class MigrationBatchWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted parameters: WorkerParameters,
	private val autoFix: AutoFixUseCase,
) : CoroutineWorker(context, parameters) {

	override suspend fun doWork(): Result {
		val request = MigrationBatchWork.request(inputData) ?: return Result.failure()
		setForeground(getForegroundInfo())
		val generation = MigrationBatchWork.generation(inputData)
		val batchName = MigrationBatchWork.batchName(inputData).ifBlank { "migration-${id}" }
		val progress = MigrationBatchWork.progress(
			request,
			generation,
			batchName,
			MigrationBatchOutcome(
				if (request.action == MigrationBatchAction.MIGRATE) MigrationBatchStatus.MIGRATING else MigrationBatchStatus.CHECKING,
			),
		)
		setProgress(progress)
		val outcome = MigrationBatchPolicy.process(
			request = request,
			canAccess = { MigrationBatchWork.canAccess(applicationContext, request) },
			discover = {
				val onProgress: suspend (org.koitharu.kotatsu.alternatives.domain.MigrationDiscovery) -> Unit = { discovery ->
					setProgress(MigrationBatchWork.progress(
						request,
						generation,
						batchName,
						MigrationBatchOutcome(MigrationBatchStatus.CHECKING),
						discovery.progress,
					))
				}
				when (request.discoveryMode) {
					MigrationBatchDiscoveryMode.CHECK_CURRENT ->
						autoFix.discover(request.mangaId, request.searchScope, onProgress)
					MigrationBatchDiscoveryMode.DIRECT_SEARCH ->
						autoFix.searchReplacements(
							mangaId = request.mangaId,
							searchScope = request.searchScope,
							directSearch = true,
							revealAllCandidates = false,
							onProgress = onProgress,
						)
				}
			},
			migrate = { selection ->
				autoFix.migrateSelected(
					request.mangaId,
					selection,
					reviewed = false,
					allowReadableSource = request.discoveryMode == MigrationBatchDiscoveryMode.DIRECT_SEARCH,
				)
			},
		)
		return Result.success(MigrationBatchWork.output(outcome))
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		val title = applicationContext.getString(R.string.manga_migration)
		val channel = "migration_batch"
		NotificationManagerCompat.from(applicationContext).createNotificationChannel(
			NotificationChannelCompat.Builder(channel, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(title).setShowBadge(false).setVibrationEnabled(false).setSound(null, null).build(),
		)
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
		return ForegroundInfo(47, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
	}
}

class MigrationBatchCancellationMarkerWorker(
	context: Context,
	parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result = Result.success(inputData)
}
