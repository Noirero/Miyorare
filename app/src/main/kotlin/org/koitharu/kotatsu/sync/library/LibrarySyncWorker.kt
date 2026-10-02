package org.koitharu.kotatsu.sync.library

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
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@HiltWorker
class LibrarySyncWorker
@AssistedInject
constructor(
	@Assisted context: Context,
	@Assisted parameters: WorkerParameters,
	private val engine: LibrarySyncEngine,
	private val registry: LibrarySyncRegistry,
	private val scheduler: LibrarySyncScheduler,
) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result {
		// Periodic dispatcher enqueues the exact same unique per-service work as manual sync.
		if (inputData.getBoolean("dispatch", false)) {
			registry
				.all()
				.filter { it !is LibrarySyncBlockedService }
				.forEach {
					if (it.connectionStatus() != LibrarySyncConnectionStatus.DISCONNECTED)
						scheduler.manual(it.id)
				}
			return Result.success()
		}
		val id =
			inputData.getString("service")?.let { raw ->
				LibrarySyncServiceId.entries.firstOrNull { it.name == raw }
			} ?: return Result.failure()
		return try {
			engine.sync(id)
			Result.success()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			if (runAttemptCount < 5 && isLibrarySyncRetryable(e)) Result.retry()
			else Result.failure()
		}
	}
}

@Singleton
class LibrarySyncScheduler @Inject constructor(private val workManager: WorkManager) {
	fun schedule() {
		val request =
			PeriodicWorkRequestBuilder<LibrarySyncWorker>(6, TimeUnit.HOURS)
				.setInputData(workDataOf("dispatch" to true))
				.setConstraints(network())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
				.build()
		workManager.enqueueUniquePeriodicWork(
			"library_sync_periodic",
			ExistingPeriodicWorkPolicy.KEEP,
			request,
		)
	}

	fun manual(id: LibrarySyncServiceId) {
		val request =
			OneTimeWorkRequestBuilder<LibrarySyncWorker>()
				.setInputData(workDataOf("service" to id.name))
				.setConstraints(network())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
				.build()
		workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
	}

	fun cancel(id: LibrarySyncServiceId) {
		workManager.cancelUniqueWork(workName(id))
	}

	private fun network() =
		Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

	companion object {
		fun workName(id: LibrarySyncServiceId) = "library_sync_${id.name}"
	}
}
