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
		// Durable terminal markers are unfinished WorkManager records blocked behind one delayed
		// sentinel. Their terminal tags reconstruct cancellation without owning the active queue.
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

		// Dependency-chain nodes that had not started can disappear when WorkManager is reopened in a
		// fresh process. Cancellation state propagation is asynchronous with respect to the operation
		// returned by cancelUniqueWork(), so deciding whether to create a marker from an immediate
		// post-cancel WorkInfo snapshot races that propagation. Preserve every node that was pending
		// before cancellation instead; the newer generation lets normal snapshot reduction prefer the
		// durable terminal marker without introducing another persistence authority.
		val currentGeneration = before.maxOfOrNull { MigrationBatchWork.generation(it.tags) } ?: 0L
		check(currentGeneration < Long.MAX_VALUE)
		val markerGeneration = currentGeneration + 1L
		val markerBatch = "$batchName:cancelled:$markerGeneration"
		val markers = pendingBlocked.map { (_, snapshot) ->
			OneTimeWorkRequestBuilder<MigrationBatchCancellationMarkerWorker>()
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
		// Keep one delayed sentinel as the only schedulable timer and block every terminal marker
		// behind it. MigrationBatchWork.snapshot() reads the terminal tag before WorkInfo state, so a
		// BLOCKED marker is already a durable INTERRUPTED outcome. Because the marker itself remains
		// unfinished, process-start pruning cannot delete it, and hundreds of long-lived jobs are not
		// scheduled independently.
		val sentinel = OneTimeWorkRequestBuilder<MigrationBatchCancellationMarkerWorker>()
			.setInitialDelay(30, TimeUnit.DAYS)
			.build()
		workManager.beginWith(sentinel).then(markers).enqueue().await()
	}
}
