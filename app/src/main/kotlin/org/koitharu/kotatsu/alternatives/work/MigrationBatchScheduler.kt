package org.koitharu.kotatsu.alternatives.work

import androidx.work.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
		// Durable terminal markers are intentionally kept unfinished behind a delayed sentinel so
		// WorkManager cannot prune them across process restart. They do not own the active queue.
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

	/** Persist Yokai's per-item Skip as a new session outcome without requiring network access. */
	suspend fun markSkipped(
		mangaId: Long,
		source: String,
		configKey: String,
		discoveryMode: MigrationBatchDiscoveryMode = MigrationBatchDiscoveryMode.YOKAI,
	): Boolean = schedulingMutex.withLock {
		val existing = workManager.getWorkInfosByTag(MigrationBatchWork.TAG).await()
		if (existing.any { !it.state.isFinished && !MigrationBatchWork.isTerminal(it) }) return@withLock false
		val previous = existing.maxOfOrNull { MigrationBatchWork.generation(it.tags) } ?: 0L
		check(previous < Long.MAX_VALUE)
		val generation = previous + 1L
		val batchName = "${MigrationBatchWork.TAG}:skip:${UUID.randomUUID()}"
		val marker = OneTimeWorkRequestBuilder<MigrationBatchCancellationMarkerWorker>()
			.setInputData(MigrationBatchWork.output(MigrationBatchOutcome(MigrationBatchStatus.SKIPPED)))
			.keepResultsForAtLeast(30, TimeUnit.DAYS)
			.apply {
				MigrationBatchWork.tags(
					mangaId = mangaId,
					source = source,
					generation = generation,
					batch = batchName,
					discoveryMode = discoveryMode,
					configKey = configKey,
				).forEach { addTag(it) }
			}
			.build()
		workManager.enqueue(marker).await()
		val finished = workManager.getWorkInfoByIdFlow(marker.id).first { it.state.isFinished }
		finished.state == WorkInfo.State.SUCCEEDED
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

		// Cancelled dependency-chain nodes that never started can disappear when WorkManager is opened
		// by a fresh process. Preserve their terminal identity as new WorkManager records. The marker
		// records remain BLOCKED behind one delayed sentinel; this prevents pruning without introducing
		// another persistence authority or scheduling hundreds of active timers.
		val afterById = workManager.getWorkInfosByTag(MigrationBatchWork.TAG).await().associateBy(WorkInfo::id)
		val currentGeneration = before.maxOfOrNull { MigrationBatchWork.generation(it.tags) } ?: 0L
		check(currentGeneration < Long.MAX_VALUE)
		val markerGeneration = currentGeneration + 1L
		val markerBatch = "$batchName:cancelled:$markerGeneration"
		val markers = pendingBlocked.mapNotNull { (info, snapshot) ->
			val after = afterById[info.id]
			if (after != null && after.state != WorkInfo.State.CANCELLED) return@mapNotNull null
			OneTimeWorkRequestBuilder<MigrationBatchCancellationMarkerWorker>()
				.setInputData(MigrationBatchWork.output(MigrationBatchOutcome(
					MigrationBatchStatus.INTERRUPTED,
					destinationId = snapshot.outcome.destinationId,
				)))
				.keepResultsForAtLeast(30, TimeUnit.DAYS)
				.addTag(MigrationBatchWork.terminalTag(MigrationBatchStatus.INTERRUPTED))
				.apply {
					MigrationBatchWork.tags(
						mangaId = snapshot.mangaId,
						source = snapshot.source,
						generation = markerGeneration,
						batch = markerBatch,
						destinationId = snapshot.outcome.destinationId,
						searchScope = snapshot.searchScope,
						discoveryMode = snapshot.discoveryMode,
						configKey = snapshot.configKey,
					).forEach { addTag(it) }
				}
				.build()
		}
		if (markers.isNotEmpty()) {
			val sentinel = OneTimeWorkRequestBuilder<MigrationBatchCancellationMarkerWorker>()
				.setInitialDelay(30, TimeUnit.DAYS)
				.build()
			workManager.beginWith(sentinel).then(markers).enqueue().await()
		}
	}
}
