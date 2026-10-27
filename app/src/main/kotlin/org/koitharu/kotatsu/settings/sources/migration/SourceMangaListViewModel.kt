package org.koitharu.kotatsu.settings.sources.migration

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.alternatives.domain.*
import org.koitharu.kotatsu.alternatives.work.MigrationBatchScheduler
import org.koitharu.kotatsu.alternatives.work.MigrationBatchWork
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class SourceMangaListViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val database: MangaDatabase,
	private val batchScheduler: MigrationBatchScheduler,
	private val yokaiSearch: YokaiStyleMigrationSearchUseCase,
	private val migrationPreferences: MigrationSessionPreferences,
) : ViewModel() {

	val sourceTitle: String = savedStateHandle[SourceMangaListFragment.ARG_SOURCE_TITLE] ?: "Manga migration"
	private val sourceKeys: List<String> = savedStateHandle.get<Array<String>>(SourceMangaListFragment.ARG_SOURCE_KEYS)
		?.toList().orEmpty()
	private val explicitMangaIds: List<Long> = savedStateHandle.get<LongArray>(SourceMangaListFragment.ARG_MANGA_IDS)
		?.toList().orEmpty()

	private val _state = MutableStateFlow(SourceMangaListState(
		filter = savedStateHandle.get<String>(FILTER)?.let { raw -> MigrationBatchStatus.entries.find { it.name == raw } },
		selectedSourceNames = migrationPreferences.selectedSourceNames,
		sourceStrategy = migrationPreferences.sourceStrategy,
		extraSearchParams = migrationPreferences.extraSearchParams.orEmpty(),
		skipSetup = migrationPreferences.skipSetup,
	))
	val state: StateFlow<SourceMangaListState> = _state.asStateFlow()

	private var loadJob: Job? = null
	private var sourceLoadJob: Job? = null
	private var autoStartConsumed = false

	fun toggleSelection(mangaId: Long) {
		_state.update { current ->
			current.copy(selectedIds = current.selectedIds.toMutableSet().apply { if (!add(mangaId)) remove(mangaId) })
		}
	}

	fun clearSelection() { _state.update { it.copy(selectedIds = emptySet()) } }

	fun selectAll() { _state.update { current -> current.copy(selectedIds = current.visibleManga.mapTo(linkedSetOf()) { it.id }) } }

	fun selectRange() {
		_state.update { current ->
			val visible = current.visibleManga
			val selectedIndices = visible.mapIndexedNotNull { index, manga -> index.takeIf { manga.id in current.selectedIds } }
			if (selectedIndices.size < 2) return@update current
			val range = selectedIndices.first()..selectedIndices.last()
			current.copy(selectedIds = current.selectedIds + range.map { visible[it].id })
		}
	}

	fun removeSelected() {
		val ids = state.value.selectedIds
		if (ids.isEmpty()) return
		viewModelScope.launch {
			withContext(Dispatchers.IO) {
				database.withTransaction {
					ids.forEach { mangaId ->
						database.getFavouritesDao().delete(mangaId)
						database.getHistoryDao().delete(mangaId)
					}
				}
			}
			clearSelection()
		}
	}

	fun setFilter(filter: MigrationBatchStatus?) {
		savedStateHandle[FILTER] = filter?.name
		_state.update { it.copy(filter = filter) }
	}

	fun toggleSource(sourceName: String) {
		val current = state.value
		if (current.batchBusy || current.scheduling || current.sourceSelectionLoading) return
		if (current.availableSources.none { it.name == sourceName }) return
		val next = current.selectedSourceNames.toMutableList()
		if (!next.remove(sourceName)) next += sourceName
		_state.update { it.copy(selectedSourceNames = next, failed = false) }
	}

	fun selectAllSources() {
		val current = state.value
		if (current.batchBusy || current.scheduling || current.sourceSelectionLoading) return
		_state.update { it.copy(selectedSourceNames = current.availableSources.map(MangaSource::name), failed = false) }
	}

	fun clearSources() {
		val current = state.value
		if (current.batchBusy || current.scheduling) return
		_state.update { it.copy(selectedSourceNames = emptyList(), failed = false) }
	}

	fun moveSource(sourceName: String, delta: Int) {
		val current = state.value
		if (current.batchBusy || current.scheduling || delta == 0) return
		val next = current.selectedSourceNames.toMutableList()
		val from = next.indexOf(sourceName)
		if (from < 0) return
		val to = (from + delta).coerceIn(0, next.lastIndex)
		if (from == to) return
		val value = next.removeAt(from)
		next.add(to, value)
		_state.update { it.copy(selectedSourceNames = next, failed = false) }
	}

	fun setSourceStrategy(strategy: MigrationSourceStrategy) {
		if (state.value.batchBusy || state.value.scheduling) return
		_state.update { it.copy(sourceStrategy = strategy, failed = false) }
	}

	fun setExtraSearchParams(value: String) {
		if (state.value.batchBusy || state.value.scheduling) return
		_state.update { it.copy(extraSearchParams = value, failed = false) }
	}

	fun setSkipSetup(value: Boolean) {
		if (state.value.batchBusy || state.value.scheduling) return
		_state.update { it.copy(skipSetup = value) }
	}

	fun searchAll() {
		val current = state.value
		if (current.scheduling || current.batchBusy || current.selectedSourceNames.isEmpty()) return
		val targetIds = current.selectedIds.takeIf { it.isNotEmpty() }
		val targets = current.manga.filter { targetIds == null || it.id in targetIds }
		if (targets.isEmpty()) return
		migrationPreferences.save(
			sources = current.selectedSourceNames,
			strategy = current.sourceStrategy,
			extraSearchParams = current.extraSearchParams,
			skipSetup = current.skipSetup,
		)
		val configKey = current.configKey
		enqueue(targets.map { manga ->
			MigrationBatchRequest(
				mangaId = manga.id,
				source = manga.source.name,
				action = MigrationBatchAction.CHECK,
				discoveryMode = MigrationBatchDiscoveryMode.YOKAI,
				selectedSourceNames = current.selectedSourceNames,
				sourceStrategy = current.sourceStrategy,
				extraSearchParams = current.extraSearchParams.trim().takeIf(String::isNotEmpty),
				configKey = configKey,
			)
		})
	}

	fun migrateReady() {
		val current = state.value
		enqueue(MigrationBatchPolicy.readyRequests(
			manga = current.manga,
			snapshots = current.batchItems,
			discoveryMode = MigrationBatchDiscoveryMode.YOKAI,
			configKey = current.configKey,
		))
	}

	private fun enqueue(requests: List<MigrationBatchRequest>) {
		if (state.value.scheduling || state.value.batchBusy || requests.isEmpty()) return
		_state.update { it.copy(scheduling = true, failed = false) }
		viewModelScope.launch {
			runCatchingCancellable { batchScheduler.enqueue(requests) }
				.onSuccess { accepted -> _state.update { it.copy(scheduling = false, failed = !accepted, selectedIds = emptySet()) } }
				.onFailure { _state.update { it.copy(scheduling = false, failed = true) } }
		}
	}

	fun cancelBatch() {
		val batch = state.value.ownedBatchName ?: return
		viewModelScope.launch {
			runCatchingCancellable { batchScheduler.cancel(batch) }
				.onFailure { _state.update { it.copy(failed = true) } }
		}
	}

	fun retry() {
		when {
			state.value.sourceSelectionFailed -> reloadSearchSources()
			else -> refresh()
		}
	}

	fun reloadSearchSources() {
		val mangaId = state.value.manga.firstOrNull()?.id ?: return
		loadSearchSources(mangaId, force = true)
	}

	fun refresh() {
		if (loadJob?.isActive == true) {
			// Source-backed sessions already observe Room continuously. Explicit manga-id sessions use
			// a one-shot snapshot so they must be rebuilt on resume after a manual migration moved
			// library ownership away from the original id.
			if (explicitMangaIds.isEmpty()) return
			loadJob?.cancel()
		}
		loadJob = viewModelScope.launch {
			runCatchingCancellable {
				combine(
					mangaFlow(),
					batchScheduler.observe(),
					database.getPrivateFavouritesDao().observePrivateOnlyIds(),
				) { entities, works, privateIds ->
					val manga = entities.toMangaList()
					val visible = manga.mapTo(hashSetOf()) { it.id }
					val privateOnly = privateIds.toHashSet()
					val relevantIds = manga.mapTo(hashSetOf()) { it.id }
					val snapshots = works.mapNotNull(MigrationBatchWork::snapshot)
						.filter { it.mangaId in relevantIds }
						.filterNot { it.mangaId in privateOnly }
						.mapNotNull { item ->
							if (item.outcome.destinationId !in privateOnly) item
							else if (item.mangaId in visible) item.copy(outcome = MigrationBatchOutcome(MigrationBatchStatus.NEEDS_REVIEW))
							else null
						}
					val latest = MigrationBatchPolicy.latest(snapshots)
					val owned = snapshots.firstOrNull { it.running }?.batchName
					val batch = snapshots.filter { it.batchName == owned }
					SourceMangaListState(
						isLoading = false,
						manga = manga,
						batchItems = latest,
						batchBusy = works.any { !it.state.isFinished && !MigrationBatchWork.isTerminal(it) },
						ownedBatchName = owned,
						batchCompleted = batch.count { !it.running },
						batchTotal = batch.size,
						migratedCount = latest.values.count { it.outcome.status == MigrationBatchStatus.MIGRATED },
						unconfirmedCount = latest.values.count {
							it.mangaId !in visible && it.outcome.status == MigrationBatchStatus.INTERRUPTED
						},
					)
				}.flowOn(Dispatchers.IO).collect { projection ->
					_state.update { current ->
						projection.copy(
							filter = current.filter,
							scheduling = current.scheduling,
							selectedIds = current.selectedIds.intersect(projection.manga.mapTo(hashSetOf()) { it.id }),
							availableSources = current.availableSources,
							selectedSourceNames = current.selectedSourceNames,
							sourceStrategy = current.sourceStrategy,
							extraSearchParams = current.extraSearchParams,
							skipSetup = current.skipSetup,
							sourceSelectionLoading = current.sourceSelectionLoading,
							sourceSelectionFailed = current.sourceSelectionFailed,
							sourcePoolFailureCount = current.sourcePoolFailureCount,
							failed = current.failed,
						)
					}
					projection.manga.firstOrNull()?.id?.let { loadSearchSources(it) }
				}
			}.onFailure { _state.update { it.copy(isLoading = false, failed = true) } }
		}
	}

	private fun mangaFlow(): Flow<List<MangaWithTags>> = if (explicitMangaIds.isNotEmpty()) {
		flow {
			val dao = database.getMangaDao()
			val visible = dao.findByIds(explicitMangaIds).filter { dao.isMigrationEntryVisible(it.manga.id) }
			emit(visible)
		}
	} else {
		database.getMangaDao().observeLibraryMangaBySources(sourceKeys)
	}

	private fun loadSearchSources(mangaId: Long, force: Boolean = false) {
		if (!force && (state.value.availableSources.isNotEmpty() || sourceLoadJob?.isActive == true)) return
		sourceLoadJob?.cancel()
		_state.update { it.copy(sourceSelectionLoading = true, sourceSelectionFailed = false) }
		sourceLoadJob = viewModelScope.launch {
			runCatchingCancellable { withContext(Dispatchers.IO) { yokaiSearch.sourcePool(mangaId) } }
				.onSuccess { pool ->
					val availableNames = pool.allSources.mapTo(hashSetOf(), MangaSource::name)
					val remembered = migrationPreferences.selectedSourceNames.filter { it in availableNames }
					val currentSelection = state.value.selectedSourceNames.filter { it in availableNames }
					val selected = when {
						currentSelection.isNotEmpty() -> currentSelection
						remembered.isNotEmpty() -> remembered
						else -> pool.allSources.map(MangaSource::name)
					}
					_state.update { current -> current.copy(
						availableSources = pool.allSources,
						selectedSourceNames = selected,
						sourceSelectionLoading = false,
						sourceSelectionFailed = false,
						sourcePoolFailureCount = pool.providerFailureCount,
					) }
					if (!autoStartConsumed && migrationPreferences.skipSetup && selected.isNotEmpty()) {
						autoStartConsumed = true
						searchAll()
					}
				}
				.onFailure {
					_state.update { current -> current.copy(
						sourceSelectionLoading = false,
						sourceSelectionFailed = true,
					) }
				}
		}
	}

	private companion object {
		const val FILTER = "migration_batch_filter"
	}
}

data class SourceMangaListState(
	val isLoading: Boolean = true,
	val failed: Boolean = false,
	val manga: List<Manga> = emptyList(),
	val selectedIds: Set<Long> = emptySet(),
	val filter: MigrationBatchStatus? = null,
	val availableSources: List<MangaSource> = emptyList(),
	/** Ordered destination source identities; FIRST_MATCH uses this exact order. */
	val selectedSourceNames: List<String> = emptyList(),
	val sourceStrategy: MigrationSourceStrategy = MigrationSourceStrategy.FIRST_MATCH,
	val extraSearchParams: String = "",
	val skipSetup: Boolean = false,
	val sourceSelectionLoading: Boolean = false,
	val sourceSelectionFailed: Boolean = false,
	val sourcePoolFailureCount: Int = 0,
	val batchItems: Map<Long, MigrationBatchSnapshot> = emptyMap(),
	val batchBusy: Boolean = false,
	val ownedBatchName: String? = null,
	val scheduling: Boolean = false,
	val batchCompleted: Int = 0,
	val batchTotal: Int = 0,
	val migratedCount: Int = 0,
	val unconfirmedCount: Int = 0,
) {
	val configKey: String
		get() = migrationConfigKey(selectedSourceNames, sourceStrategy, extraSearchParams.trim().takeIf(String::isNotEmpty))

	fun status(mangaId: Long): MigrationBatchStatus {
		val snapshot = batchItems[mangaId] ?: return MigrationBatchStatus.UNCHECKED
		val status = snapshot.outcome.status
		return when {
			status == MigrationBatchStatus.MIGRATED -> status
			snapshot.discoveryMode == MigrationBatchDiscoveryMode.YOKAI && snapshot.configKey == configKey -> status
			else -> MigrationBatchStatus.UNCHECKED
		}
	}

	val visibleManga: List<Manga> get() = manga.filter { filter == null || status(it.id) == filter }
	val readyCount: Int get() = MigrationBatchPolicy.readyRequests(
		manga = manga,
		snapshots = batchItems,
		discoveryMode = MigrationBatchDiscoveryMode.YOKAI,
		configKey = configKey,
	).size
}
