package org.koitharu.kotatsu.settings.sources.migration

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.alternatives.domain.*
import org.koitharu.kotatsu.alternatives.work.MigrationBatchScheduler
import org.koitharu.kotatsu.alternatives.work.MigrationBatchWork
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class SourceMangaListViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val database: MangaDatabase,
	private val batchScheduler: MigrationBatchScheduler,
) : ViewModel() {

	val sourceTitle: String = checkNotNull(savedStateHandle[SourceMangaListFragment.ARG_SOURCE_TITLE])
	private val sourceKeys: List<String> = checkNotNull(
		savedStateHandle.get<Array<String>>(SourceMangaListFragment.ARG_SOURCE_KEYS),
	).toList()

	private val _state = MutableStateFlow(SourceMangaListState(
		filter = savedStateHandle.get<String>(FILTER)?.let { raw -> MigrationBatchStatus.entries.find { it.name == raw } },
		searchScope = savedStateHandle.get<String>(SEARCH_SCOPE)
			?.let { raw -> MigrationSearchScope.entries.find { it.name == raw } }
			?: MigrationSearchScope.ALL,
		discoveryMode = savedStateHandle.get<String>(DISCOVERY_MODE)
			?.let { raw -> MigrationBatchDiscoveryMode.entries.find { it.name == raw } }
			?: MigrationBatchDiscoveryMode.CHECK_CURRENT,
	))
	val state: StateFlow<SourceMangaListState> = _state.asStateFlow()

	private var loadJob: Job? = null

	fun toggleSelection(mangaId: Long) {
		_state.update { current ->
			current.copy(selectedIds = current.selectedIds.toMutableSet().apply { if (!add(mangaId)) remove(mangaId) })
		}
	}

	fun clearSelection() { _state.update { it.copy(selectedIds = emptySet()) } }

	fun selectAll() { _state.update { current -> current.copy(selectedIds = current.manga.mapTo(linkedSetOf()) { it.id }) } }

	fun selectRange() {
		_state.update { current ->
			val selectedIndices = current.manga.mapIndexedNotNull { index, manga -> index.takeIf { manga.id in current.selectedIds } }
			if (selectedIndices.size < 2) return@update current
			val range = selectedIndices.first()..selectedIndices.last()
			current.copy(selectedIds = current.selectedIds + range.map { current.manga[it].id })
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

	fun setSearchScope(scope: MigrationSearchScope) {
		if (state.value.batchBusy || state.value.scheduling || state.value.searchScope == scope) return
		savedStateHandle[SEARCH_SCOPE] = scope.name
		savedStateHandle[FILTER] = null
		_state.update { it.copy(searchScope = scope, filter = null) }
	}

	fun setDiscoveryMode(mode: MigrationBatchDiscoveryMode) {
		if (state.value.batchBusy || state.value.scheduling || state.value.discoveryMode == mode) return
		savedStateHandle[DISCOVERY_MODE] = mode.name
		savedStateHandle[FILTER] = null
		_state.update { it.copy(discoveryMode = mode, filter = null) }
	}

	fun checkAll() = enqueue(state.value.manga.map {
		MigrationBatchRequest(
			it.id,
			it.source.name,
			MigrationBatchAction.CHECK,
			searchScope = state.value.searchScope,
			discoveryMode = state.value.discoveryMode,
		)
	})

	fun migrateReady() = enqueue(MigrationBatchPolicy.readyRequests(
		state.value.manga,
		state.value.batchItems,
		state.value.searchScope,
		state.value.discoveryMode,
	))

	private fun enqueue(requests: List<MigrationBatchRequest>) {
		if (state.value.scheduling || state.value.batchBusy || requests.isEmpty()) return
		_state.update { it.copy(scheduling = true, failed = false) }
		viewModelScope.launch {
			runCatchingCancellable { batchScheduler.enqueue(requests) }
				.onSuccess { accepted -> _state.update { it.copy(scheduling = false, failed = !accepted) } }
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
		if (loadJob?.isActive == true) checkAll() else refresh()
	}

	fun refresh() {
		if (loadJob?.isActive == true) return
		loadJob = viewModelScope.launch {
			runCatchingCancellable {
				combine(
					database.getMangaDao().observeLibraryMangaBySources(sourceKeys),
					batchScheduler.observe(),
					database.getPrivateFavouritesDao().observePrivateOnlyIds(),
				) { entities, works, privateIds ->
					val manga = entities.toMangaList()
					val visible = manga.mapTo(hashSetOf()) { it.id }
					val privateOnly = privateIds.toHashSet()
					val snapshots = works.mapNotNull(MigrationBatchWork::snapshot).filter { it.source in sourceKeys }
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
							searchScope = current.searchScope,
							discoveryMode = current.discoveryMode,
							scheduling = current.scheduling,
							selectedIds = current.selectedIds.intersect(projection.manga.mapTo(hashSetOf()) { it.id }),
						)
					}
				}
			}.onFailure { _state.update { it.copy(isLoading = false, failed = true) } }
		}
	}

	private companion object {
		const val FILTER = "migration_batch_filter"
		const val SEARCH_SCOPE = "migration_batch_search_scope"
		const val DISCOVERY_MODE = "migration_batch_discovery_mode"
	}
}

data class SourceMangaListState(
	val isLoading: Boolean = true,
	val failed: Boolean = false,
	val manga: List<Manga> = emptyList(),
	val selectedIds: Set<Long> = emptySet(),
	val filter: MigrationBatchStatus? = null,
	val searchScope: MigrationSearchScope = MigrationSearchScope.ALL,
	val discoveryMode: MigrationBatchDiscoveryMode = MigrationBatchDiscoveryMode.CHECK_CURRENT,
	val batchItems: Map<Long, MigrationBatchSnapshot> = emptyMap(),
	val batchBusy: Boolean = false,
	val ownedBatchName: String? = null,
	val scheduling: Boolean = false,
	val batchCompleted: Int = 0,
	val batchTotal: Int = 0,
	val migratedCount: Int = 0,
	val unconfirmedCount: Int = 0,
) {
	fun status(mangaId: Long): MigrationBatchStatus {
		val snapshot = batchItems[mangaId] ?: return MigrationBatchStatus.UNCHECKED
		val status = snapshot.outcome.status
		val matchingEvidence = snapshot.discoveryMode == discoveryMode && snapshot.searchScope == searchScope
		return when {
			status == MigrationBatchStatus.MIGRATED -> status
			status == MigrationBatchStatus.HEALTHY && discoveryMode == MigrationBatchDiscoveryMode.CHECK_CURRENT -> status
			matchingEvidence -> status
			else -> MigrationBatchStatus.UNCHECKED
		}
	}

	val visibleManga: List<Manga> get() = manga.filter { filter == null || status(it.id) == filter }
	val readyCount: Int get() = MigrationBatchPolicy.readyRequests(manga, batchItems, searchScope, discoveryMode).size
}
