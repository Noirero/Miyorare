package org.koitharu.kotatsu.tsuki

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Periodic updater for official Miyorare Source Packs.
 *
 * It deliberately reuses [TsukiPluginInstaller]'s existing trusted release path, checksum
 * validation and atomic rollback. The worker never installs arbitrary upstream files directly:
 * it can only consume official Source Pack releases that have already passed the repository-side
 * upstream sync/compatibility pipeline.
 *
 * The existing extension update preferences are the user-facing policy for now:
 * - automatic extension updates enabled -> installed official Miyorare packs are auto-updated;
 * - update notifications enabled -> users are notified when an official pack update is available.
 * The pack detail screen remains the explicit "check/update now" path.
 */
@HiltWorker
class MiyorareSourcePackUpdateWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val settings: AppSettings,
	private val pluginInstaller: TsukiPluginInstaller,
	private val pluginManager: TsukiPluginManager,
) : CoroutineWorker(appContext, params) {

	private val notificationPrefs by lazy {
		applicationContext.getSharedPreferences(NOTIFICATION_PREFS_NAME, Context.MODE_PRIVATE)
	}

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		val autoUpdate = settings.isAutoUpdateExtensionsEnabled
		val notifications = settings.isExtensionUpdateNotificationsEnabled
		if (!autoUpdate && !notifications) return@withContext Result.success()

		pluginManager.initialize()
		val installedPlugins = pluginManager.getPlugins()
			.filter { it.provider == TsukiPluginProvider.MIYORARE }
		val localStagingPackIds = installedPlugins
			.asSequence()
			.filter { it.origin.startsWith(MiyorareOfficialSourcePacks.LOCAL_STAGING_ORIGIN_PREFIX) }
			.mapNotNull { MiyorareOfficialSourcePacks.findByInstalledPluginId(it.pluginId)?.pluginId }
			.toSet()
		val installedPackIds = installedPlugins
			.asSequence()
			.mapNotNull { MiyorareOfficialSourcePacks.findByInstalledPluginId(it.pluginId)?.pluginId }
			.filterNot { it in localStagingPackIds }
			.distinct()
			.toList()
		if (installedPackIds.isEmpty()) return@withContext Result.success()

		var pendingUpdates = 0
		var retryNeeded = false
		for (packId in installedPackIds) {
			try {
				if (!pluginInstaller.hasMiyorarePackUpdate(packId)) continue
				if (autoUpdate) {
					// The installer stages every shard first and rolls back all already-replaced shards
					// if any later validation/install step fails.
					pluginInstaller.installOrUpdateMiyorare(packId)
				} else {
					pendingUpdates++
				}
			} catch (error: CancellationException) {
				throw error
			} catch (error: IOException) {
				retryNeeded = true
				Log.w(TAG, "Temporary failure while checking/updating $packId", error)
			} catch (error: Throwable) {
				// Malformed/incompatible official releases fail closed in the installer. Do not loop
				// aggressively; the next scheduled check can retry after a fixed release is published.
				Log.e(TAG, "Official Source Pack update rejected for $packId", error)
			}
		}

		if (!autoUpdate && notifications && pendingUpdates > 0) {
			notifyUpdatesIfDue(pendingUpdates)
		}
		if (retryNeeded) Result.retry() else Result.success()
	}

	private fun notifyUpdatesIfDue(count: Int) {
		val now = System.currentTimeMillis()
		val lastNotification = notificationPrefs.getLong(KEY_LAST_NOTIFICATION_TIME, 0L)
		if (now - lastNotification < TimeUnit.DAYS.toMillis(1)) return
		if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) return

		val notificationManager = NotificationManagerCompat.from(applicationContext)
		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
			.setName(applicationContext.getString(R.string.extension_updates_available))
			.build()
		notificationManager.createNotificationChannel(channel)

		val launchIntent = applicationContext.packageManager
			.getLaunchIntentForPackage(applicationContext.packageName)
			?: Intent()
		val contentIntent = PendingIntentCompat.getActivity(
			applicationContext,
			0,
			launchIntent,
			PendingIntent.FLAG_UPDATE_CURRENT,
			false,
		)
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setSmallIcon(R.drawable.general_notification)
			.setContentTitle(applicationContext.getString(R.string.extension_updates_available))
			.setContentText(
				applicationContext.resources.getQuantityString(
					R.plurals.extension_updates_available_message,
					count,
					count,
				),
			)
			.setAutoCancel(true)
			.setContentIntent(contentIntent)
			.build()
		notificationManager.notify(TAG, NOTIFICATION_ID, notification)
		notificationPrefs.edit().putLong(KEY_LAST_NOTIFICATION_TIME, now).apply()
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val request = PeriodicWorkRequestBuilder<MiyorareSourcePackUpdateWorker>(6, TimeUnit.HOURS)
				.setConstraints(periodicConstraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniquePeriodicWork(
				PERIODIC_WORK_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				request,
			).await()
		}

		override suspend fun unschedule() {
			workManager.cancelUniqueWork(PERIODIC_WORK_NAME).await()
			workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME).await()
		}

		override suspend fun isScheduled(): Boolean = workManager
			.awaitUniqueWorkInfoByName(PERIODIC_WORK_NAME)
			.any { !it.state.isFinished }

		suspend fun startNow() {
			val request = OneTimeWorkRequestBuilder<MiyorareSourcePackUpdateWorker>()
				.setConstraints(immediateConstraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniqueWork(
				IMMEDIATE_WORK_NAME,
				ExistingWorkPolicy.KEEP,
				request,
			).await()
		}

		private fun immediateConstraints() = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.build()

		private fun periodicConstraints() = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.setRequiresBatteryNotLow(true)
			.build()
	}

	private companion object {
		const val TAG = "MiyorareSourcePackUpdate"
		const val CHANNEL_ID = "miyorare_source_pack_updates"
		const val NOTIFICATION_ID = 41
		const val NOTIFICATION_PREFS_NAME = "miyorare_source_pack_update_worker"
		const val KEY_LAST_NOTIFICATION_TIME = "last_update_notification_time"
		const val PERIODIC_WORK_NAME = "miyorare_source_pack_auto_updates"
		const val IMMEDIATE_WORK_NAME = "miyorare_source_pack_auto_updates_now"
	}
}
