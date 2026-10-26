package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.CancellationException
import org.koitharu.kotatsu.parsers.model.Manga

enum class MigrationBatchStatus {
	UNCHECKED, QUEUED, CHECKING, MIGRATING, READY, NEEDS_REVIEW, NO_REPLACEMENT,
	INCOMPLETE, HEALTHY, MIGRATED, INTERRUPTED, ERROR,
}

enum class MigrationBatchAction { CHECK, MIGRATE }

data class MigrationBatchRequest(
	val mangaId: Long,
	val source: String,
	val action: MigrationBatchAction,
	val selection: MigrationSelection? = null,
	val searchScope: MigrationSearchScope = MigrationSearchScope.ALL,
)

data class MigrationBatchOutcome(
	val status: MigrationBatchStatus,
	val selection: MigrationSelection? = null,
	val destinationId: Long? = null,
)

data class MigrationBatchSnapshot(
	val mangaId: Long,
	val source: String,
	val generation: Long,
	val batchName: String,
	val outcome: MigrationBatchOutcome,
	val running: Boolean = false,
	val searchProgress: MigrationSearchProgress? = null,
	val searchScope: MigrationSearchScope = MigrationSearchScope.ALL,
)

object MigrationBatchPolicy {
	fun assess(discovery: MigrationDiscovery, changed: Boolean = false): MigrationBatchOutcome = when {
		discovery.currentSourceReadable -> MigrationBatchOutcome(MigrationBatchStatus.HEALTHY)
		discovery.incomplete -> MigrationBatchOutcome(MigrationBatchStatus.INCOMPLETE)
		discovery.assessment?.state == MigrationMatchState.READY && !changed -> MigrationBatchOutcome(
			MigrationBatchStatus.READY, discovery.assessment.best?.manga?.let(MigrationSelection::of),
		)
		discovery.assessment?.state == MigrationMatchState.NO_REPLACEMENT -> MigrationBatchOutcome(MigrationBatchStatus.NO_REPLACEMENT)
		else -> MigrationBatchOutcome(MigrationBatchStatus.NEEDS_REVIEW)
	}

	fun latest(snapshots: Collection<MigrationBatchSnapshot>): Map<Long, MigrationBatchSnapshot> =
		snapshots.groupBy { it.mangaId }.mapValues { (_, items) ->
			val generation = items.maxOf(MigrationBatchSnapshot::generation)
			val newest = items.filter { it.generation == generation }
			// An inconsistent duplicate generation is never permission to run unattended.
			if (newest.size == 1) newest.single() else newest.sortedBy { it.batchName }.first().copy(
				outcome = MigrationBatchOutcome(MigrationBatchStatus.INTERRUPTED), running = newest.any { it.running },
			)
		}

	fun readyRequests(
		manga: Collection<Manga>,
		snapshots: Map<Long, MigrationBatchSnapshot>,
		searchScope: MigrationSearchScope? = null,
	): List<MigrationBatchRequest> = manga.mapNotNull { item ->
		val snapshot = snapshots[item.id] ?: return@mapNotNull null
		if (searchScope != null && snapshot.searchScope != searchScope) return@mapNotNull null
		if (snapshot.source != item.source.name || snapshot.outcome.status != MigrationBatchStatus.READY || snapshot.running) return@mapNotNull null
		val selection = snapshot.outcome.selection ?: return@mapNotNull null
		MigrationBatchRequest(
			item.id,
			item.source.name,
			MigrationBatchAction.MIGRATE,
			selection,
			searchScope = snapshot.searchScope,
		)
	}

	/** WorkManager persists each explicit outcome. Domain failures do not cancel later batch items. */
	suspend fun process(
		request: MigrationBatchRequest,
		canAccess: suspend () -> Boolean,
		discover: suspend () -> MigrationDiscovery,
		migrate: suspend (MigrationSelection) -> Manga?,
	): MigrationBatchOutcome = try {
		if (!canAccess()) MigrationBatchOutcome(MigrationBatchStatus.INTERRUPTED, destinationId = request.selection?.id)
		else when (request.action) {
			MigrationBatchAction.CHECK -> assess(discover())
			MigrationBatchAction.MIGRATE -> {
				val selected = checkNotNull(request.selection) { "Batch migration requires a READY selection" }
				val result = migrate(selected)
				if (result == null) MigrationBatchOutcome(MigrationBatchStatus.HEALTHY)
				else MigrationBatchOutcome(MigrationBatchStatus.MIGRATED, destinationId = result.id)
			}
		}
	} catch (error: CancellationException) {
		throw error
	} catch (error: AutoFixUseCase.SelectionChangedException) {
		assess(error.discovery, changed = true)
	} catch (error: MigrateUseCase.SourceChangedException) {
		MigrationBatchOutcome(MigrationBatchStatus.INTERRUPTED, destinationId = request.selection?.id)
	} catch (error: Exception) {
		MigrationBatchOutcome(MigrationBatchStatus.ERROR, destinationId = request.selection?.id)
	}
}
