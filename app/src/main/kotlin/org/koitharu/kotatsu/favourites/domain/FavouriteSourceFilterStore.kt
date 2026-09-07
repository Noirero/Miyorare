package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import javax.inject.Inject
import javax.inject.Singleton

/** Session-wide source selections, kept independent for NORMAL and PRIVATE libraries. */
@Singleton
class FavouriteSourceFilterStore @Inject constructor() {

	private val mutableState = MutableStateFlow<Map<FavouriteSpace, Map<FavouriteContentType, Set<String>>>>(emptyMap())
	val state: StateFlow<Map<FavouriteSpace, Map<FavouriteContentType, Set<String>>>> = mutableState.asStateFlow()

	fun selections(space: FavouriteSpace): Map<FavouriteContentType, Set<String>> =
		mutableState.value[space].orEmpty()

	fun set(
		type: FavouriteContentType,
		sourceName: String,
		isSelected: Boolean,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) {
		mutableState.update { current ->
			val spaceState = current[space].orEmpty()
			val selected = spaceState[type].orEmpty()
			val updated = if (isSelected) selected + sourceName else selected - sourceName
			if (updated == selected) current else current + (space to (spaceState + (type to updated)))
		}
	}

	fun clear(type: FavouriteContentType, space: FavouriteSpace = FavouriteSpace.NORMAL) {
		mutableState.update { current ->
			val spaceState = current[space].orEmpty()
			if (spaceState[type].isNullOrEmpty()) return@update current
			val updated = spaceState - type
			if (updated.isEmpty()) current - space else current + (space to updated)
		}
	}
}
