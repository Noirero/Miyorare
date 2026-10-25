package org.koitharu.kotatsu.favourites.ui.sets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.favourites.data.*
import org.koitharu.kotatsu.favourites.domain.*
import javax.inject.Inject

@HiltViewModel
class CategorySetsViewModel @Inject constructor(
	savedState: SavedStateHandle,
	private val repository: FavouritesRepository,
	private val selection: FavouriteSetSelectionStore,
	contentTypes: FavouriteContentTypeStore,
) : BaseViewModel() {
	val space = FavouriteSpace.fromArgument(savedState[EXTRA_FAVOURITE_SPACE] ?: 0)
	// Keep the context from which management opened, including after configuration recreation.
	val contentType = FavouriteContentType.valueOf(savedState.get<String>(EXTRA_FAVOURITE_CONTENT_TYPE)
		?: contentTypes.selectedType(space).value.name.also { savedState[EXTRA_FAVOURITE_CONTENT_TYPE] = it })
	val sets = repository.observeSets(space).withErrorHandling()
		.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
	private val activeSet = selection.observe(space, flowOf(contentType))
	val rows = combine(sets, repository.observeSetCategoryCounts(space), activeSet) { sets, counts, active ->
		sets.map { CategorySetRow(it, counts[it.setId] ?: 0, it.setId == active.setId) }
	}.withErrorHandling().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
	private var mutation: Job? = null

	/** The synchronous job guard also closes the gap before the busy Flow reaches Compose. */
	private fun mutate(onComplete: () -> Unit = {}, onFinished: () -> Unit = {}, block: suspend () -> Unit): Boolean {
		if (mutation?.isActive == true) return false
		mutation = launchLoadingJob {
			try {
				withContext(Dispatchers.IO) { block() }
				onComplete()
			} finally {
				onFinished()
			}
		}
		return true
	}

	fun save(id: Long?, title: String, onSaved: () -> Unit) = mutate(onSaved) {
		if (id == null) selection.select(repository.createSet(title, space), space, contentType)
		else repository.renameSet(id, title, space)
	}

	fun reorder(ids: List<Long>, onComplete: () -> Unit) {
		if (!mutate(onFinished = onComplete) { repository.reorderSets(ids, space) }) onComplete()
	}

	suspend fun impact(id: Long): Pair<Int, Int> = withContext(Dispatchers.IO) {
		val categories = repository.observeCategoriesInSet(id, space).first()
		categories.size to repository.getDistinctMangaCount(categories.map { it.id }, space)
	}

	fun delete(id: Long, target: Long?, destructive: Boolean, onDeleted: () -> Unit) = mutate(onDeleted) {
		repository.deleteSet(id, space, target, destructive)
	}
}

data class CategorySetRow(val set: FavouriteCategorySetEntity, val categoryCount: Int, val isActive: Boolean)
