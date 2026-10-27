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
import org.koitharu.kotatsu.alternatives.domain.MigrationDiscovery
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchAvailability
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchScope
import org.koitharu.kotatsu.alternatives.domain.MigrationSelection
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

data class MigrationReviewState(
	val loading: Boolean = true,
	val searching: Boolean = false,
	val discovery: MigrationDiscovery? = null,
	/** Compatibility/count snapshot. Direct Search no longer waits for it. */
	val availability: MigrationSearchAvailability? = null,
	val selectedScope: MigrationSearchScope = MigrationSearchScope.ALL,
	val hasSearched: Boolean = false,
	/** True only after the user explicitly chose the current-source check. */
	val currentSourceChecked: Boolean = false,
	/** True when the user explicitly chose Search installed sources / readable-source override. */
	val manualSearchEnabled: Boolean = false,
	val checkFailed: Boolean = false,
	val searchFailed: Boolean = false,
	/** Execution/transfer failure only. Discovery failures use [searchFailed]. */
	val failed: Boolean = false,
	val changed: Boolean = false,
	val completedId: Long? = null,
)

/** Scope changes invalidate only scope-dependent search evidence, never the user's chosen entry path. */
internal fun MigrationReviewState.withSearchScope(scope: MigrationSearchScope): MigrationReviewState {
	if (scope == selectedScope) return this
	return copy(
		loading = false,
		searching = false,
		discovery = discovery?.let {
			MigrationDiscovery(seed = it.seed, sourceHealthy = it.currentSourceReadable)
		},
		availability = null,
		selectedScope = scope,
		hasSearched = false,
		checkFailed = false,
		searchFailed = false,
		failed = false,
		changed = false,
	)
}

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
		if (before.loading || before.discovery == null) return
		searchJob?.cancel()
		searchGeneration++
		val selectedScope = before.selectedScope
		mutableState.value = before.copy(
			loading = true,
			searching = false,
			availability = null,
			hasSearched = false,
			currentSourceChecked = false,
			manualSearchEnabled = false,
			checkFailed = false,
			searchFailed = false,
			failed = false,
			changed = false,
		)
		viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					autoFix.checkCurrentSource(mangaId) { progress ->
						mutableState.value = mutableState.value.copy(
							loading = true,
							discovery = progress,
							selectedScope = selectedScope,
						)
					}
				}
			}
				.onSuccess { current ->
					mutableState.value = MigrationReviewState(
						loading = false,
						discovery = current,
						selectedScope = selectedScope,
						currentSourceChecked = true,
					)
				}
				.onFailure {
					mutableState.value = before.copy(
						loading = false,
						checkFailed = true,
					)
				}
		}
	}

	/** User-selected path: skip current-source probing and immediately search ALL installed sources. */
	fun enableManualSearch() {
		val before = mutableState.value
		if (before.loading || before.discovery == null) return
		mutableState.value = before.copy(
			loading = false,
			searching = false,
			manualSearchEnabled = true,
			currentSourceChecked = false,
			availability = null,
			hasSearched = false,
			checkFailed = false,
			searchFailed = false,
			failed = false,
			changed = false,
		)
		startSearch(before.selectedScope, revealAllCandidates = true)
	}

	fun setSearchScope(scope: MigrationSearchScope) {
		val before = mutableState.value
		if (before.selectedScope == scope) return
		if (before.loading && !before.manualSearchEnabled) return
		searchJob?.cancel()
		searchGeneration++
		mutableState.value = before.withSearchScope(scope)
		if (before.manualSearchEnabled) {
			startSearch(scope, revealAllCandidates = true)
		}
	}

	fun search() {
		val before = mutableState.value
		if (before.discovery == null) return
		if (before.loading && !before.manualSearchEnabled) return
		if (!before.currentSourceChecked && !before.manualSearchEnabled) return
		if (before.currentSourceChecked && before.discovery.currentSourceReadable && !before.manualSearchEnabled) return
		startSearch(before.selectedScope, revealAllCandidates = before.manualSearchEnabled)
	}

	private fun startSearch(scope: MigrationSearchScope, revealAllCandidates: Boolean) {
		val before = mutableState.value
		val seed = before.discovery?.seed ?: return
		searchJob?.cancel()
		val generation = ++searchGeneration
		val currentReadable = before.currentSourceChecked && before.discovery.currentSourceReadable
		mutableState.value = before.copy(
			loading = true,
			searching = true,
			discovery = MigrationDiscovery(seed = seed, sourceHealthy = currentReadable),
			availability = null,
			selectedScope = scope,
			hasSearched = true,
			checkFailed = false,
			searchFailed = false,
			failed = false,
			changed = false,
		)
		searchJob = viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					autoFix.searchReplacements(
						mangaId = mangaId,
						searchScope = scope,
						currentSourceReadable = currentReadable,
						revealAllCandidates = revealAllCandidates,
					) { progress ->
						if (generation == searchGeneration && mutableState.value.selectedScope == scope) {
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
					if (generation == searchGeneration && mutableState.value.selectedScope == scope) {
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
					if (generation == searchGeneration && mutableState.value.selectedScope == scope) {
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

	fun migrate(candidate: Manga, reviewed: Boolean) {
		if (mutableState.value.loading) return
		val allowReadableSource = mutableState.value.manualSearchEnabled
		mutableState.value = mutableState.value.copy(loading = true, searching = false, failed = false, changed = false)
		viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					autoFix.migrateSelected(
						mangaId,
						MigrationSelection.of(candidate),
						reviewed,
						allowReadableSource = allowReadableSource,
					)
				}
			}
				.onSuccess { replacement ->
					if (replacement == null) {
						mutableState.value = mutableState.value.copy(loading = false, changed = true)
					} else {
						savedStateHandle[COMPLETED_ID] = replacement.id
						mutableState.value = MigrationReviewState(loading = false, completedId = replacement.id)
					}
				}
				.onFailure { error ->
					mutableState.value = if (error is AutoFixUseCase.SelectionChangedException) {
						mutableState.value.copy(loading = false, discovery = error.discovery, changed = true)
					} else mutableState.value.copy(loading = false, searching = false, failed = true)
				}
		}
	}

	private companion object { const val COMPLETED_ID = "migration_completed_id" }
}
