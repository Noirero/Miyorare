package org.koitharu.kotatsu.settings.sources.migration

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
	val availability: MigrationSearchAvailability? = null,
	val selectedScope: MigrationSearchScope = MigrationSearchScope.ALL,
	val hasSearched: Boolean = false,
	/** True only after the user explicitly chose the current-source check. */
	val currentSourceChecked: Boolean = false,
	/** True when the user explicitly chose direct replacement search / readable-source override. */
	val manualSearchEnabled: Boolean = false,
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
		searching = false,
		discovery = discovery?.let {
			MigrationDiscovery(seed = it.seed, sourceHealthy = it.currentSourceReadable)
		},
		selectedScope = scope,
		hasSearched = false,
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
					mutableState.value = MigrationReviewState(loading = false, searchFailed = true)
				}
		}
	}

	/** User-selected path: check current source first. */
	fun retry() {
		val before = mutableState.value
		if (before.loading || before.discovery == null) return
		val selectedScope = before.selectedScope
		mutableState.value = before.copy(
			loading = true,
			searching = false,
			availability = null,
			hasSearched = false,
			currentSourceChecked = false,
			manualSearchEnabled = false,
			searchFailed = false,
			failed = false,
			changed = false,
		)
		viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					val current = autoFix.checkCurrentSource(mangaId) { progress ->
						mutableState.value = mutableState.value.copy(
							loading = true,
							discovery = progress,
							selectedScope = selectedScope,
						)
					}
					val availability = if (current.currentSourceReadable) null else autoFix.searchAvailability(mangaId)
					current to availability
				}
			}
				.onSuccess { (current, availability) ->
					mutableState.value = MigrationReviewState(
						loading = false,
						discovery = current,
						availability = availability,
						selectedScope = selectedScope,
						currentSourceChecked = true,
					)
				}
				.onFailure {
					mutableState.value = before.copy(
						loading = false,
						searchFailed = true,
					)
				}
		}
	}

	/** User-selected path: skip current-source probing and prepare replacement scopes immediately. */
	fun enableManualSearch() {
		val before = mutableState.value
		if (before.loading || before.discovery == null) return
		mutableState.value = before.copy(
			loading = true,
			manualSearchEnabled = true,
			hasSearched = false,
			searchFailed = false,
			failed = false,
			changed = false,
		)
		viewModelScope.launch {
			runCatchingCancellable { withContext(Dispatchers.IO) { autoFix.searchAvailability(mangaId) } }
				.onSuccess { availability ->
					mutableState.value = mutableState.value.copy(
						loading = false,
						manualSearchEnabled = true,
						availability = availability,
						searchFailed = false,
					)
				}
				.onFailure {
					mutableState.value = mutableState.value.copy(
						loading = false,
						manualSearchEnabled = true,
						searchFailed = true,
					)
				}
		}
	}

	fun setSearchScope(scope: MigrationSearchScope) {
		val before = mutableState.value
		if (before.loading || before.selectedScope == scope) return
		mutableState.value = before.withSearchScope(scope)
	}

	fun search() {
		val before = mutableState.value
		if (before.loading || before.discovery == null) return
		if (!before.currentSourceChecked && !before.manualSearchEnabled) return
		if (before.currentSourceChecked && before.discovery.currentSourceReadable && !before.manualSearchEnabled) return
		if ((before.availability?.count(before.selectedScope) ?: 0) <= 0) return
		val scope = before.selectedScope
		val currentReadable = before.currentSourceChecked && before.discovery.currentSourceReadable
		mutableState.value = before.copy(
			loading = true,
			searching = true,
			hasSearched = true,
			searchFailed = false,
			failed = false,
			changed = false,
		)
		viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					autoFix.searchReplacements(mangaId, scope, currentSourceReadable = currentReadable) { progress ->
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
				.onSuccess { discovery ->
					mutableState.value = mutableState.value.copy(
						loading = false,
						searching = false,
						discovery = discovery,
						hasSearched = true,
						searchFailed = false,
					)
				}
				.onFailure {
					mutableState.value = mutableState.value.copy(
						loading = false,
						searching = false,
						hasSearched = true,
						searchFailed = true,
					)
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
