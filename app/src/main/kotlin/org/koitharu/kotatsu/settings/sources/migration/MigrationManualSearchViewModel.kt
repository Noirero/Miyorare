package org.koitharu.kotatsu.settings.sources.migration

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchRequest
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationExecutionMode
import org.koitharu.kotatsu.alternatives.domain.MigrationGlobalSearchBridge
import org.koitharu.kotatsu.alternatives.domain.MigrationSelection
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy
import org.koitharu.kotatsu.alternatives.domain.YokaiStyleMigrationSearchUseCase
import org.koitharu.kotatsu.alternatives.domain.migrationConfigKey
import org.koitharu.kotatsu.alternatives.work.MigrationBatchScheduler
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class MigrationManualSearchViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val mangaDataRepository: MangaDataRepository,
	private val yokaiSearch: YokaiStyleMigrationSearchUseCase,
	private val globalSearch: MigrationGlobalSearchBridge,
	private val batchScheduler: MigrationBatchScheduler,
) : ViewModel() {
	private val mangaId: Long = checkNotNull(savedStateHandle[MigrationManualSearchFragment.ARG_MANGA_ID])
	private val selectedSourceNames: List<String> = savedStateHandle
		.get<Array<String>>(MigrationManualSearchFragment.ARG_SOURCE_NAMES)?.toList().orEmpty()
	private val sessionStrategy: MigrationSourceStrategy = savedStateHandle
		.get<String>(MigrationManualSearchFragment.ARG_STRATEGY)
		?.let { raw -> MigrationSourceStrategy.entries.find { it.name == raw } }
		?: MigrationSourceStrategy.FIRST_MATCH
	private val sessionExtraSearch: String? = savedStateHandle
		.get<String>(MigrationManualSearchFragment.ARG_EXTRA_SEARCH)?.trim()?.takeIf(String::isNotEmpty)
	private val sessionConfigKey: String = savedStateHandle
		.get<String>(MigrationManualSearchFragment.ARG_CONFIG)
		?: migrationConfigKey(selectedSourceNames, sessionStrategy, sessionExtraSearch)
	private val initialTargetSource: String? = savedStateHandle[MigrationManualSearchFragment.ARG_TARGET_SOURCE]
	private val initialTargetId: Long = savedStateHandle.get<Long>(MigrationManualSearchFragment.ARG_TARGET_ID) ?: 0L

	private val _state = MutableStateFlow(MigrationManualSearchState())
	val state = _state.asStateFlow()
	private var searchJob: Job? = null

	init {
		load()
	}

	fun setQuery(value: String) {
		if (state.value.searching || state.value.migrating || state.value.skipping) return
		_state.update { it.copy(query = value, selected = null, errorMessage = null) }
	}

	fun select(candidate: Manga) {
		if (state.value.migrating || state.value.skipping || candidate.id == mangaId) return
		_state.update { it.copy(selected = candidate, errorMessage = null) }
	}

	fun clearSelection() {
		if (state.value.migrating || state.value.skipping) return
		_state.update { it.copy(selected = null, errorMessage = null) }
	}

	fun search() {
		val before = state.value
		val seed = before.seed ?: return
		val query = before.query.trim()
		if (query.isEmpty() || before.migrating || before.skipping) return
		searchJob?.cancel()
		val sources = before.sources
		_state.update { current ->
			current.copy(
				searching = true,
				errorMessage = null,
				groups = sources.associate { source ->
					source.name to ManualMigrationSourceResult(source = source, loading = true)
				},
			)
		}
		searchJob = viewModelScope.launch(Dispatchers.IO) {
			val semaphore = Semaphore(MAX_PARALLEL_SOURCES)
			coroutineScope {
				sources.map { source ->
					launch {
						val result = semaphore.withPermit { globalSearch.search(source, query) }
						// MangaEntity has a single numeric primary key. Source implementations can legally
						// collide on that number, so hide candidates that cannot be persisted without
						// overwriting either the source manga or an unrelated manga from another source.
						val manga = ArrayList<Manga>()
						for (candidate in result.getOrDefault(emptyList())) {
							if (candidate.id == seed.id) continue
							if (canPersistCandidate(candidate)) manga += candidate
						}
						_state.update { current ->
							val next = current.groups.toMutableMap()
							next[source.name] = ManualMigrationSourceResult(
								source = source,
								manga = manga,
								loading = false,
								errorMessage = result.exceptionOrNull()?.message,
							)
							current.copy(groups = next)
						}
					}
				}.joinAll()
			}
			_state.update { it.copy(searching = false) }
		}
	}

	fun migrateSelected() = executeSelected(MigrationExecutionMode.MIGRATE)

	fun copySelected() = executeSelected(MigrationExecutionMode.COPY)

	fun skip() {
		val seed = state.value.seed ?: return
		if (state.value.migrating || state.value.skipping) return
		searchJob?.cancel()
		_state.update { it.copy(searching = false, skipping = true, errorMessage = null) }
		viewModelScope.launch {
			runCatchingCancellable {
				batchScheduler.markSkipped(
					mangaId = mangaId,
					source = seed.source.name,
					configKey = sessionConfigKey,
				)
			}.onSuccess { marked ->
				if (marked) {
					_state.update { it.copy(skipping = false, skipped = true) }
				} else {
					_state.update { it.copy(skipping = false, errorMessage = "Another migration batch is still running") }
				}
			}.onFailure { error ->
				_state.update { it.copy(skipping = false, errorMessage = error.message ?: error::class.java.simpleName) }
			}
		}
	}

	private fun executeSelected(mode: MigrationExecutionMode) {
		val current = state.value
		val candidate = current.selected ?: return
		val seed = current.seed ?: return
		if (current.migrating || current.skipping || candidate.id == mangaId) return
		searchJob?.cancel()
		_state.update { it.copy(searching = false, migrating = true, errorMessage = null) }
		viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					check(candidate.id != mangaId) { "Selected replacement conflicts with the source manga identity" }
					check(canPersistCandidate(candidate)) {
						"Selected replacement id is already owned by a different source"
					}
					mangaDataRepository.storeManga(
						manga = candidate,
						replaceExisting = true,
						stripAppliedOverride = false,
					)
					val selection = MigrationSelection.fromSearchResult(candidate)
					val snapshot = checkNotNull(
						batchScheduler.enqueueAndAwait(
							MigrationBatchRequest(
								mangaId = mangaId,
								source = seed.source.name,
								action = if (mode == MigrationExecutionMode.COPY) MigrationBatchAction.COPY else MigrationBatchAction.MIGRATE,
								selection = selection,
								discoveryMode = MigrationBatchDiscoveryMode.YOKAI,
								selectedSourceNames = selectedSourceNames,
								sourceStrategy = sessionStrategy,
								extraSearchParams = sessionExtraSearch,
								configKey = sessionConfigKey,
							),
						),
					) { "Another migration batch is still running" }
					val expected = if (mode == MigrationExecutionMode.COPY) MigrationBatchStatus.COPIED else MigrationBatchStatus.MIGRATED
					check(snapshot.outcome.status == expected) {
						"Migration execution ended with ${snapshot.outcome.status.name.lowercase()}"
					}
					val destinationId = checkNotNull(snapshot.outcome.destinationId) { "Migration result has no destination" }
					checkNotNull(mangaDataRepository.findMangaById(destinationId, withChapters = false)) {
						"Migrated manga $destinationId is no longer available"
					}
				}
			}.onSuccess { result ->
				_state.update { it.copy(migrating = false, completed = result, completedMode = mode) }
			}.onFailure { error ->
				_state.update { it.copy(migrating = false, errorMessage = error.message ?: error::class.java.simpleName) }
			}
		}
	}

	private suspend fun canPersistCandidate(candidate: Manga): Boolean {
		val existing = mangaDataRepository.findMangaById(candidate.id, withChapters = false) ?: return true
		return existing.source.name == candidate.source.name
	}

	private fun load() {
		viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					val seed = checkNotNull(mangaDataRepository.findMangaById(mangaId, withChapters = true))
					val pool = yokaiSearch.sourcePool(mangaId)
					val byName = pool.allSources.associateBy(MangaSource::name)
					val selected = selectedSourceNames.mapNotNull(byName::get).distinctBy(MangaSource::name)
					val validSources = if (selected.size == 1) selected else selected.filterNot { it == seed.source }
					val validSourceNames = validSources.mapTo(HashSet(), MangaSource::name)
					val initial = initialTargetId.takeIf { it != 0L && it != mangaId }
						?.let { mangaDataRepository.findMangaById(it, withChapters = false) }
						?.takeIf { initialTargetSource == null || it.source.name == initialTargetSource }
						?.takeIf { it.source.name in validSourceNames }
					Triple(seed, validSources, initial)
				}
			}.onSuccess { (seed, sources, initial) ->
				_state.value = MigrationManualSearchState(
					loading = false,
					seed = seed,
					query = seed.title,
					sources = sources,
					selected = initial,
				)
				search()
			}.onFailure { error ->
				_state.value = MigrationManualSearchState(
					loading = false,
					errorMessage = error.message ?: error::class.java.simpleName,
				)
			}
		}
	}

	private companion object {
		const val MAX_PARALLEL_SOURCES = 5
	}
}

data class MigrationManualSearchState(
	val loading: Boolean = true,
	val searching: Boolean = false,
	val migrating: Boolean = false,
	val skipping: Boolean = false,
	val skipped: Boolean = false,
	val seed: Manga? = null,
	val query: String = "",
	val sources: List<MangaSource> = emptyList(),
	val groups: Map<String, ManualMigrationSourceResult> = emptyMap(),
	val selected: Manga? = null,
	val errorMessage: String? = null,
	val completed: Manga? = null,
	val completedMode: MigrationExecutionMode? = null,
)

data class ManualMigrationSourceResult(
	val source: MangaSource,
	val manga: List<Manga> = emptyList(),
	val loading: Boolean = false,
	val errorMessage: String? = null,
)
