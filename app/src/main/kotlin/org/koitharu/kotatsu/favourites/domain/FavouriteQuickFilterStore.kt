package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.list.domain.ListFilterOption
import javax.inject.Inject
import javax.inject.Singleton

/** Session-wide quick filters for Favourites category pages. */
@Singleton
class FavouriteQuickFilterStore @Inject constructor(
	networkState: NetworkState,
) {

	data class Snapshot(
		val shared: Set<ListFilterOption> = emptySet(),
		val typed: Map<FavouriteContentType, Set<ListFilterOption>> = emptyMap(),
	) {
		fun filtersFor(type: FavouriteContentType): Set<ListFilterOption> =
			shared + typed[type].orEmpty()
	}

	private val mutableState = MutableStateFlow(
		Snapshot(
			shared = if (networkState.value) emptySet() else setOf(ListFilterOption.Downloaded),
		),
	)
	val state: StateFlow<Snapshot> = mutableState.asStateFlow()

	fun set(
		type: FavouriteContentType,
		option: ListFilterOption,
		isSelected: Boolean,
	) {
		mutableState.update { current ->
			if (option.isTypeSpecific()) {
				val selected = current.typed[type].orEmpty().updateSelection(option, isSelected)
				val typed = if (selected.isEmpty()) current.typed - type else current.typed + (type to selected)
				if (typed == current.typed) current else current.copy(typed = typed)
			} else {
				val shared = current.shared.updateSelection(option, isSelected)
				if (shared == current.shared) current else current.copy(shared = shared)
			}
		}
	}

	fun toggle(type: FavouriteContentType, option: ListFilterOption) {
		val isSelected = option in mutableState.value.filtersFor(type)
		set(type, option, !isSelected)
	}

	fun clear(type: FavouriteContentType) {
		mutableState.update { current ->
			if (current.shared.isEmpty() && current.typed[type].isNullOrEmpty()) {
				current
			} else {
				current.copy(shared = emptySet(), typed = current.typed - type)
			}
		}
	}

	private fun Set<ListFilterOption>.updateSelection(
		option: ListFilterOption,
		isSelected: Boolean,
	): Set<ListFilterOption> = if (!isSelected) {
		this - option
	} else {
		filterNot { it.groupKey == option.groupKey }.toSet() + option
	}

	private fun ListFilterOption.isTypeSpecific(): Boolean =
		this is ListFilterOption.ReadingProgress || this is ListFilterOption.State
}
