package org.koitharu.kotatsu.settings.work

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.backup.local.ui.periodical.PeriodicalBackupWorker
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.extensions.install.ExtensionUpdateWorker
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeUpdateWorker
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.work.SyncWorker
import org.koitharu.kotatsu.tracker.work.TrackWorker
import org.koitharu.kotatsu.tsuki.MiyorareSourcePackUpdateWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduleManager @Inject constructor(
	private val settings: AppSettings,
	private val syncSettings: SyncSettings,
	private val workManager: WorkManager,
	private val suggestionScheduler: SuggestionsWorker.Scheduler,
	private val trackerScheduler: TrackWorker.Scheduler,
	private val backupScheduler: PeriodicalBackupWorker.Scheduler,
	private val syncScheduler: SyncWorker.Scheduler,
	private val extensionUpdateScheduler: ExtensionUpdateWorker.Scheduler,
	private val sourcePackUpdateScheduler: MiyorareSourcePackUpdateWorker.Scheduler,
	private val kotatsuRuntimeUpdateScheduler: KotatsuRuntimeUpdateWorker.Scheduler,
	@ApplicationContext private val context: Context,
) : SharedPreferences.OnSharedPreferenceChangeListener {

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_TRACKER_ENABLED,
			AppSettings.KEY_TRACKER_FREQUENCY,
			AppSettings.KEY_TRACKER_WIFI_ONLY -> updateWorker(trackerScheduler, settings.isTrackerEnabled, key != AppSettings.KEY_TRACKER_ENABLED)

			AppSettings.KEY_SUGGESTIONS,
			AppSettings.KEY_SUGGESTIONS_WIFI_ONLY -> updateWorker(suggestionScheduler, settings.isSuggestionsEnabled, key != AppSettings.KEY_SUGGESTIONS)

			AppSettings.KEY_BACKUP_PERIODICAL_ENABLED,
			AppSettings.KEY_BACKUP_PERIODICAL_FREQ,
			AppSettings.KEY_BACKUP_PERIODICAL_OUTPUT -> updateWorker(
				backupScheduler,
				settings.isPeriodicalBackupEnabled && settings.periodicalBackupDirectory != null,
				key != AppSettings.KEY_BACKUP_PERIODICAL_ENABLED,
			)

			AppSettings.KEY_AUTO_UPDATE_EXTENSIONS,
			AppSettings.KEY_SHIZUKU_INSTALLER,
			AppSettings.KEY_EXTENSION_UPDATE_NOTIFICATIONS,
			AppSettings.KEY_PRIVATE_INSTALLER -> {
				val extensionEnabled = isExtensionUpdateWorkerNeeded()
				updateWorker(extensionUpdateScheduler, extensionEnabled, false)
				if (extensionEnabled) processLifecycleScope.launch(Dispatchers.Default) { extensionUpdateScheduler.startNow() }

				val sourcePackEnabled = isSourcePackUpdateWorkerNeeded()
				updateWorker(sourcePackUpdateScheduler, sourcePackEnabled, false)
				if (sourcePackEnabled) processLifecycleScope.launch(Dispatchers.Default) { sourcePackUpdateScheduler.startNow() }

				// Kotatsu Runtime lives in app-private storage and has an independent signer/hash verifier.
				val runtimeEnabled = settings.isAutoUpdateExtensionsEnabled
				updateWorker(kotatsuRuntimeUpdateScheduler, runtimeEnabled, false)
				if (runtimeEnabled) processLifecycleScope.launch(Dispatchers.Default) { kotatsuRuntimeUpdateScheduler.startNow() }
			}
		}
	}

	fun init() {
		settings.subscribe(this)
		processLifecycleScope.launch(Dispatchers.Default) {
			updateWorkerImpl(trackerScheduler, settings.isTrackerEnabled, true)
			updateWorkerImpl(suggestionScheduler, settings.isSuggestionsEnabled, true)
			updateWorkerImpl(backupScheduler, settings.isPeriodicalBackupEnabled && settings.periodicalBackupDirectory != null, false)
			updateWorkerImpl(syncScheduler, syncSettings.isSignedIn && syncSettings.intervalMinutes > 0, false)

			val extensionUpdatesEnabled = isExtensionUpdateWorkerNeeded()
			updateWorkerImpl(extensionUpdateScheduler, extensionUpdatesEnabled, false)
			if (extensionUpdatesEnabled) extensionUpdateScheduler.startNow()

			val sourcePackUpdatesEnabled = isSourcePackUpdateWorkerNeeded()
			updateWorkerImpl(sourcePackUpdateScheduler, sourcePackUpdatesEnabled, false)
			if (sourcePackUpdatesEnabled) sourcePackUpdateScheduler.startNow()

			val runtimeUpdatesEnabled = settings.isAutoUpdateExtensionsEnabled
			updateWorkerImpl(kotatsuRuntimeUpdateScheduler, runtimeUpdatesEnabled, false)
			if (runtimeUpdatesEnabled) kotatsuRuntimeUpdateScheduler.startNow()

			if (syncSettings.isSignedIn && syncSettings.isSyncOnStart) SyncWorker.enqueueManual(workManager)
		}
	}

	private fun isExtensionUpdateWorkerNeeded(): Boolean =
		settings.isPrivateInstallEnabled ||
			(settings.isAutoUpdateExtensionsEnabled && settings.isShizukuInstallerEnabled) ||
			settings.isExtensionUpdateNotificationsEnabled ||
			LnPluginManager.hasInstalledPlugins(context)

	private fun isSourcePackUpdateWorkerNeeded(): Boolean =
		settings.isAutoUpdateExtensionsEnabled || settings.isExtensionUpdateNotificationsEnabled

	private fun updateWorker(scheduler: PeriodicWorkScheduler, isEnabled: Boolean, force: Boolean) {
		processLifecycleScope.launch(Dispatchers.Default) { updateWorkerImpl(scheduler, isEnabled, force) }
	}

	private suspend fun updateWorkerImpl(scheduler: PeriodicWorkScheduler, isEnabled: Boolean, force: Boolean) {
		if (force || scheduler.isScheduled() != isEnabled) {
			if (isEnabled) scheduler.schedule() else scheduler.unschedule()
		}
	}
}
