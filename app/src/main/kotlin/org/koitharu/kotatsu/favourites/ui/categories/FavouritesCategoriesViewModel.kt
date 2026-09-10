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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.isNovelContent
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
	val selectedContentType: FavouriteContentType
		get() = contentTypeStore.selectedType.value

	private var commitJob: Job? = null
	private val isActionsEnabled = MutableStateFlow(true)
	private val contentTypeState = combine(
		contentTypeStore.selectedType,
		contentTypeStore.novelCategoryIds,
		displayPreferences.hiddenVirtualCategoryIds,
	) { type, _, hiddenBySpace ->
		CategoryContentState(
			type = type,
			hiddenVirtualCategoryIds = hiddenBySpace[favouriteSpace]?.get(type).orEmpty(),
		)
	}

	val content = combine(
		repository.observeCategoriesWithCovers(favouriteSpace),
		observeAllCategories(),
		observeAllVisibility(),
		isActionsEnabled,
		contentTypeState,
	) { cats, _, showAll, hasActions, state ->
		val wantNovel = state.type == FavouriteContentType.NOVEL
		val typedCats = cats
			.filterKeys { category -> contentTypeStore.isCategoryForType(category.id, state.type) }
			.mapValues { (_, covers) -> covers.filter { it.mangaSource.isNovelContentSource == wantNovel } }
		val allManga = repository.getAllManga(favouriteSpace).filter { it.isNovelContent == wantNovel }
		val typedAll = allManga.size to allManga.take(3).map { manga -> Cover(manga.coverUrl, manga.source.name) }
		typedCats.toUiList(
			allFavorites = typedAll,
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
		// Display capabilities are shared by default. Private must honor the same All-category toggle
		// instead of forcing an always-visible shelf that behaves differently from Normal.
		settings.isAllFavouritesVisible = isVisible
	}

	fun setVirtualCategoryVisible(categoryId: Long, isVisible: Boolean) {
		displayPreferences.setVirtualCategoryVisible(
			space = favouriteSpace,
			type = contentTypeStore.selectedType.value,
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
				mangaCount = covers.size,
				covers = covers.take(3),
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

	private fun observeAllCategories(): Flow<Pair<Int, List<Cover>>> {
		return settings.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) {
			allFavoritesSortOrder
		}.mapLatest { order ->
			repository.getAllFavoritesCovers(order, limit = 3, space = favouriteSpace)
		}.combine(repository.observeMangaCount(favouriteSpace)) { covers, count ->
			count to covers
		}
	}

	private data class CategoryContentState(
		val type: FavouriteContentType,
		val hiddenVirtualCategoryIds: Set<Long>,
	)
}
