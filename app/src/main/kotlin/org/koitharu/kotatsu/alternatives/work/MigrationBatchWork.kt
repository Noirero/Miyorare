package org.koitharu.kotatsu.alternatives.work

import androidx.work.Data
import androidx.work.WorkInfo
import org.koitharu.kotatsu.alternatives.domain.*

/** WorkManager is the durable authority for requests, outcomes, cancellation and queue ownership. */
internal object MigrationBatchWork {
	const val TAG = "migration_batch"
	private const val ID = "migration_id:"
	private const val SOURCE = "migration_source:"
	private const val GENERATION = "migration_generation:"
	private const val BATCH = "migration_batch_name:"
	private const val TARGET = "migration_target:"
	private const val SCOPE = "migration_scope:"
	private const val MODE = "migration_mode:"
	private const val CONFIG = "migration_config:"
	private const val TERMINAL = "migration_terminal:"

	fun tags(request: MigrationBatchRequest, generation: Long, batch: String): List<String> =
		tags(
			mangaId = request.mangaId,
			source = request.source,
			generation = generation,
			batch = batch,
			destinationId = request.selection?.id,
			searchScope = request.searchScope,
			discoveryMode = request.discoveryMode,
			configKey = request.configKey,
		)

	fun tags(
		mangaId: Long,
		source: String,
		generation: Long,
		batch: String,
		destinationId: Long? = null,
		searchScope: MigrationSearchScope = MigrationSearchScope.ALL,
		discoveryMode: MigrationBatchDiscoveryMode = MigrationBatchDiscoveryMode.CHECK_CURRENT,
		configKey: String = LEGACY_MIGRATION_CONFIG,
	): List<String> = buildList {
		add(TAG); add(ID + mangaId); add(SOURCE + source)
		add(GENERATION + generation); add(BATCH + batch); add(SCOPE + searchScope.name); add(MODE + discoveryMode.name)
		add(CONFIG + configKey)
		destinationId?.let { add(TARGET + it) }
	}

	fun terminalTag(status: MigrationBatchStatus): String {
		require(status == MigrationBatchStatus.INTERRUPTED)
		return TERMINAL + status.name
	}

	fun isTerminal(info: WorkInfo): Boolean = info.tags.any { it.startsWith(TERMINAL) }

	fun input(request: MigrationBatchRequest): Data = Data.Builder()
		.putLong("request_id", request.mangaId)
		.putString("request_source", request.source)
		.putString("request_action", request.action.name)
		.putString("request_scope", request.searchScope.name)
		.putString("request_mode", request.discoveryMode.name)
		.putStringArray("request_sources", request.selectedSourceNames.toTypedArray())
		.putString("request_strategy", request.sourceStrategy.name)
		.putString("request_extra_search", request.extraSearchParams)
		.putString("request_config", request.configKey)
		.putString("request_target_source", request.selection?.source)
		.putLong("request_target_id", request.selection?.id ?: 0L)
		.putString("request_fingerprint", request.selection?.fingerprint)
		.build()

	fun request(data: Data): MigrationBatchRequest {
		val selectedSources = data.getStringArray("request_sources")?.toList().orEmpty()
		val strategy = data.getString("request_strategy")
			?.let { raw -> MigrationSourceStrategy.entries.find { it.name == raw } }
			?: MigrationSourceStrategy.FIRST_MATCH
		val extra = data.getString("request_extra_search")
		return MigrationBatchRequest(
			mangaId = data.getLong("request_id", 0L),
			source = checkNotNull(data.getString("request_source")),
			action = MigrationBatchAction.valueOf(checkNotNull(data.getString("request_action"))),
			selection = data.getString("request_fingerprint")?.let { fingerprint ->
				MigrationSelection(checkNotNull(data.getString("request_target_source")), data.getLong("request_target_id", 0L), fingerprint)
			},
			searchScope = data.getString("request_scope")
				?.let { raw -> MigrationSearchScope.entries.find { it.name == raw } }
				?: MigrationSearchScope.ALL,
			discoveryMode = data.getString("request_mode")
				?.let { raw -> MigrationBatchDiscoveryMode.entries.find { it.name == raw } }
				?: MigrationBatchDiscoveryMode.CHECK_CURRENT,
			selectedSourceNames = selectedSources,
			sourceStrategy = strategy,
			extraSearchParams = extra,
			configKey = data.getString("request_config")
				?: migrationConfigKey(selectedSources, strategy, extra),
		)
	}

	fun output(outcome: MigrationBatchOutcome, searchProgress: MigrationSearchProgress? = null): Data = Data.Builder()
		.putString("result_status", outcome.status.name)
		.putString("result_target_source", outcome.selection?.source)
		.putString("result_target_title", outcome.destinationTitle)
		.putLong("result_target_id", outcome.selection?.id ?: outcome.destinationId ?: 0L)
		.putString("result_fingerprint", outcome.selection?.fingerprint)
		.putString("search_phase", searchProgress?.phase?.name)
		.putString("search_query", searchProgress?.query)
		.putInt("search_query_index", searchProgress?.queryIndex ?: 0)
		.putInt("search_query_total", searchProgress?.queryTotal ?: 0)
		.putInt("search_source_completed", searchProgress?.completedSources ?: 0)
		.putInt("search_source_total", searchProgress?.totalSources ?: 0)
		.putInt("search_candidate_count", searchProgress?.candidateCount ?: 0)
		.putString("search_last_source", searchProgress?.lastSource)
		.build()

	fun generation(tags: Set<String>): Long = tags.firstOrNull { it.startsWith(GENERATION) }
		?.removePrefix(GENERATION)?.toLongOrNull() ?: 0L

	fun searchScope(tags: Set<String>): MigrationSearchScope = tags.firstOrNull { it.startsWith(SCOPE) }
		?.removePrefix(SCOPE)
		?.let { raw -> MigrationSearchScope.entries.find { it.name == raw } }
		?: MigrationSearchScope.ALL

	fun discoveryMode(tags: Set<String>): MigrationBatchDiscoveryMode = tags.firstOrNull { it.startsWith(MODE) }
		?.removePrefix(MODE)
		?.let { raw -> MigrationBatchDiscoveryMode.entries.find { it.name == raw } }
		?: MigrationBatchDiscoveryMode.CHECK_CURRENT

	fun configKey(tags: Set<String>): String = tags.firstOrNull { it.startsWith(CONFIG) }
		?.removePrefix(CONFIG)
		?: LEGACY_MIGRATION_CONFIG

	private fun searchProgress(data: Data): MigrationSearchProgress? {
		val phase = data.getString("search_phase")
			?.let { raw -> MigrationSearchPhase.entries.find { it.name == raw } }
			?: return null
		return MigrationSearchProgress(
			phase = phase,
			query = data.getString("search_query"),
			queryIndex = data.getInt("search_query_index", 0),
			queryTotal = data.getInt("search_query_total", 0),
			completedSources = data.getInt("search_source_completed", 0),
			totalSources = data.getInt("search_source_total", 0),
			candidateCount = data.getInt("search_candidate_count", 0),
			lastSource = data.getString("search_last_source"),
		)
	}

	fun snapshot(info: WorkInfo): MigrationBatchSnapshot? {
		val mangaId = info.tags.firstOrNull { it.startsWith(ID) }?.removePrefix(ID)?.toLongOrNull() ?: return null
		val source = info.tags.firstOrNull { it.startsWith(SOURCE) }?.removePrefix(SOURCE) ?: return null
		val batch = info.tags.firstOrNull { it.startsWith(BATCH) }?.removePrefix(BATCH) ?: return null
		val terminalStatus = info.tags.firstOrNull { it.startsWith(TERMINAL) }
			?.removePrefix(TERMINAL)?.let { raw -> MigrationBatchStatus.entries.find { it.name == raw } }
		val data = if (info.state == WorkInfo.State.RUNNING) info.progress else info.outputData
		val status = terminalStatus ?: when (info.state) {
			WorkInfo.State.CANCELLED, WorkInfo.State.FAILED -> MigrationBatchStatus.INTERRUPTED
			WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> MigrationBatchStatus.QUEUED
			else -> data.getString("result_status")?.let { raw -> MigrationBatchStatus.entries.find { it.name == raw } }
				?: if (info.state == WorkInfo.State.RUNNING) MigrationBatchStatus.QUEUED else MigrationBatchStatus.ERROR
		}
		val selection = if (status == MigrationBatchStatus.READY) data.getString("result_fingerprint")?.let {
			MigrationSelection(data.getString("result_target_source") ?: return null, data.getLong("result_target_id", 0L), it)
		} else null
		val destinationId = data.getLong("result_target_id", 0L).takeIf { it != 0L }
			?: info.tags.firstOrNull { it.startsWith(TARGET) }?.removePrefix(TARGET)?.toLongOrNull()
		return MigrationBatchSnapshot(
			mangaId = mangaId,
			source = source,
			generation = generation(info.tags),
			batchName = batch,
			outcome = MigrationBatchOutcome(status, selection, destinationId, data.getString("result_target_title")),
			running = terminalStatus == null && !info.state.isFinished,
			searchProgress = searchProgress(data),
			searchScope = searchScope(info.tags),
			discoveryMode = discoveryMode(info.tags),
			// WorkInfo intentionally does not expose inputData. Full ordered source/strategy config remains
			// durable inside the worker request; history/status matching uses the explicit config tag below.
			selectedSourceNames = emptyList(),
			sourceStrategy = MigrationSourceStrategy.FIRST_MATCH,
			extraSearchParams = null,
			configKey = configKey(info.tags),
		)
	}
}
