package org.koitharu.kotatsu.settings.sources.migration

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.alternatives.domain.AutoFixUseCase
import org.koitharu.kotatsu.alternatives.domain.MigrationDirectSearchOptions
import org.koitharu.kotatsu.alternatives.domain.MigrationDiscovery
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchAvailability
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchPhase
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchProgress
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchScope
import org.koitharu.kotatsu.alternatives.domain.MigrationSelection
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

data class MigrationReviewState(
	val loading: Boolean = true,
	val searching: Boolean = false,
	val discovery: MigrationDiscovery? = null,
	/** Compatibility/count snapshot retained for old callers; source selection no longer depends on it. */
	val availability: MigrationSearchAvailability? = null,
	/** Category filter for the source picker, not an implicit search scope. */
	val selectedScope: MigrationSearchScope = MigrationSearchScope.ALL,
	val hasSearched: Boolean = false,
	/** True only after the user explicitly chose the current-source check. */
	val currentSourceChecked: Boolean = false,
	/** True when current-source checking is intentionally bypassed. */
	val manualSearchEnabled: Boolean = false,
	val sourceSelectionLoading: Boolean = false,
	val sourceSelectionFailed: Boolean = false,
	val availableSources: List<MangaSource> = emptyList(),
	val selectedSourceNames: Set<String> = emptySet(),
	val sourcePoolFailureCount: Int = 0,
	val directOptions: MigrationDirectSearchOptions = MigrationDirectSearchOptions(),
	val checkFailed: Boolean = false,
	val searchFailed: Boolean = false,
	/** Execution/transfer failure only. Discovery failures use [searchFailed]. */
	val failed: Boolean = false,
	val changed: Boolean = false,
	val completedId: Long? = null,
)

/** Scope now filters the source picker only; it never starts or invalidates a search by itself. */
internal fun MigrationReviewState.withSearchScope(scope: MigrationSearchScope): MigrationReviewState =
	if (scope == selectedScope) this else copy(selectedScope = scope)

@HiltViewModel
class MigrationReviewViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val autoFix: AutoFixUseCase,
) : ViewModel() {
	private val mangaId: Long = checkNotNull(savedStateHandle[MigrationReviewFragment.ARG_MANGA_ID])
	private val mutableState = MutableStateFlow(MigrationReviewState())
	val state = mutableState.asStateFlow()
	private var searchJob: Job? = null
	private var searchGeneration = 0

	init {
		val completed = savedStateHandle.get<Long>(COMPLETED_ID)
		if (completed != null) {
			mutableState.value = MigrationReviewState(loading = false, completedId = completed)
		} else {
			loadInitialState()
		}
	}

	/** Initial screen reads local library state only. No source/network call is allowed here. */
	private fun loadInitialState() {
		viewModelScope.launch {
			runCatchingCancellable { withContext(Dispatchers.IO) { autoFix.loadStoredManga(mangaId) } }
				.onSuccess { seed ->
					mutableState.value = MigrationReviewState(
						loading = false,
						discovery = MigrationDiscovery(seed = seed),
					)
				}
				.onFailure {
					mutableState.value = MigrationReviewState(loading = false, checkFailed = true)
				}
		}
	}

	/** User-selected path: check current source first. */
	fun retry() {
		val before = mutableState.value
		if (before.loading || before.sourceSelectionLoading || before.discovery == null) return
		searchJob?.cancel()
		searchGeneration++
		mutableState.value = before.copy(
			loading = true,
			searching = false,
			hasSearched = false,
			currentSourceChecked = false,
			manualSearchEnabled = false,
			sourceSelectionLoading = false,
			sourceSelectionFailed = false,
			availableSources = emptyList(),
			selectedSourceNames = emptySet(),
			sourcePoolFailureCount = 0,
			checkFailed = false,
			searchFailed = false,
			failed = false,
			changed = false,
		)
		viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					autoFix.checkCurrentSource(mangaId) { progress ->
						mutableState.value = mutableState.value.copy(loading = true, discovery = progress)
					}
				}
			}
				.onSuccess { current ->
					mutableState.value = MigrationReviewState(
						loading = false,
						discovery = current,
						selectedScope = before.selectedScope,
						currentSourceChecked = true,
						directOptions = before.directOptions,
					)
					if (!current.currentSourceReadable) prepareSourceSelection(directSearch = false)
				}
				.onFailure {
					mutableState.value = before.copy(loading = false, checkFailed = true)
				}
		}
	}

	/** User-selected path: skip current-source probing and show installed source choices first. */
	fun enableManualSearch() {
		val before = mutableState.value
		if (before.loading || before.sourceSelectionLoading || before.discovery == null) return
		prepareSourceSelection(directSearch = true)
	}

	fun reloadSearchSources() {
		val before = mutableState.value
		if (before.loading || before.sourceSelectionLoading || before.discovery == null) return
		prepareSourceSelection(directSearch = before.manualSearchEnabled)
	}

	private fun prepareSourceSelection(directSearch: Boolean) {
		val before = mutableState.value
		val seed = before.discovery?.seed ?: return
		searchJob?.cancel()
		searchGeneration++
		mutableState.value = before.copy(
			loading = false,
			searching = false,
			discovery = MigrationDiscovery(seed = seed, sourceHealthy = before.currentSourceChecked && before.discovery.currentSourceReadable),
			hasSearched = false,
			manualSearchEnabled = directSearch,
			sourceSelectionLoading = true,
			sourceSelectionFailed = false,
			availableSources = emptyList(),
			selectedSourceNames = emptySet(),
			sourcePoolFailureCount = 0,
			checkFailed = false,
			searchFailed = false,
			failed = false,
			changed = false,
		)
		viewModelScope.launch {
			runCatchingCancellable { withContext(Dispatchers.IO) { autoFix.loadSearchSourcePool(mangaId) } }
				.onSuccess { pool ->
					mutableState.value = mutableState.value.copy(
						sourceSelectionLoading = false,
						sourceSelectionFailed = false,
						availableSources = pool.allSources,
						sourcePoolFailureCount = pool.providerFailureCount,
					)
				}
				.onFailure {
					mutableState.value = mutableState.value.copy(
						sourceSelectionLoading = false,
						sourceSelectionFailed = true,
					)
				}
		}
	}

	fun setSearchScope(scope: MigrationSearchScope) {
		if (mutableState.value.searching || mutableState.value.loading) return
		mutableState.value = mutableState.value.withSearchScope(scope)
	}

	fun setSourcesSelected(sourceNames: Set<String>, selected: Boolean) {
		val before = mutableState.value
		if (before.loading || before.searching || before.sourceSelectionLoading) return
		val valid = sourceNames.intersect(before.availableSources.mapTo(HashSet()) { it.name })
		if (valid.isEmpty()) return
		val next = before.selectedSourceNames.toMutableSet()
		if (selected) next += valid else next -= valid
		mutableState.value = before.copy(selectedSourceNames = next, searchFailed = false, changed = false)
	}

	fun updateDirectOptions(options: MigrationDirectSearchOptions) {
		val before = mutableState.value
		if (before.loading || before.searching) return
		mutableState.value = before.copy(directOptions = options)
	}

	fun search() {
		val before = mutableState.value
		if (before.discovery == null || before.loading || before.searching || before.sourceSelectionLoading) return
		if (!before.currentSourceChecked && !before.manualSearchEnabled) return
		if (before.currentSourceChecked && before.discovery.currentSourceReadable && !before.manualSearchEnabled) return
		if (before.selectedSourceNames.isEmpty()) return
		startSearch(
			selectedSourceNames = before.selectedSourceNames,
			directSearch = before.manualSearchEnabled,
			options = before.directOptions,
		)
	}

	private fun startSearch(
		selectedSourceNames: Set<String>,
		directSearch: Boolean,
		options: MigrationDirectSearchOptions,
	) {
		val before = mutableState.value
		val seed = before.discovery?.seed ?: return
		searchJob?.cancel()
		val generation = ++searchGeneration
		val currentReadable = before.currentSourceChecked && before.discovery.currentSourceReadable
		mutableState.value = before.copy(
			loading = true,
			searching = true,
			discovery = MigrationDiscovery(
				seed = seed,
				sourceHealthy = currentReadable,
				progress = MigrationSearchProgress(MigrationSearchPhase.SEARCHING_SOURCES),
			),
			hasSearched = true,
			searchFailed = false,
			failed = false,
			changed = false,
		)
		searchJob = viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					autoFix.searchReplacements(
						mangaId = mangaId,
						searchScope = MigrationSearchScope.ALL,
						currentSourceReadable = currentReadable,
						directSearch = directSearch,
						revealAllCandidates = directSearch,
						selectedSourceNames = selectedSourceNames,
						directOptions = options,
					) { progress ->
						if (generation == searchGeneration) {
							mutableState.value = mutableState.value.copy(
								loading = true,
								searching = true,
								discovery = progress,
								hasSearched = true,
								searchFailed = false,
							)
						}
					}
				}
			}
				.onSuccess { discovery ->
					if (generation == searchGeneration) {
						mutableState.value = mutableState.value.copy(
							loading = false,
							searching = false,
							discovery = discovery,
							hasSearched = true,
							searchFailed = false,
						)
					}
				}
				.onFailure {
					if (generation == searchGeneration) {
						mutableState.value = mutableState.value.copy(
							loading = false,
							searching = false,
							hasSearched = true,
							searchFailed = true,
						)
					}
				}
		}
	}

	fun backToSourceSelection() {
		val before = mutableState.value
		val seed = before.discovery?.seed ?: return
		if (before.loading) return
		mutableState.value = before.copy(
			searching = false,
			discovery = MigrationDiscovery(seed = seed, sourceHealthy = before.currentSourceChecked && before.discovery.currentSourceReadable),
			hasSearched = false,
			searchFailed = false,
			failed = false,
			changed = false,
		)
	}

	fun migrate(candidate: Manga, reviewed: Boolean) {
		val before = mutableState.value
		if (before.loading) return
		val allowReadableSource = before.manualSearchEnabled
		val selection = if (allowReadableSource) {
			MigrationSelection.fromSearchResult(candidate)
		} else {
			MigrationSelection.of(candidate)
		}
		mutableState.value = before.copy(loading = true, searching = false, failed = false, changed = false)
		viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					autoFix.migrateSelected(
						mangaId,
						selection,
						reviewed,
						allowReadableSource = allowReadableSource,
					)
				}
			}
				.onSuccess { replacement ->
					if (replacement == null) {
						mutableState.value = before.copy(loading = false, changed = true)
					} else {
						savedStateHandle[COMPLETED_ID] = replacement.id
						mutableState.value = MigrationReviewState(loading = false, completedId = replacement.id)
					}
				}
				.onFailure { error ->
					mutableState.value = if (error is AutoFixUseCase.SelectionChangedException) {
						if (allowReadableSource) before.copy(loading = false, changed = true)
						else before.copy(loading = false, discovery = error.discovery, changed = true)
					} else before.copy(loading = false, searching = false, failed = true)
				}
		}
	}

	private companion object { const val COMPLETED_ID = "migration_completed_id" }
}
