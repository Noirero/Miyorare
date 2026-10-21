package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
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
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Periodically installs only a manifest + runtime APK that passes the host verifier. */
@HiltWorker
class KotatsuRuntimeUpdateWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val updater: KotatsuRuntimeUpdater,
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result = try {
		// null means the installed pack already matches the latest compatible manifest.
		updater.updateLatest()
		Result.success()
	} catch (error: CancellationException) {
		throw error
	} catch (error: IOException) {
		Result.retry()
	} catch (_: Throwable) {
		// Protocol/signature/health failures are fail-closed and keep current/previous known-good.
		Result.failure()
	}

	@Reusable
	class Scheduler @Inject constructor(private val workManager: WorkManager) : PeriodicWorkScheduler {
		override suspend fun schedule() {
			val request = PeriodicWorkRequestBuilder<KotatsuRuntimeUpdateWorker>(12, TimeUnit.HOURS)
				.setConstraints(constraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).await()
		}

		override suspend fun unschedule() {
			workManager.cancelUniqueWork(PERIODIC_NAME).await()
			workManager.cancelUniqueWork(IMMEDIATE_NAME).await()
		}

		override suspend fun isScheduled(): Boolean = workManager.awaitUniqueWorkInfoByName(PERIODIC_NAME).any { !it.state.isFinished }

		suspend fun startNow() {
			val request = OneTimeWorkRequestBuilder<KotatsuRuntimeUpdateWorker>()
				.setConstraints(constraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniqueWork(IMMEDIATE_NAME, ExistingWorkPolicy.REPLACE, request).await()
		}

		private fun constraints() = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.setRequiresBatteryNotLow(true)
			.build()
	}

	private companion object {
		const val PERIODIC_NAME = "kotatsu_runtime_auto_update"
		const val IMMEDIATE_NAME = "kotatsu_runtime_update_now"
	}
}
