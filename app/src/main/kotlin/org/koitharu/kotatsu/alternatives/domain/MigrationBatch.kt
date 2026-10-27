package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.CancellationException
import org.koitharu.kotatsu.parsers.model.Manga
import java.security.MessageDigest

enum class MigrationBatchStatus {
	UNCHECKED, QUEUED, CHECKING, MIGRATING, READY, NEEDS_REVIEW, NO_REPLACEMENT,
	INCOMPLETE, HEALTHY, MIGRATED, COPIED, INTERRUPTED, ERROR,
}

enum class MigrationBatchAction { CHECK, MIGRATE, COPY }

enum class MigrationBatchDiscoveryMode {
	/** Legacy compatibility mode retained for already-persisted WorkManager rows. */
	CHECK_CURRENT,
	/** Legacy #762 direct-search mode retained for already-persisted WorkManager rows. */
	DIRECT_SEARCH,
	/** Current user-facing migration flow, modeled after Yokai. */
	YOKAI,
}

data class MigrationBatchRequest(
	val mangaId: Long,
	val source: String,
	val action: MigrationBatchAction,
	val selection: MigrationSelection? = null,
	val searchScope: MigrationSearchScope = MigrationSearchScope.ALL,
	val discoveryMode: MigrationBatchDiscoveryMode = MigrationBatchDiscoveryMode.YOKAI,
	/** Ordered source identities. FIRST_MATCH must preserve this exact order. */
	val selectedSourceNames: List<String> = emptyList(),
	val sourceStrategy: MigrationSourceStrategy = MigrationSourceStrategy.FIRST_MATCH,
	val extraSearchParams: String? = null,
	val configKey: String = migrationConfigKey(selectedSourceNames, sourceStrategy, extraSearchParams),
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
	val discoveryMode: MigrationBatchDiscoveryMode = MigrationBatchDiscoveryMode.CHECK_CURRENT,
	val selectedSourceNames: List<String> = emptyList(),
	val sourceStrategy: MigrationSourceStrategy = MigrationSourceStrategy.FIRST_MATCH,
	val extraSearchParams: String? = null,
	val configKey: String = LEGACY_MIGRATION_CONFIG,
)

internal const val LEGACY_MIGRATION_CONFIG = "legacy"

fun migrationConfigKey(
	selectedSourceNames: List<String>,
	strategy: MigrationSourceStrategy,
	extraSearchParams: String?,
): String {
	if (selectedSourceNames.isEmpty()) return LEGACY_MIGRATION_CONFIG
	val canonical = buildString {
		append(strategy.name).append('\n')
		append(extraSearchParams.orEmpty().trim()).append('\n')
		selectedSourceNames.forEach { append(it).append('\n') }
	}
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical.toByteArray(Charsets.UTF_8))
		.take(12)
		.joinToString("") { "%02x".format(it) }
}

object MigrationBatchPolicy {
	fun assess(
		discovery: MigrationDiscovery,
		changed: Boolean = false,
		rawSelection: Boolean = false,
	): MigrationBatchOutcome = when {
		discovery.currentSourceReadable -> MigrationBatchOutcome(MigrationBatchStatus.HEALTHY)
		discovery.incomplete -> MigrationBatchOutcome(MigrationBatchStatus.INCOMPLETE)
		discovery.assessment?.state == MigrationMatchState.READY && !changed -> MigrationBatchOutcome(
			MigrationBatchStatus.READY,
			discovery.assessment.best?.manga?.let {
				if (rawSelection) MigrationSelection.fromSearchResult(it) else MigrationSelection.of(it)
			},
		)
		discovery.assessment?.state == MigrationMatchState.NO_REPLACEMENT -> MigrationBatchOutcome(MigrationBatchStatus.NO_REPLACEMENT)
		else -> MigrationBatchOutcome(MigrationBatchStatus.NEEDS_REVIEW)
	}

	fun latest(snapshots: Collection<MigrationBatchSnapshot>): Map<Long, MigrationBatchSnapshot> =
		snapshots.groupBy { it.mangaId }.mapValues { (_, items) ->
			val generation = items.maxOf(MigrationBatchSnapshot::generation)
			val newest = items.filter { it.generation == generation }
			if (newest.size == 1) newest.single() else newest.sortedBy { it.batchName }.first().copy(
				outcome = MigrationBatchOutcome(MigrationBatchStatus.INTERRUPTED), running = newest.any { it.running },
			)
		}

	fun readyRequests(
		manga: Collection<Manga>,
		snapshots: Map<Long, MigrationBatchSnapshot>,
		searchScope: MigrationSearchScope? = null,
		discoveryMode: MigrationBatchDiscoveryMode? = null,
		configKey: String? = null,
		action: MigrationBatchAction = MigrationBatchAction.MIGRATE,
	): List<MigrationBatchRequest> {
		require(action == MigrationBatchAction.MIGRATE || action == MigrationBatchAction.COPY)
		return manga.mapNotNull { item ->
			val snapshot = snapshots[item.id] ?: return@mapNotNull null
			if (configKey != null && snapshot.configKey != configKey) return@mapNotNull null
			if (searchScope != null && snapshot.searchScope != searchScope) return@mapNotNull null
			if (discoveryMode != null && snapshot.discoveryMode != discoveryMode) return@mapNotNull null
			if (snapshot.source != item.source.name || snapshot.outcome.status != MigrationBatchStatus.READY || snapshot.running) return@mapNotNull null
			val selection = snapshot.outcome.selection ?: return@mapNotNull null
			MigrationBatchRequest(
				mangaId = item.id,
				source = item.source.name,
				action = action,
				selection = selection,
				searchScope = snapshot.searchScope,
				discoveryMode = snapshot.discoveryMode,
				selectedSourceNames = snapshot.selectedSourceNames,
				sourceStrategy = snapshot.sourceStrategy,
				extraSearchParams = snapshot.extraSearchParams,
				configKey = snapshot.configKey,
			)
		}
	}

	/** WorkManager persists each explicit outcome. Domain failures do not cancel later batch items. */
	suspend fun process(
		request: MigrationBatchRequest,
		canAccess: suspend () -> Boolean,
		discover: suspend () -> MigrationDiscovery,
		migrate: suspend (MigrationSelection) -> Manga?,
		copy: suspend (MigrationSelection) -> Manga? = migrate,
	): MigrationBatchOutcome = try {
		if (!canAccess()) MigrationBatchOutcome(MigrationBatchStatus.INTERRUPTED, destinationId = request.selection?.id)
		else when (request.action) {
			MigrationBatchAction.CHECK -> assess(
				discover(),
				rawSelection = request.discoveryMode != MigrationBatchDiscoveryMode.CHECK_CURRENT,
			)
			MigrationBatchAction.MIGRATE -> {
				val selected = checkNotNull(request.selection) { "Batch migration requires a READY selection" }
				val result = migrate(selected)
				if (result == null) MigrationBatchOutcome(MigrationBatchStatus.HEALTHY)
				else MigrationBatchOutcome(MigrationBatchStatus.MIGRATED, destinationId = result.id)
			}
			MigrationBatchAction.COPY -> {
				val selected = checkNotNull(request.selection) { "Batch copy requires a READY selection" }
				val result = copy(selected)
				if (result == null) MigrationBatchOutcome(MigrationBatchStatus.HEALTHY)
				else MigrationBatchOutcome(MigrationBatchStatus.COPIED, destinationId = result.id)
			}
		}
	} catch (error: CancellationException) {
		throw error
	} catch (error: AutoFixUseCase.SelectionChangedException) {
		assess(
			error.discovery,
			changed = true,
			rawSelection = request.discoveryMode != MigrationBatchDiscoveryMode.CHECK_CURRENT,
		)
	} catch (error: MigrateUseCase.SourceChangedException) {
		MigrationBatchOutcome(MigrationBatchStatus.INTERRUPTED, destinationId = request.selection?.id)
	} catch (error: Exception) {
		MigrationBatchOutcome(MigrationBatchStatus.ERROR, destinationId = request.selection?.id)
	}
}
