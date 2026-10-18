package org.koitharu.kotatsu.settings.sources.migration

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@HiltViewModel
class SourceMangaListViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val database: MangaDatabase,
) : ViewModel() {

	val sourceTitle: String = checkNotNull(savedStateHandle[SourceMangaListFragment.ARG_SOURCE_TITLE])
	private val sourceKeys: List<String> = checkNotNull(
		savedStateHandle.get<Array<String>>(SourceMangaListFragment.ARG_SOURCE_KEYS),
	).toList()

	private val _state = MutableStateFlow(SourceMangaListState())
	val state: StateFlow<SourceMangaListState> = _state.asStateFlow()

	private var loadJob: Job? = null


	fun toggleSelection(mangaId: Long) {
		_state.update { current ->
			current.copy(
				selectedIds = current.selectedIds.toMutableSet().apply {
					if (!add(mangaId)) remove(mangaId)
				},
			)
		}
	}

	fun clearSelection() {
		_state.update { it.copy(selectedIds = emptySet()) }
	}

	fun selectAll() {
		_state.update { current ->
			current.copy(selectedIds = current.manga.mapTo(linkedSetOf()) { it.id })
		}
	}

	fun selectRange() {
		_state.update { current ->
			val selectedIndices = current.manga.mapIndexedNotNull { index, manga ->
				index.takeIf { manga.id in current.selectedIds }
			}
			if (selectedIndices.size < 2) return@update current
			val range = selectedIndices.first()..selectedIndices.last()
			current.copy(
				selectedIds = current.selectedIds + range.map { current.manga[it].id },
			)
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
			_state.update { current ->
				current.copy(
					manga = current.manga.filterNot { it.id in ids },
					selectedIds = emptySet(),
				)
			}
		}
	}

	fun refresh() {
		if (loadJob?.isActive == true) return
		loadJob = viewModelScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
					database.getMangaDao().findLibraryMangaBySources(sourceKeys).toMangaList()
				}
			}.onSuccess { manga ->
				_state.update { it.copy(isLoading = false, manga = manga,
					selectedIds = it.selectedIds.intersect(manga.mapTo(hashSetOf()) { item -> item.id }), failed = false) }
			}.onFailure { _state.update { it.copy(isLoading = false, failed = true) } }
		}
	}
}

data class SourceMangaListState(
	val isLoading: Boolean = true,
	val failed: Boolean = false,
	val manga: List<Manga> = emptyList(),
	val selectedIds: Set<Long> = emptySet(),
)
