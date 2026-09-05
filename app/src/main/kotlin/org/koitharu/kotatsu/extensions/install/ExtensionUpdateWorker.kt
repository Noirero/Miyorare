package org.koitharu.kotatsu.extensions.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.settings.sources.catalog.ExternalExtensionRepoEntry
import org.koitharu.kotatsu.settings.sources.catalog.ExternalExtensionRepoRepository
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionInstallMode
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreManager
import org.koitharu.kotatsu.settings.sources.catalog.StoreHealth
import org.koitharu.kotatsu.settings.sources.catalog.SourcesCatalogActivity
import org.koitharu.kotatsu.settings.sources.catalog.isLnPlugin
import org.koitharu.kotatsu.settings.sources.catalog.isNewerPluginVersion
import org.koitharu.kotatsu.settings.sources.catalog.isNewerThan
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class ExtensionUpdateWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val settings: AppSettings,
	private val installerPreferences: ExtensionInstallerPreferences,
	private val repoRepository: ExternalExtensionRepoRepository,
	private val storeManager: ExtensionStoreManager,
	private val extensionLoader: MihonExtensionLoader,
	private val extensionManager: MihonExtensionManager,
	private val shizukuInstaller: ShizukuExtensionInstaller,
	private val lnPluginManager: LnPluginManager,
	@BaseHttpClient private val httpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		// Novel/LN plugins are internal text files, not Android packages. Preserve their independent
		// updater path: they never go through Package Installer or Shizuku.
		val novelRetryNeeded = updateNovelPlugins()
		val apkResult = doApkWork()
		if (novelRetryNeeded && apkResult == Result.success()) Result.retry() else apkResult
	}

	private suspend fun updateNovelPlugins(): Boolean {
		lnPluginManager.initialize()
		val plugins = lnPluginManager.getAll()
		if (plugins.isEmpty()) return false
		storeManager.refresh(forceRefresh = true)
		val candidates = storeManager.states.value
			.filter { it.store.enabled && it.health == StoreHealth.AVAILABLE }
			.flatMap { state -> state.catalog.filter { it.isLnPlugin }.map { state to it } }
		if (candidates.isEmpty()) return false
		var retryNeeded = false
		for (source in plugins) {
			if (isStopped) break
			val plugin = source.plugin
			val (state, entry) = candidates
				.filter { (_, e) ->
					e.packageName == plugin.id && isNewerPluginVersion(e.versionName, plugin.version)
				}
				.minByOrNull { (s, _) -> if (s.store.id == plugin.storeId) 0 else 1 }
				?: continue
			try {
				lnPluginManager.install(
					pluginId = entry.packageName,
					rawCode = downloadText(entry.apkName),
					iconUrl = entry.iconUrl.orEmpty(),
					lang = entry.lang.orEmpty(),
					storeId = state.store.id,
				)
			} catch (e: Exception) {
				if (e is IOException) retryNeeded = true
				Log.e(TAG, "Failed to update novel plugin ${entry.packageName}", e)
			}
		}
		return retryNeeded
	}

	private suspend fun doApkWork(): Result {
		val method = installerPreferences.method
		val autoUpdate = settings.isAutoUpdateExtensionsEnabled
		val notifications = settings.isExtensionUpdateNotificationsEnabled
		return when (method) {
			ExtensionInstallerMethod.PRIVATE -> doPrivateModeWork(
				autoInstall = autoUpdate,
				shouldNotify = notifications,
			)
			ExtensionInstallerMethod.SHIZUKU -> doSystemModeWork(
				autoInstall = autoUpdate,
				shouldNotify = notifications,
			)
			ExtensionInstallerMethod.SYSTEM -> doSystemModeWork(
				// A background worker cannot reasonably drive Android's confirmation UI. Never
				// substitute Shizuku or silently pick another method.
				autoInstall = false,
				shouldNotify = notifications || autoUpdate,
			)
		}
	}

	private suspend fun doSystemModeWork(
		autoInstall: Boolean,
		shouldNotify: Boolean,
	): Result {
		if (!autoInstall && !shouldNotify) return Result.success()
		if (autoInstall && !shizukuInstaller.awaitReady()) {
			// Selected Shizuku must stay selected; retry rather than falling back to Package Installer.
			return Result.retry()
		}

		return try {
			val installed = extensionLoader.getInstalledExtensions(applicationContext)
				.associateBy { it.pkgName }
			if (installed.isEmpty()) return Result.success()
			storeManager.refresh(forceRefresh = true)
			val allStoreStates = storeManager.states.value
			val storeStates = allStoreStates.filter {
				it.store.enabled && it.health == StoreHealth.AVAILABLE
			}
			if (storeStates.isEmpty()) {
				return if (allStoreStates.any { it.store.enabled }) Result.retry() else Result.success()
			}

			val downloadDir = File(applicationContext.cacheDir, "extension_updates").apply { mkdirs() }
			var installedAny = false
			var retryNeeded = allStoreStates.any {
				it.store.enabled && it.health == StoreHealth.UNAVAILABLE
			}
			var permanentFailure = false
			val pendingUpdates = ArrayList<ExternalExtensionRepoEntry>()
			repoLoop@ for (state in storeStates) {
				val owned = installed.values.filter {
					storeManager.owner(ExtensionInstallMode.SYSTEM, it)?.id == state.store.id
				}.associateBy { it.pkgName }
				val updates = state.catalog.filter { entry ->
					owned[entry.packageName]?.let(entry::isNewerThan) == true
				}.sortedBy { it.name.lowercase() }
				if (!autoInstall) {
					pendingUpdates += updates
					continue@repoLoop
				}
				for (entry in updates) {
					if (isStopped) break@repoLoop
					val apk = File(downloadDir, "${entry.packageName}-${entry.versionCode}.apk")
					try {
						download(repoRepository.resolveApkUrl(state.store.indexUrl, entry.apkName), apk)
						when (val installResult = shizukuInstaller.install(apk, entry.packageName)) {
							ShizukuExtensionInstaller.InstallResult.Success -> installedAny = true
							ShizukuExtensionInstaller.InstallResult.Unavailable -> {
								retryNeeded = true
								break@repoLoop
							}
							ShizukuExtensionInstaller.InstallResult.InvalidPackage -> {
								permanentFailure = true
								Log.e(TAG, "Downloaded APK has the wrong package for ${entry.packageName}")
							}
							is ShizukuExtensionInstaller.InstallResult.Failure -> {
								Log.e(TAG, "Failed to update ${entry.packageName}: ${installResult.message}")
								if (
									installResult.status == null ||
									installResult.status == PackageInstaller.STATUS_FAILURE_TIMEOUT
								) {
									retryNeeded = true
								} else {
									permanentFailure = true
								}
							}
						}
					} catch (_: IOException) {
						retryNeeded = true
					} finally {
						apk.delete()
					}
				}
			}
			if (installedAny) extensionManager.loadExtensions()
			if (!autoInstall) {
				if (shouldNotify && pendingUpdates.isNotEmpty()) {
					notifyUpdatesIfDue(pendingUpdates.size)
				}
				return Result.success()
			}
			when {
				isStopped -> Result.retry()
				retryNeeded -> Result.retry()
				permanentFailure && !installedAny -> Result.failure()
				else -> Result.success()
			}
		} catch (e: Exception) {
			Log.e(TAG, "Extension auto-update failed", e)
			Result.failure()
		}
	}

	private suspend fun doPrivateModeWork(
		autoInstall: Boolean,
		shouldNotify: Boolean,
	): Result {
		if (!autoInstall && !shouldNotify) return Result.success()
		return try {
			val installed = extensionLoader.getInstalledExtensions(applicationContext, privateMode = true)
				.associateBy { it.pkgName }
			if (installed.isEmpty()) return Result.success()
			storeManager.refresh(forceRefresh = true)
			val allStoreStates = storeManager.states.value
			val storeStates = allStoreStates.filter {
				it.store.enabled && it.health == StoreHealth.AVAILABLE
			}
			if (storeStates.isEmpty()) {
				return if (allStoreStates.any { it.store.enabled }) Result.retry() else Result.success()
			}

			val downloadDir = File(applicationContext.cacheDir, "extension_updates").apply { mkdirs() }
			var installedAny = false
			var retryNeeded = allStoreStates.any {
				it.store.enabled && it.health == StoreHealth.UNAVAILABLE
			}
			var permanentFailure = false
			var pendingUpdateCount = 0
			repoLoop@ for (state in storeStates) {
				val owned = installed.values.filter {
					storeManager.owner(ExtensionInstallMode.SANDBOX, it)?.id == state.store.id
				}.associateBy { it.pkgName }
				val updates = state.catalog.filter { entry ->
					owned[entry.packageName]?.let(entry::isNewerThan) == true
				}.sortedBy { it.name.lowercase() }
				if (!autoInstall) {
					pendingUpdateCount += updates.size
					continue@repoLoop
				}
				for (entry in updates) {
					if (isStopped) break@repoLoop
					val apk = File(downloadDir, "${entry.packageName}-${entry.versionCode}.apk")
					try {
						download(repoRepository.resolveApkUrl(state.store.indexUrl, entry.apkName), apk)
						val success = MihonExtensionLoader.installPrivateExtensionFile(
							context = applicationContext,
							file = apk,
							expectedPackageName = entry.packageName,
						)
						if (success) {
							installedAny = true
						} else {
							permanentFailure = true
							Log.e(TAG, "Failed to private-install update for ${entry.packageName}")
						}
					} catch (_: IOException) {
						retryNeeded = true
					} finally {
						apk.delete()
					}
				}
			}
			if (installedAny) extensionManager.loadExtensions()
			if (!autoInstall) {
				if (shouldNotify && pendingUpdateCount > 0) notifyUpdatesIfDue(pendingUpdateCount)
				return Result.success()
			}
			when {
				isStopped -> Result.retry()
				retryNeeded -> Result.retry()
				permanentFailure && !installedAny -> Result.failure()
				else -> Result.success()
			}
		} catch (e: Exception) {
			Log.e(TAG, "Extension private-mode auto-update failed", e)
			Result.failure()
		}
	}

	private fun notifyUpdatesIfDue(count: Int) {
		val now = System.currentTimeMillis()
		if (now - settings.lastExtensionUpdateNotificationTime < TimeUnit.DAYS.toMillis(1)) return
		notifyUpdatesAvailable(count)
		settings.lastExtensionUpdateNotificationTime = now
	}

	private fun notifyUpdatesAvailable(count: Int) {
		if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) return
		val notificationManager = NotificationManagerCompat.from(applicationContext)
		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
			.setName(applicationContext.getString(R.string.extension_updates_available))
			.build()
		notificationManager.createNotificationChannel(channel)

		val intent = Intent(applicationContext, SourcesCatalogActivity::class.java)
			.putExtra(AppRouter.KEY_SOURCE_CATALOG_EXTERNAL_ONLY, true)
		val contentIntent = PendingIntentCompat.getActivity(
			applicationContext,
			0,
			intent,
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
	}

	private fun download(url: String, destination: File) {
		val request = Request.Builder().url(url).get().build()
		httpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
			val body = response.body
			val length = body.contentLength()
			if (length > MAX_APK_BYTES) throw IOException("Extension APK is too large")
			body.byteStream().use { input ->
				destination.outputStream().buffered().use { output ->
					val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
					var total = 0L
					while (true) {
						val read = input.read(buffer)
						if (read < 0) break
						total += read
						if (total > MAX_APK_BYTES) throw IOException("Extension APK is too large")
						output.write(buffer, 0, read)
					}
				}
			}
		}
	}

	private fun downloadText(url: String): String {
		val request = Request.Builder().url(url).get().build()
		return httpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
			val body = response.body
			if (body.contentLength() > MAX_PLUGIN_BYTES) throw IOException("Plugin is too large")
			body.string()
		}
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val request = PeriodicWorkRequestBuilder<ExtensionUpdateWorker>(1, TimeUnit.DAYS)
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
			val request = OneTimeWorkRequestBuilder<ExtensionUpdateWorker>()
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
		const val TAG = "ExtensionUpdateWorker"
		const val CHANNEL_ID = "extension_updates"
		const val NOTIFICATION_ID = 39
		const val PERIODIC_WORK_NAME = "extension_auto_updates"
		const val IMMEDIATE_WORK_NAME = "extension_auto_updates_now"
		const val MAX_APK_BYTES = 100L * 1024L * 1024L
		const val MAX_PLUGIN_BYTES = 4L * 1024L * 1024L
	}
}
