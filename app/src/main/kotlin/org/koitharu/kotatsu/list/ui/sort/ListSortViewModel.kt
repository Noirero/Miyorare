package org.koitharu.kotatsu.list.ui.sort

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.DownloadedFavouritesSortPreferences
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.ui.config.ListConfigSection
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class ListSortViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val settings: AppSettings,
	private val favouritesRepository: FavouritesRepository,
	private val downloadedSortPreferences: DownloadedFavouritesSortPreferences,
) : BaseViewModel() {

	private val section = savedStateHandle.require<ListConfigSection>(AppRouter.KEY_LIST_SECTION)

	val types: List<ListSortOrder.Type> = when (section) {
		is ListConfigSection.Favorites -> ListSortOrder.FAVORITES
		else -> ListSortOrder.HISTORY
	}

	val sortOrder = MutableStateFlow(
		when (section) {
			is ListConfigSection.Favorites -> if (section.categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID) {
				downloadedSortPreferences.state.value
			} else {
				settings.allFavoritesSortOrder
			}

			else -> settings.historySortOrder
		},
	)

	init {
		// Real categories keep their sort in the database. The two virtual categories use preferences.
		val categoryId = (section as? ListConfigSection.Favorites)?.categoryId
		if (
			categoryId != null &&
			categoryId != NO_ID &&
			categoryId != DOWNLOADED_FAVOURITES_CATEGORY_ID
		) {
			launchJob(Dispatchers.Default) {
				runCatchingCancellable {
					favouritesRepository.getCategory(categoryId).order
				}.onSuccess { sortOrder.value = it }
			}
		}
	}

	/** Mihon's rule: tapping the active column flips the direction, tapping another one keeps it. */
	fun onTypeClick(type: ListSortOrder.Type) {
		val current = sortOrder.value
		val isAscending = if (current.type == type) !current.isAscending else current.isAscending
		val value = type.toSortOrder(isAscending)
		sortOrder.value = value
		when (section) {
			is ListConfigSection.Favorites -> when (section.categoryId) {
				NO_ID -> settings.allFavoritesSortOrder = value
				DOWNLOADED_FAVOURITES_CATEGORY_ID -> downloadedSortPreferences.set(value)
				else -> launchJob(Dispatchers.Default) {
					favouritesRepository.setCategoryOrder(section.categoryId, value)
				}
			}

			else -> settings.historySortOrder = value
		}
	}
}
