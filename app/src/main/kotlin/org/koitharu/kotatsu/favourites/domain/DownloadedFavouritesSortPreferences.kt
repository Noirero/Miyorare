package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.list.domain.ListSortOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Independent sort state for the virtual Downloaded favourites category. */
@Singleton
class DownloadedFavouritesSortPreferences @Inject constructor(
	@ApplicationContext context: Context,
	appSettings: AppSettings,
) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
	private val mutableState = MutableStateFlow(
		ListSortOrder(
			prefs.getString(KEY_SORT_ORDER, null).orEmpty(),
			appSettings.allFavoritesSortOrder,
		),
	)

	val state: StateFlow<ListSortOrder> = mutableState.asStateFlow()

	fun set(value: ListSortOrder) {
		prefs.edit { putString(KEY_SORT_ORDER, value.name) }
		mutableState.value = value
	}

	private companion object {
		const val KEY_SORT_ORDER = "favourites_downloaded_sort_order"
	}
}
