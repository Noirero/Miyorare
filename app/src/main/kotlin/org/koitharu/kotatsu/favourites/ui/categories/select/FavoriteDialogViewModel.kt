package org.koitharu.kotatsu.favourites.ui.categories.select

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
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
	@ApplicationContext appContext: Context,
) : BaseViewModel() {

	val manga = savedStateHandle.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST).map {
		it.manga
	}
	val favouriteSpace: FavouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)
	private val contentType = if (manga.firstOrNull()?.isNovelContent == true) {
		FavouriteContentType.NOVEL
	} else {
		FavouriteContentType.MANGA
	}
	private val isSingleNormalFavourite = favouriteSpace == FavouriteSpace.NORMAL && manga.size == 1
	private val categoryMemory = appContext.getSharedPreferences(CATEGORY_MEMORY_PREFS, Context.MODE_PRIVATE)
	private val restoredCategoryIds = MutableStateFlow<Set<Long>>(emptySet())

	private val pendingChanges = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
	val isSaving = MutableStateFlow(isSingleNormalFavourite)
	val onSaved = MutableEventFlow<Boolean>()
	private val savedContent = combine(
		favouritesRepository.observeCategories(favouriteSpace),
		settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
		contentTypeStore.novelCategoryIds,
	) { categories, tracker, _ ->
		mapList(
			categories.filter { contentTypeStore.isCategoryForType(it.id, contentType) },
			tracker && favouriteSpace == FavouriteSpace.NORMAL,
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

	init {
		if (isSingleNormalFavourite) {
			launchJob(Dispatchers.Default) {
				try {
					val mangaId = manga.single().id
					val activeCategories = favouritesRepository.getCategoriesIds(mangaId, FavouriteSpace.NORMAL)
					if (activeCategories.isNotEmpty()) {
						rememberCategories(mangaId, activeCategories)
						favouritesRepository.removeFromFavourites(listOf(mangaId), FavouriteSpace.NORMAL)
						onSaved.call(false)
					}
				} finally {
					isSaving.value = false
				}
			}
		}
	}

	fun setChecked(categoryId: Long, isChecked: Boolean) {
		if (isSaving.value) return
		pendingChanges.update { it + (categoryId to isChecked) }
	}

	fun save(openCategoryManagement: Boolean = false) {
		if (!isSaving.compareAndSet(expect = false, update = true)) return
		val pending = pendingChanges.value
		val changes = LinkedHashMap<Long, Boolean>(restoredCategoryIds.value.size + pending.size).apply {
			for (categoryId in restoredCategoryIds.value) put(categoryId, true)
			putAll(pending)
		}
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
				restoredCategoryIds.value = emptySet()
				if (openCategoryManagement) prepareCategoryManagement()
				onSaved.call(openCategoryManagement)
			} finally {
				isSaving.value = false
			}
		}
	}

	fun prepareCategoryManagement() {
		contentTypeStore.setSelectedType(contentType, favouriteSpace)
	}

	private suspend fun mapList(
		categories: List<FavouriteCategory>,
		tracker: Boolean,
	): List<ListModel> {
		if (categories.isEmpty()) {
			restoredCategoryIds.value = emptySet()
			return listOf(
				EmptyState(
					icon = 0,
					textPrimary = R.string.empty_favourite_categories,
					textSecondary = 0,
					actionStringRes = 0,
				),
			)
		}

		val selectedIds = manga.mapTo(HashSet(manga.size)) { it.id }
		val selectedCount = selectedIds.size
		val validCategoryIds = categories.mapTo(HashSet(categories.size)) { it.id }
		val countsByCategory = HashMap<Long, Int>(categories.size)
		var rememberedForRestore: Set<Long> = emptySet()
		for (mangaId in selectedIds) {
			val activeCategoryIds = favouritesRepository.getCategoriesIds(mangaId, favouriteSpace)
			val effectiveCategoryIds = if (
				isSingleNormalFavourite && activeCategoryIds.isEmpty()
			) {
				readRememberedCategories(mangaId).filterTo(LinkedHashSet()) { it in validCategoryIds }.also {
					rememberedForRestore = it
				}
			} else {
				activeCategoryIds
			}
			for (categoryId in effectiveCategoryIds) {
				countsByCategory[categoryId] = (countsByCategory[categoryId] ?: 0) + 1
			}
		}
		restoredCategoryIds.value = rememberedForRestore

		return categories.map { cat ->
			MangaCategoryItem(
				category = cat,
				checkedState = when (countsByCategory[cat.id] ?: 0) {
					0 -> MaterialCheckBox.STATE_UNCHECKED
					selectedCount -> MaterialCheckBox.STATE_CHECKED
					else -> MaterialCheckBox.STATE_INDETERMINATE
				},
				isTrackerEnabled = tracker,
			)
		}
	}

	private fun rememberCategories(mangaId: Long, categoryIds: Set<Long>) {
		categoryMemory.edit()
			.putStringSet(
				mangaId.toString(),
				categoryIds.mapTo(HashSet(categoryIds.size)) { it.toString() },
			)
			.apply()
	}

	private fun readRememberedCategories(mangaId: Long): Set<Long> = categoryMemory
		.getStringSet(mangaId.toString(), emptySet())
		.orEmpty()
		.mapNotNullTo(LinkedHashSet()) { it.toLongOrNull() }

	private companion object {
		const val CATEGORY_MEMORY_PREFS = "normal_favourite_category_memory"
	}
}
