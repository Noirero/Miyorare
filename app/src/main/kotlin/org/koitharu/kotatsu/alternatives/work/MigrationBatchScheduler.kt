package org.koitharu.kotatsu.alternatives.work

import androidx.work.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.alternatives.domain.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MigrationBatchScheduler @Inject constructor(private val workManager: WorkManager) {
	private val schedulingMutex = Mutex()

	fun observe(): Flow<List<WorkInfo>> = workManager.getWorkInfosByTagFlow(MigrationBatchWork.TAG)

	suspend fun enqueue(requests: Collection<MigrationBatchRequest>): Boolean = schedulingMutex.withLock {
		val unique = requests.distinctBy { it.mangaId }
		if (unique.isEmpty()) return@withLock false
		val existing = workManager.getWorkInfosByTag(MigrationBatchWork.TAG).await()
		// Durable terminal markers remain unfinished for their retention window. They reconstruct
		// cancellation state but do not own the active migration queue.
		if (existing.any { !it.state.isFinished && !MigrationBatchWork.isTerminal(it) }) return@withLock false
		val previous = existing.maxOfOrNull { MigrationBatchWork.generation(it.tags) } ?: 0L
		check(previous < Long.MAX_VALUE)
		val generation = previous + 1L
		val batchName = "${MigrationBatchWork.TAG}:${UUID.randomUUID()}"
		val tasks = unique.map { item ->
			OneTimeWorkRequestBuilder<MigrationBatchWorker>()
				.setInputData(MigrationBatchWork.input(item))
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
				.keepResultsForAtLeast(30, TimeUnit.DAYS)
				.apply { MigrationBatchWork.tags(item, generation, batchName).forEach { addTag(it) } }
				.build()
		}
		var chain = workManager.beginUniqueWork(batchName, ExistingWorkPolicy.KEEP, tasks.first())
		for (task in tasks.drop(1)) chain = chain.then(task)
		// Enqueue the complete graph once; requests/outcomes survive process death in WorkManager.
		chain.enqueue().await()
		true
	}

	suspend fun cancel(batchName: String) = schedulingMutex.withLock {
		require(batchName.startsWith(MigrationBatchWork.TAG + ":"))
		val before = workManager.getWorkInfosByTag(MigrationBatchWork.TAG).await()
		val batch = before.mapNotNull { info ->
			MigrationBatchWork.snapshot(info)?.takeIf { it.batchName == batchName }?.let { snapshot -> info to snapshot }
		}
		val pendingBlocked = batch.filter { (info, _) -> !info.state.isFinished && info.state != WorkInfo.State.RUNNING }
		if (batch.none { (info, _) -> !info.state.isFinished }) return@withLock

		workManager.cancelUniqueWork(batchName).await()
		if (pendingBlocked.isEmpty()) return@withLock

		// A cancelled dependency that never became runnable can have last_enqueue_time == 0 in
		// WorkManager. Its normal retention window is therefore already expired from the database's
		// point of view and it can be pruned as soon as a fresh process opens WorkDatabase. Preserve
		// each never-started identity as an independent delayed terminal WorkRequest instead of another
		// BLOCKED dependency: an independent request receives a real last_enqueue_time immediately,
		// remains unfinished for the retention window, and is ignored by active-queue ownership because
		// of its terminal tag.
		val currentGeneration = before.maxOfOrNull { MigrationBatchWork.generation(it.tags) } ?: 0L
		check(currentGeneration < Long.MAX_VALUE)
		val markerGeneration = currentGeneration + 1L
		val markerBatch = "$batchName:cancelled:$markerGeneration"
		val markers = pendingBlocked.map { (_, snapshot) ->
			OneTimeWorkRequestBuilder<MigrationBatchCancellationMarkerWorker>()
				.setInitialDelay(30, TimeUnit.DAYS)
				.setInputData(MigrationBatchWork.output(MigrationBatchOutcome(
					MigrationBatchStatus.INTERRUPTED,
					destinationId = snapshot.outcome.destinationId,
				)))
				.keepResultsForAtLeast(30, TimeUnit.DAYS)
				.addTag(MigrationBatchWork.terminalTag(MigrationBatchStatus.INTERRUPTED))
				.apply {
					MigrationBatchWork.tags(
						snapshot.mangaId,
						snapshot.source,
						markerGeneration,
						markerBatch,
						snapshot.outcome.destinationId,
					).forEach { addTag(it) }
				}
				.build()
		}
		workManager.enqueue(markers).await()
	}
}
