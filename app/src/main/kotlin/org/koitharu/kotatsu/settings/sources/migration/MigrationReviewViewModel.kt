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
import org.koitharu.kotatsu.alternatives.domain.MigrationSelection
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

data class MigrationReviewState(
	val loading: Boolean = true,
	val searching: Boolean = false,
	val discovery: MigrationDiscovery? = null,
	val failed: Boolean = false,
	val changed: Boolean = false,
	val completedId: Long? = null,
)

@HiltViewModel
class MigrationReviewViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val autoFix: AutoFixUseCase,
) : ViewModel() {
	private val mangaId: Long = checkNotNull(savedStateHandle[MigrationReviewFragment.ARG_MANGA_ID])
	// Start idle so init can enter retry(); every actual network/execution operation flips loading=true.
	private val mutableState = MutableStateFlow(MigrationReviewState(loading = false))
	val state = mutableState.asStateFlow()

	init {
		val completed = savedStateHandle.get<Long>(COMPLETED_ID)
		if (completed != null) mutableState.value = MigrationReviewState(loading = false, completedId = completed)
		else retry()
	}

	fun retry() {
		if (mutableState.value.loading) return
		mutableState.value = MigrationReviewState(searching = true)
		viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					autoFix.discover(mangaId) { progress ->
						mutableState.value = MigrationReviewState(
							loading = true,
							searching = true,
							discovery = progress,
						)
					}
				}
			}
				.onSuccess { mutableState.value = MigrationReviewState(loading = false, discovery = it) }
				.onFailure { mutableState.value = MigrationReviewState(loading = false, failed = true) }
		}
	}

	fun migrate(candidate: Manga, reviewed: Boolean) {
		if (mutableState.value.loading) return
		mutableState.value = mutableState.value.copy(loading = true, searching = false, failed = false, changed = false)
		viewModelScope.launch {
			runCatchingCancellable { withContext(Dispatchers.IO) { autoFix.migrateSelected(mangaId, MigrationSelection.of(candidate), reviewed) } }
				.onSuccess { replacement ->
					if (replacement == null) {
						mutableState.value = MigrationReviewState(loading = false, changed = true)
					} else {
						savedStateHandle[COMPLETED_ID] = replacement.id
						mutableState.value = MigrationReviewState(loading = false, completedId = replacement.id)
					}
				}
				.onFailure { error ->
					mutableState.value = if (error is AutoFixUseCase.SelectionChangedException) {
						MigrationReviewState(loading = false, discovery = error.discovery, changed = true)
					} else mutableState.value.copy(loading = false, searching = false, failed = true)
				}
		}
	}

	private companion object { const val COMPLETED_ID = "migration_completed_id" }
}
