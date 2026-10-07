package org.koitharu.kotatsu.list.ui.model

data class SmartLocalPanelModel(
	val mangaCount: Int,
	val chapterCount: Int,
	val readingCount: Int,
	val newCount: Int,
	val query: String,
) : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other is SmartLocalPanelModel
}
