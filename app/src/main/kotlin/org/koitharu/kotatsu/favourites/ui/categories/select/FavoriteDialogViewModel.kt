package org.koitharu.kotatsu.favourites.ui.categories.select

import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.ids
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import javax.inject.Inject

@HiltViewModel
class FavoriteDialogViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val favouritesRepository: FavouritesRepository,
	settings: AppSettings,
	private val contentTypeStore: FavouriteContentTypeStore,
) : BaseViewModel() {

	val manga = savedStateHandle.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST).map {
		it.manga
	}
	private val contentType = if (manga.firstOrNull()?.source?.isNovelSource == true) {
		FavouriteContentType.NOVEL
	} else {
		FavouriteContentType.MANGA
	}

	private val pendingChanges = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
	val isSaving = MutableStateFlow(false)
	val onSaved = MutableEventFlow<Boolean>()
	private val savedContent = combine(
		favouritesRepository.observeCategories(),
		settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
		contentTypeStore.novelCategoryIds,
	) { categories, tracker, _ ->
		mapList(
			categories.filter { contentTypeStore.isCategoryForType(it.id, contentType) },
			tracker,
		)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	val content = combine(savedContent, pendingChanges) { saved, pending ->
		saved.map { model ->
			val item = model as? MangaCategoryItem ?: return@map model
			val checked = pending[item.category.id] ?: return@map item
			item.copy(
				checkedState = if (checked) {
					MaterialCheckBox.STATE_CHECKED
				} else {
					MaterialCheckBox.STATE_UNCHECKED
				},
			)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	fun setChecked(categoryId: Long, isChecked: Boolean) {
		if (isSaving.value) return
		pendingChanges.update { it + (categoryId to isChecked) }
	}

	fun save(openCategoryManagement: Boolean = false) {
		if (!isSaving.compareAndSet(expect = false, update = true)) return
		val changes = pendingChanges.value
		launchJob(Dispatchers.Default) {
			try {
				for ((categoryId, isChecked) in changes) {
					if (isChecked) {
						favouritesRepository.addToCategory(categoryId, manga)
					} else {
						favouritesRepository.removeFromCategory(categoryId, manga.ids())
					}
				}
				pendingChanges.value = emptyMap()
				if (openCategoryManagement) prepareCategoryManagement()
				onSaved.call(openCategoryManagement)
			} finally {
				isSaving.value = false
			}
		}
	}

	fun prepareCategoryManagement() {
		contentTypeStore.setSelectedType(contentType)
	}

	private suspend fun mapList(
		categories: List<FavouriteCategory>,
		tracker: Boolean,
	): List<ListModel> {
		if (categories.isEmpty()) {
			return listOf(
				EmptyState(
					icon = 0,
					textPrimary = R.string.empty_favourite_categories,
					textSecondary = 0,
					actionStringRes = 0,
				),
			)
		}
		val cats = MutableLongObjectMap<MutableLongSet>(categories.size)
		categories.forEach { cats[it.id] = MutableLongSet(manga.size) }
		for (m in manga) {
			val ids = favouritesRepository.getCategoriesIds(m.id)
			ids.forEach { id -> cats[id]?.add(m.id) }
		}
		return categories.map { cat ->
			MangaCategoryItem(
				category = cat,
				checkedState = when (cats[cat.id]?.size ?: 0) {
					0 -> MaterialCheckBox.STATE_UNCHECKED
					manga.size -> MaterialCheckBox.STATE_CHECKED
					else -> MaterialCheckBox.STATE_INDETERMINATE
				},
				isTrackerEnabled = tracker,
			)
		}
	}
}
