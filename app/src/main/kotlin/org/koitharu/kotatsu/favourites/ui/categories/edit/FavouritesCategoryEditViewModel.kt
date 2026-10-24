package org.koitharu.kotatsu.favourites.ui.categories.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.ui.categories.edit.FavouritesCategoryEditActivity.Companion.NO_ID
import org.koitharu.kotatsu.list.domain.ListSortOrder
import javax.inject.Inject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SET_ID
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_CONTENT_TYPE
import org.koitharu.kotatsu.favourites.data.FavouriteCategorySetEntity
import org.koitharu.kotatsu.favourites.domain.FavouriteSetSelectionStore
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType

@HiltViewModel
class FavouritesCategoryEditViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val repository: FavouritesRepository,
	private val settings: AppSettings,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val setSelection: FavouriteSetSelectionStore,
) : BaseViewModel() {

	private val categoryId = savedStateHandle[AppRouter.KEY_ID] ?: NO_ID
	val favouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)

	private val requestedSetId: Long? = savedStateHandle[EXTRA_FAVOURITE_SET_ID]
	private val createType = FavouriteContentType.entries.firstOrNull { it.name == savedStateHandle.get<String>(EXTRA_FAVOURITE_CONTENT_TYPE) }
		?: contentTypeStore.selectedType(favouriteSpace).value
	val sets = repository.observeSets(favouriteSpace).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
	val currentSet = MutableStateFlow<FavouriteCategorySetEntity?>(null)

	fun moveToSet(set: FavouriteCategorySetEntity) {
		launchLoadingJob(Dispatchers.IO) {
			if (categoryId != NO_ID) repository.moveCategoriesToSet(listOf(categoryId), set.setId, favouriteSpace)
			withContext(Dispatchers.Main.immediate) {
				currentSet.value = set
				savedStateHandle[EXTRA_FAVOURITE_SET_ID] = set.setId
			}
		}
	}

	val onSaved = MutableEventFlow<Unit>()
	val category = MutableStateFlow<FavouriteCategory?>(null)

	val isTrackerEnabled = flow {
		val globalFeaturesAllowed = favouriteSpace == FavouriteSpace.NORMAL ||
			repository.isPrivateIsolationDisabled()
		emit(
			globalFeaturesAllowed &&
				settings.isTrackerEnabled &&
				AppSettings.TRACK_FAVOURITES in settings.trackSources,
		)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	init {
		launchLoadingJob(Dispatchers.Default) {
			currentSet.value = if (categoryId != NO_ID) repository.getCategorySet(categoryId, favouriteSpace)
			else repository.resolveSet(requestedSetId ?: setSelection.observe(favouriteSpace, flowOf(createType)).first().setId, favouriteSpace)
			category.value = if (categoryId != NO_ID) {
				repository.getCategory(categoryId)
			} else {
				null
			}
		}
	}

	fun save(
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isNewChaptersDownloadEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	) {
		launchLoadingJob(Dispatchers.Default) {
			check(title.isNotEmpty())
			if (categoryId == NO_ID) {
				val category = repository.createCategory(
					title = title,
					sortOrder = sortOrder,
					isTrackerEnabled = isTrackerEnabled,
					isNewChaptersDownloadEnabled = isNewChaptersDownloadEnabled,
					isVisibleOnShelf = isVisibleOnShelf,
					space = favouriteSpace,
					setId = currentSet.value?.setId,
				)
				contentTypeStore.setCategoryType(
					category.id,
					createType,
				)
			} else {
				repository.updateCategory(
					id = categoryId,
					title = title,
					sortOrder = sortOrder,
					isTrackerEnabled = isTrackerEnabled,
					isNewChaptersDownloadEnabled = isNewChaptersDownloadEnabled,
					isVisibleOnShelf = isVisibleOnShelf,
				)
			}
			onSaved.call(Unit)
		}
	}
}
