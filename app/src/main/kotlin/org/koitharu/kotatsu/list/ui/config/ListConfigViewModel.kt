package org.koitharu.kotatsu.list.ui.config

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.favourites.domain.FavouriteCategoryNavigationMode
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import javax.inject.Inject

@HiltViewModel
class ListConfigViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val settings: AppSettings,
	contentTypeStore: FavouriteContentTypeStore,
	private val favouriteDisplayPreferences: FavouriteDisplayPreferences,
) : BaseViewModel() {

	val section = savedStateHandle.require<ListConfigSection>(AppRouter.KEY_LIST_SECTION)
	val isFavouriteSection: Boolean
		get() = section is ListConfigSection.Favorites

	/** The shelf cannot change while this sheet is open, so snapshot it when the sheet is created. */
	private val favouriteType: FavouriteContentType = contentTypeStore.selectedType.value

	private fun favouriteOptions() = favouriteDisplayPreferences.current(favouriteType)

	var listMode: ListMode
		get() = when (section) {
			is ListConfigSection.Favorites -> favouriteOptions().listMode
			ListConfigSection.History -> settings.historyListMode
			ListConfigSection.Suggestions -> settings.suggestionsListMode
			ListConfigSection.General,
			ListConfigSection.Updated -> settings.listMode
		}
		set(value) {
			when (section) {
				is ListConfigSection.Favorites -> favouriteDisplayPreferences.setListMode(favouriteType, value)
				ListConfigSection.History -> settings.historyListMode = value
				ListConfigSection.Suggestions -> settings.suggestionsListMode = value
				ListConfigSection.Updated,
				ListConfigSection.General -> settings.listMode = value
			}
		}

	var gridColumns: Int
		get() = favouriteOptions().gridColumns
		set(value) = favouriteDisplayPreferences.setGridColumns(favouriteType, value)

	var gridSize: Int
		get() = if (isFavouriteSection) favouriteOptions().gridSize else settings.gridSize
		set(value) {
			if (isFavouriteSection) {
				favouriteDisplayPreferences.setGridSize(favouriteType, value)
			} else {
				settings.gridSize = value
			}
		}

	var isTitleOverCover: Boolean
		get() = if (isFavouriteSection) favouriteOptions().titleOverCover else settings.isTitleOverCover
		set(value) {
			if (isFavouriteSection) {
				favouriteDisplayPreferences.setTitleOverCover(favouriteType, value)
			} else {
				settings.isTitleOverCover = value
			}
		}

	var isGridSpacingIncreased: Boolean
		get() = if (isFavouriteSection) favouriteOptions().gridSpacingIncreased else settings.isGridSpacingIncreased
		set(value) {
			if (isFavouriteSection) {
				favouriteDisplayPreferences.setGridSpacingIncreased(favouriteType, value)
			} else {
				settings.isGridSpacingIncreased = value
			}
		}

	var showDownloaded: Boolean
		get() = favouriteOptions().showDownloaded
		set(value) = favouriteDisplayPreferences.setShowDownloaded(favouriteType, value)

	var showUnread: Boolean
		get() = favouriteOptions().showUnread
		set(value) = favouriteDisplayPreferences.setShowUnread(favouriteType, value)

	var showLocalSource: Boolean
		get() = favouriteOptions().showLocalSource
		set(value) = favouriteDisplayPreferences.setShowLocalSource(favouriteType, value)

	var showLanguage: Boolean
		get() = favouriteOptions().showLanguage
		set(value) = favouriteDisplayPreferences.setShowLanguage(favouriteType, value)

	var showContinueReading: Boolean
		get() = favouriteOptions().showContinueReading
		set(value) = favouriteDisplayPreferences.setShowContinueReading(favouriteType, value)

	var showCategoryTabs: Boolean
		get() = favouriteOptions().showCategoryTabs
		set(value) = favouriteDisplayPreferences.setShowCategoryTabs(favouriteType, value)

	var showCategoryCounts: Boolean
		get() = favouriteOptions().showCategoryCounts
		set(value) = favouriteDisplayPreferences.setShowCategoryCounts(favouriteType, value)

	var categoryNavigationMode: FavouriteCategoryNavigationMode
		get() = favouriteDisplayPreferences.categoryNavigationMode.value
		set(value) = favouriteDisplayPreferences.setCategoryNavigationMode(value)

	val isGroupingSupported: Boolean
		get() = section == ListConfigSection.History || section == ListConfigSection.Updated

	val isGroupingAvailable: Boolean
		get() = when (section) {
			ListConfigSection.History -> settings.historySortOrder.isGroupingSupported()
			ListConfigSection.Updated -> true
			else -> false
		}

	var isGroupingEnabled: Boolean
		get() = when (section) {
			ListConfigSection.History -> settings.isHistoryGroupingEnabled
			ListConfigSection.Updated -> settings.isUpdatedGroupingEnabled
			else -> false
		}
		set(value) = when (section) {
			ListConfigSection.History -> settings.isHistoryGroupingEnabled = value
			ListConfigSection.Updated -> settings.isUpdatedGroupingEnabled = value
			else -> Unit
		}
}
