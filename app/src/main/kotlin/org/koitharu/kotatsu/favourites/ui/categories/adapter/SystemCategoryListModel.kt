package org.koitharu.kotatsu.favourites.ui.categories.adapter

import org.koitharu.kotatsu.list.ui.model.ListModel

data class SystemCategoryListModel(
	val id: Long,
	val title: CharSequence,
	val isVisible: Boolean,
	val isActionsEnabled: Boolean,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SystemCategoryListModel && other.id == id
	}
}
