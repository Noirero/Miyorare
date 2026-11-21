package org.koitharu.kotatsu.favourites.ui.container

import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.list.ui.model.ListModel

internal data class FavouriteTabs(
	val type: FavouriteContentType,
	val items: List<FavouriteTabModel>,
)

data class FavouriteTabModel(
	val id: Long,
	val title: String?,
	val count: Int,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is FavouriteTabModel && other.id == id
	}
}
