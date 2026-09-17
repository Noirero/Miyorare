package org.koitharu.kotatsu.kotatsumigration.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.CoroutineIntentService
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.withPartialWakeLock
import org.koitharu.kotatsu.kotatsumigration.domain.KotatsuMigrationManager
import org.koitharu.kotatsu.kotatsumigration.domain.KotatsuMigrationUseCase
import org.koitharu.kotatsu.kotatsumigration.domain.KotatsuMigrationUseCase.Outcome
import org.koitharu.kotatsu.kotatsumigration.domain.MigrationSummary
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class KotatsuMigrationService : CoroutineIntentService() {

	@Inject
	lateinit var useCase: KotatsuMigrationUseCase

	@Inject
	lateinit var manager: KotatsuMigrationManager

	private lateinit var notificationManager: NotificationManagerCompat

	override fun onCreate() {
		super.onCreate()
		notificationManager = NotificationManagerCompat.from(this)
	}

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		startForeground(this, done = 0, total = 0)
		val legacy = useCase.scan()
		manager.onStart(legacy.size)
		if (legacy.isEmpty()) {
			// Nothing to migrate (e.g. auto-run after restoring Miyorare's own backup) — finish silently.
			manager.onFinish(MigrationSummary(total = 0, migrated = 0, pendingExtension = 0, missingExtensions = emptySet()))
			return
		}
		useCase.prepare() // load installed extensions once before resolving
		var migrated = 0
		var pendingExtension = 0
		val missingExtensions = linkedSetOf<String>()
		powerManager.withPartialWakeLock(TAG) {
			legacy.forEachIndexed { index, item ->
				updateForeground(this, done = index, total = legacy.size)
				manager.onProgress(done = index, total = legacy.size, migrated = migrated)
				val outcome = runCatchingCancellable { useCase.migrate(item) }
					.getOrElse { Outcome.Failed(it.message) }
				when (outcome) {
					Outcome.Migrated -> migrated++
					is Outcome.ConvertedPendingExtension -> {
						pendingExtension++
						missingExtensions += outcome.target.sourceName
					}
					is Outcome.Failed -> outcome.message?.let { Log.w(TAG, "Migration skipped one entry: $it") }
					Outcome.NoMapping -> Unit // no Mihon equivalent — reflected in the X/Y count
				}
			}
		}
		val summary = MigrationSummary(
			total = legacy.size,
			migrated = migrated,
			pendingExtension = pendingExtension,
			missingExtensions = missingExtensions,
		)
		manager.onFinish(summary)
		notifyResult(startId, summary)
	}

	override fun IntentJobContext.onError(error: Throwable) {
		manager.reset()
		if (checkNotificationPermission(CHANNEL_ID)) {
			val notification = NotificationCompat.Builder(this@KotatsuMigrationService, CHANNEL_ID)
				.setContentTitle(getString(R.string.error_occurred))
				.setContentText(error.message)
				.setSmallIcon(R.drawable.general_notification)
				.setAutoCancel(true)
				.setSilent(true)
				.build()
			notificationManager.notify(TAG, startId, notification)
		}
	}

	@SuppressLint("InlinedApi")
	private fun startForeground(jobContext: IntentJobContext, done: Int, total: Int) {
		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
			.setName(getString(R.string.migrate_from_kotatsu))
			.setShowBadge(false)
			.setVibrationEnabled(false)
			.setSound(null, null)
			.setLightsEnabled(false)
			.build()
		notificationManager.createNotificationChannel(channel)
		jobContext.setForeground(
			FOREGROUND_NOTIFICATION_ID,
			buildProgressNotification(jobContext, done, total),
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
	}

	private fun updateForeground(jobContext: IntentJobContext, done: Int, total: Int) {
		if (!checkNotificationPermission(CHANNEL_ID)) return
		notificationManager.notify(
			FOREGROUND_NOTIFICATION_ID,
			buildProgressNotification(jobContext, done, total),
		)
	}

	private fun buildProgressNotification(jobContext: IntentJobContext, done: Int, total: Int) =
		NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle(getString(R.string.kotatsu_migration_running))
			.setContentText(if (total > 0) getString(R.string.fraction_pattern, done, total) else null)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setDefaults(0)
			.setSilent(true)
			.setOngoing(true)
			.setProgress(total, done, total == 0)
			.setSmallIcon(R.drawable.general_notification)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.addAction(
				appcompatR.drawable.abc_ic_clear_material,
				getString(android.R.string.cancel),
				jobContext.getCancelIntent(),
			)
			.build()

	private fun notifyResult(startId: Int, summary: MigrationSummary) {
		if (!checkNotificationPermission(CHANNEL_ID)) return
		val text = buildString {
			append(getString(R.string.kotatsu_migration_result, summary.converted, summary.total))
			if (summary.missingExtensions.isNotEmpty()) {
				append('\n')
				append(
					getString(
						R.string.kotatsu_migration_missing_extensions,
						summary.missingExtensions.joinToString(", "),
					),
				)
			}
		}
		val notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle(getString(R.string.kotatsu_migration_complete))
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text))
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setSmallIcon(R.drawable.general_notification)
			.setAutoCancel(true)
			.setSilent(true)
			.build()
		notificationManager.notify(TAG, startId, notification)
	}

	companion object {

		private const val TAG = "kotatsu_migration"
		private const val CHANNEL_ID = "kotatsu_migration"
		private const val FOREGROUND_NOTIFICATION_ID = 42

		fun start(context: Context): Boolean = try {
			ContextCompat.startForegroundService(
				context,
				Intent(context, KotatsuMigrationService::class.java),
			)
			true
		} catch (e: Exception) {
			e.printStackTraceDebug()
			false
		}
	}
}
