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

	private val normalState = MutableStateFlow<Map<FavouriteContentType, Set<String>>>(emptyMap())
	private val privateStateMutable = MutableStateFlow<Map<FavouriteContentType, Set<String>>>(emptyMap())

	/** Backwards-compatible NORMAL state used by existing Classic/normal Favourites call sites. */
	val state: StateFlow<Map<FavouriteContentType, Set<String>>> = normalState.asStateFlow()
	val privateState: StateFlow<Map<FavouriteContentType, Set<String>>> = privateStateMutable.asStateFlow()

	fun state(space: FavouriteSpace): StateFlow<Map<FavouriteContentType, Set<String>>> =
		if (space == FavouriteSpace.PRIVATE) privateState else state

	fun set(
		type: FavouriteContentType,
		sourceName: String,
		isSelected: Boolean,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) {
		val target = if (space == FavouriteSpace.PRIVATE) privateStateMutable else normalState
		target.update { current ->
			val selected = current[type].orEmpty()
			val updated = if (isSelected) selected + sourceName else selected - sourceName
			if (updated == selected) current else current + (type to updated)
		}
	}

	fun clear(type: FavouriteContentType, space: FavouriteSpace = FavouriteSpace.NORMAL) {
		val target = if (space == FavouriteSpace.PRIVATE) privateStateMutable else normalState
		target.update { current ->
			if (current[type].isNullOrEmpty()) current else current - type
		}
	}
}
