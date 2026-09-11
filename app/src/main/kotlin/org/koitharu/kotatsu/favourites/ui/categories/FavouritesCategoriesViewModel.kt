package org.koitharu.kotatsu.favourites.ui.categories

import androidx.collection.LongSet
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.isNovelContentSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.model.Cover
import org.koitharu.kotatsu.favourites.ui.categories.adapter.AllCategoriesListModel
import org.koitharu.kotatsu.favourites.ui.categories.adapter.CategoryListModel
import org.koitharu.kotatsu.favourites.ui.categories.adapter.SystemCategoryListModel
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import javax.inject.Inject

@HiltViewModel
class FavouritesCategoriesViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val repository: FavouritesRepository,
	private val settings: AppSettings,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val displayPreferences: FavouriteDisplayPreferences,
) : BaseViewModel() {

	val favouriteSpace: FavouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)
	private val selectedType = contentTypeStore.selectedType(favouriteSpace)
	val selectedContentType: FavouriteContentType
		get() = selectedType.value

	private var commitJob: Job? = null
	private val isActionsEnabled = MutableStateFlow(true)
	private val contentTypeState = combine(
		selectedType,
		contentTypeStore.novelCategoryIds,
		displayPreferences.hiddenVirtualCategoryIds,
	) { type, _, hiddenBySpace ->
		CategoryContentState(
			type = type,
			hiddenVirtualCategoryIds = hiddenBySpace[favouriteSpace]?.get(type).orEmpty(),
		)
	}

	/**
	 * Cover previews and counts are intentionally separate. Private DAO cover queries are capped at
	 * three rows per category, while one COUNT query supplies the real badge totals. This avoids the
	 * old full-library materialisation and keeps counts correct regardless of preview size.
	 */
	val content = combine(
		repository.observeCategoriesWithCovers(favouriteSpace),
		observeAllVisibility(),
		isActionsEnabled,
		contentTypeState,
	) { cats, showAll, hasActions, state ->
		val wantNovel = state.type == FavouriteContentType.NOVEL
		val typedCats = cats
			.filterKeys { category -> contentTypeStore.isCategoryForType(category.id, state.type) }
			.mapValues { (_, covers) -> covers.filter { it.mangaSource.isNovelContentSource == wantNovel }.take(3) }
		val categoryIds = typedCats.keys.map { it.id }
		val counts = repository.getCategoryCounts(categoryIds, favouriteSpace)
		val visibleCategoryIds = typedCats.keys
			.filter { it.isVisibleInLibrary }
			.map { it.id }
		val allCount = if (visibleCategoryIds.isEmpty()) {
			0
		} else {
			repository.getDistinctMangaCount(visibleCategoryIds, favouriteSpace)
		}
		val allCovers = typedCats.asSequence()
			.filter { (category, _) -> category.isVisibleInLibrary }
			.flatMap { (_, covers) -> covers.asSequence() }
			.distinct()
			.take(3)
			.toList()
		typedCats.toUiList(
			allFavorites = allCount to allCovers,
			categoryCounts = counts,
			showAll = showAll,
			hasActions = hasActions,
			type = state.type,
			hiddenVirtualCategoryIds = state.hiddenVirtualCategoryIds,
		)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	fun deleteCategories(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			repository.removeCategories(ids)
			contentTypeStore.removeCategories(ids)
		}
	}

	fun setAllCategoriesVisible(isVisible: Boolean) {
		settings.isAllFavouritesVisible = isVisible
	}

	fun setVirtualCategoryVisible(categoryId: Long, isVisible: Boolean) {
		displayPreferences.setVirtualCategoryVisible(
			space = favouriteSpace,
			type = selectedType.value,
			categoryId = categoryId,
			visible = isVisible,
		)
	}

	fun isEmpty(): Boolean = content.value.none { it is CategoryListModel }

	fun saveOrder(snapshot: List<ListModel>) {
		val prevJob = commitJob
		commitJob = launchJob {
			prevJob?.cancelAndJoin()
			val ids = snapshot.mapNotNullTo(ArrayList(snapshot.size)) {
				(it as? CategoryListModel)?.category?.id
			}
			if (ids.isNotEmpty()) repository.reorderCategories(ids)
		}
	}

	fun setIsVisible(ids: Set<Long>, isVisible: Boolean) {
		launchJob(Dispatchers.Default) {
			for (id in ids) repository.updateCategory(id, isVisible)
		}
	}

	fun setActionsEnabled(value: Boolean) {
		isActionsEnabled.value = value
	}

	fun getCategories(ids: LongSet): ArrayList<FavouriteCategory> {
		val items = content.requireValue()
		return items.mapNotNullTo(ArrayList(ids.size)) { item ->
			(item as? CategoryListModel)?.category?.takeIf { it.id in ids }
		}
	}

	private fun Map<FavouriteCategory, List<Cover>>.toUiList(
		allFavorites: Pair<Int, List<Cover>>,
		categoryCounts: Map<Long, Int>,
		showAll: Boolean,
		hasActions: Boolean,
		type: FavouriteContentType,
		hiddenVirtualCategoryIds: Set<Long>,
	): List<ListModel> {
		val systemCategoryCount = 1 +
			(if (type == FavouriteContentType.MANGA) 1 else 0) +
			(if (favouriteSpace == FavouriteSpace.PRIVATE) 2 else 0)
		val result = ArrayList<ListModel>(size + systemCategoryCount + 4)
		result.add(
			AllCategoriesListModel(
				mangaCount = allFavorites.first,
				covers = allFavorites.second,
				isVisible = showAll,
				isActionsEnabled = hasActions,
			),
		)
		result.add(ListHeader(textRes = R.string.favourites_system_categories))
		result.add(
			SystemCategoryListModel(
				id = DOWNLOADED_FAVOURITES_CATEGORY_ID,
				title = DOWNLOADED_FAVOURITES_CATEGORY_TITLE,
				isVisible = DOWNLOADED_FAVOURITES_CATEGORY_ID !in hiddenVirtualCategoryIds,
				isActionsEnabled = hasActions,
			),
		)
		if (type == FavouriteContentType.MANGA) {
			result.add(
				SystemCategoryListModel(
					id = LOCAL_FAVOURITES_CATEGORY_ID,
					title = LOCAL_FAVOURITES_CATEGORY_TITLE,
					isVisible = LOCAL_FAVOURITES_CATEGORY_ID !in hiddenVirtualCategoryIds,
					isActionsEnabled = hasActions,
				),
			)
		}
		if (favouriteSpace == FavouriteSpace.PRIVATE) {
			result.add(
				SystemCategoryListModel(
					id = PRIVATE_IN_PROGRESS_CATEGORY_ID,
					title = PRIVATE_IN_PROGRESS_CATEGORY_TITLE,
					isVisible = PRIVATE_IN_PROGRESS_CATEGORY_ID !in hiddenVirtualCategoryIds,
					isActionsEnabled = hasActions,
				),
			)
			result.add(
				SystemCategoryListModel(
					id = PRIVATE_COMPLETED_CATEGORY_ID,
					title = PRIVATE_COMPLETED_CATEGORY_TITLE,
					isVisible = PRIVATE_COMPLETED_CATEGORY_ID !in hiddenVirtualCategoryIds,
					isActionsEnabled = hasActions,
				),
			)
		}
		result.add(ListHeader(textRes = R.string.favourites_user_categories))
		mapTo(result) { (category, covers) ->
			CategoryListModel(
				mangaCount = categoryCounts[category.id] ?: 0,
				covers = covers,
				category = category,
				isActionsEnabled = hasActions,
				isTrackerEnabled = favouriteSpace == FavouriteSpace.NORMAL &&
					settings.isTrackerEnabled &&
					AppSettings.TRACK_FAVOURITES in settings.trackSources,
			)
		}
		return result
	}

	private fun observeAllVisibility(): Flow<Boolean> = settings.observeAsFlow(
		AppSettings.KEY_ALL_FAVOURITES_VISIBLE,
	) { isAllFavouritesVisible }

	private data class CategoryContentState(
		val type: FavouriteContentType,
		val hiddenVirtualCategoryIds: Set<Long>,
	)
}
