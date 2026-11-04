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
import org.koitharu.kotatsu.alternatives.domain.AutoFixUseCase
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchOutcome
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchPolicy
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationDiscovery
import org.koitharu.kotatsu.alternatives.domain.MigrationExecutionMode
import org.koitharu.kotatsu.alternatives.domain.YokaiStyleMigrationExecutionUseCase
import org.koitharu.kotatsu.alternatives.domain.YokaiStyleMigrationSearchUseCase
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.settings.SettingsActivity

@HiltWorker
class MigrationBatchWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted parameters: WorkerParameters,
	private val autoFix: AutoFixUseCase,
	private val yokaiSearch: YokaiStyleMigrationSearchUseCase,
	private val yokaiExecute: YokaiStyleMigrationExecutionUseCase,
	private val database: MangaDatabase,
) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result {
		setForeground(getForegroundInfo())
		val request = MigrationBatchWork.request(inputData)
        // Room ownership and the durable checkpoint commit atomically. A restarted worker
        // may recover that result without executing the transfer twice.
        completedSimpleMigration(database, request)?.let {
            return Result.success(MigrationBatchWork.output(it))
        }
		setProgress(MigrationBatchWork.output(MigrationBatchOutcome(
			if (request.action == MigrationBatchAction.CHECK) MigrationBatchStatus.CHECKING else MigrationBatchStatus.MIGRATING,
		)))
		val outcome = MigrationBatchPolicy.process(
			request = request,
			canAccess = {
				database.getMangaDao().isMigrationEntryVisible(request.mangaId) &&
					database.getMangaDao().find(request.mangaId)?.manga?.source == request.source
			},
			discover = {
				val onProgress: suspend (MigrationDiscovery) -> Unit = { discovery ->
					setProgress(MigrationBatchWork.output(
						MigrationBatchOutcome(MigrationBatchStatus.CHECKING),
						discovery.progress,
					))
				}
				when (request.discoveryMode) {
					MigrationBatchDiscoveryMode.YOKAI -> yokaiSearch(
						mangaId = request.mangaId,
						selectedSourceNames = request.selectedSourceNames,
						strategy = request.sourceStrategy,
						extraSearchParams = request.extraSearchParams,
						onProgress = onProgress,
					)
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
				when (request.discoveryMode) {
					MigrationBatchDiscoveryMode.YOKAI -> yokaiExecute(
						request.mangaId,
						selection,
						MigrationExecutionMode.MIGRATE,
					)
					MigrationBatchDiscoveryMode.CHECK_CURRENT,
					MigrationBatchDiscoveryMode.DIRECT_SEARCH -> autoFix.migrateSelected(
						request.mangaId,
						selection,
						reviewed = false,
						allowReadableSource = request.discoveryMode == MigrationBatchDiscoveryMode.DIRECT_SEARCH,
					)
				}
			},
            migrateDetailed = if (request.action == MigrationBatchAction.MIGRATE &&
                request.discoveryMode == MigrationBatchDiscoveryMode.YOKAI
            ) ({ selection -> yokaiExecute.migrateSimple(request.mangaId, selection) }) else null,
			copy = { selection ->
				check(request.discoveryMode == MigrationBatchDiscoveryMode.YOKAI) {
					"Copy is only supported by the Yokai migration session"
				}
				yokaiExecute(request.mangaId, selection, MigrationExecutionMode.COPY)
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

/**
 * Read-only reconciliation after a successful Simple Migration commit, including process restart.
 * Both the source-owned checkpoint and destination identity must still match.
 */
internal suspend fun completedSimpleMigration(
    database: MangaDatabase,
    request: org.koitharu.kotatsu.alternatives.domain.MigrationBatchRequest,
): MigrationBatchOutcome? {
    if (request.action != MigrationBatchAction.MIGRATE ||
        request.discoveryMode != MigrationBatchDiscoveryMode.YOKAI
    ) return null
    val selected = request.selection ?: return null
    if (database.getMangaDao().preservedDestination(request.mangaId) != selected.id ||
        database.getMangaDao().find(selected.id)?.manga?.source != selected.source ||
        !database.getMangaDao().isMigrationEntryVisible(selected.id) ||
        database.getPrivateFavouritesDao().isPrivateOnly(selected.id)
    ) return null
    return MigrationBatchOutcome(
        status = if (database.getMangaDao().isSimpleMigrationPreserved(request.mangaId)) {
            MigrationBatchStatus.MIGRATED_PRESERVED
        } else {
            MigrationBatchStatus.MIGRATED
        },
        destinationId = selected.id,
        destinationSource = selected.source,
    )
}


/**
 * A stopped WorkManager job cannot publish its success output after Room has committed.
 * Reconcile ONLY its exact persisted source -> destination checkpoint, without rerunning
 * migration or changing any user data. Uncommitted/ambiguous/private results stay interrupted.
 */
internal suspend fun reconcileInterruptedSimpleMigration(
    database: MangaDatabase,
    snapshot: org.koitharu.kotatsu.alternatives.domain.MigrationBatchSnapshot,
): org.koitharu.kotatsu.alternatives.domain.MigrationBatchSnapshot {
    if (snapshot.running || snapshot.outcome.status != MigrationBatchStatus.INTERRUPTED ||
        snapshot.discoveryMode != MigrationBatchDiscoveryMode.YOKAI
    ) return snapshot
    val targetId = snapshot.outcome.destinationId ?: return snapshot
    val destination = database.getMangaDao().find(targetId)?.manga ?: return snapshot
    if (snapshot.outcome.destinationSource != null &&
        snapshot.outcome.destinationSource != destination.source
    ) return snapshot
    val checkpoint = completedSimpleMigration(
        database,
        org.koitharu.kotatsu.alternatives.domain.MigrationBatchRequest(
            mangaId = snapshot.mangaId,
            source = snapshot.source,
            action = MigrationBatchAction.MIGRATE,
            selection = org.koitharu.kotatsu.alternatives.domain.MigrationSelection(
                destination.source, targetId, "reconcile-checkpoint",
            ),
            discoveryMode = snapshot.discoveryMode,
        ),
    ) ?: return snapshot
    return snapshot.copy(outcome = checkpoint.copy(destinationTitle = destination.title))
}

class MigrationBatchCancellationMarkerWorker(
	context: Context,
	parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result = Result.success(inputData)
}
