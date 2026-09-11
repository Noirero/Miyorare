package org.koitharu.kotatsu.details.ui.related

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.details.domain.RelatedMangaGroup
import org.koitharu.kotatsu.details.domain.RelatedMangaUseCase
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject

data class RelatedGroupsUiState(
	val groups: List<RelatedMangaGroup> = emptyList(),
	val isLoading: Boolean = true,
	val error: Throwable? = null,
)

@HiltViewModel
class RelatedListViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val relatedMangaUseCase: RelatedMangaUseCase,
) : ViewModel() {

	val seed: Manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga
	val source: MangaSource get() = seed.source

	private val _state = MutableStateFlow(RelatedGroupsUiState())
	val state = _state.asStateFlow()
	private var loadingJob: Job? = null
	private var loadGeneration = 0L

	init {
		load()
	}

	fun retry() {
		load(force = true)
	}

	private fun load(force: Boolean = false) {
		if (!force && loadingJob?.isActive == true) return
		val generation = ++loadGeneration
		if (force) loadingJob?.cancel()
		loadingJob = viewModelScope.launch(Dispatchers.Default) {
			if (generation == loadGeneration) {
				_state.value = RelatedGroupsUiState(isLoading = true)
			}
			try {
				relatedMangaUseCase.collectGroups(seed) { group ->
					if (generation != loadGeneration) return@collectGroups
					val current = _state.value.groups
					if (current.none { it.keyword == group.keyword }) {
						val updated = if (group.keyword == null) {
							listOf(group) + current
						} else {
							current + group
						}
						_state.value = RelatedGroupsUiState(
							groups = updated,
							isLoading = true,
						)
					}
				}
				if (generation == loadGeneration) {
					_state.value = _state.value.copy(isLoading = false)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				if (generation == loadGeneration) {
					_state.value = _state.value.copy(isLoading = false, error = e)
				}
			} finally {
				if (generation == loadGeneration) {
					loadingJob = null
				}
			}
		}
	}
}
