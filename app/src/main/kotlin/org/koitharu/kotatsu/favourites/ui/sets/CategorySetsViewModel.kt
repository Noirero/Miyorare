package org.koitharu.kotatsu.favourites.ui.sets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.favourites.data.*
import org.koitharu.kotatsu.favourites.domain.*
import javax.inject.Inject

@HiltViewModel
class CategorySetsViewModel @Inject constructor(
	savedState: SavedStateHandle, private val repository: FavouritesRepository,
	private val selection: FavouriteSetSelectionStore, private val contentTypes: FavouriteContentTypeStore,
) : BaseViewModel() {
	val space = FavouriteSpace.fromArgument(savedState[EXTRA_FAVOURITE_SPACE] ?: 0)
	val sets = repository.observeSets(space).withErrorHandling().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

	fun save(id: Long?, title: String, onSaved: () -> Unit) = launchLoadingJob(Dispatchers.IO) {
		if (id == null) selection.select(repository.createSet(title, space), space, contentTypes.selectedType(space).value)
		else repository.renameSet(id, title, space)
		onSaved()
	}

	fun reorder(id: Long, offset: Int) = launchLoadingJob(Dispatchers.IO) {
		val ids = repository.getSets(space).map { it.setId }.toMutableList()
		val index = ids.indexOf(id)
		val target = index + offset
		if (index >= 0 && target in ids.indices) {
			java.util.Collections.swap(ids, index, target)
			repository.reorderSets(ids, space)
		}
	}

	suspend fun impact(id: Long): Pair<Int, Int> {
		val categories = repository.observeCategoriesInSet(id, space).first()
		return categories.size to repository.getDistinctMangaCount(categories.map { it.id }, space)
	}

	fun delete(id: Long, target: Long?, destructive: Boolean, onDeleted: () -> Unit) = launchLoadingJob(Dispatchers.IO) {
		repository.deleteSet(id, space, target, destructive)
		onDeleted()
	}
}
