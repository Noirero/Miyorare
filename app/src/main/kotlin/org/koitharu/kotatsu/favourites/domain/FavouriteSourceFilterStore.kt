package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-wide source selections for the Favourites shelf.
 *
 * Category pages are separate ViewModels inside ViewPager2, so keeping the selection in a page-local
 * quick filter makes its checkmarks disappear on every category change. Store exact source names
 * here (Mihon names contain the source id) and keep Manga/Novel selections independent.
 */
@Singleton
class FavouriteSourceFilterStore @Inject constructor() {

	private val mutableState = MutableStateFlow<Map<FavouriteContentType, Set<String>>>(emptyMap())
	val state: StateFlow<Map<FavouriteContentType, Set<String>>> = mutableState.asStateFlow()

	fun set(type: FavouriteContentType, sourceName: String, isSelected: Boolean) {
		mutableState.update { current ->
			val selected = current[type].orEmpty()
			val updated = if (isSelected) selected + sourceName else selected - sourceName
			if (updated == selected) current else current + (type to updated)
		}
	}

	fun clear(type: FavouriteContentType) {
		mutableState.update { current ->
			if (current[type].isNullOrEmpty()) current else current - type
		}
	}
}
