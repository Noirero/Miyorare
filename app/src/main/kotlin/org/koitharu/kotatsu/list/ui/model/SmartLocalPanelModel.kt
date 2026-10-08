package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.local.library.LocalContentType

data class SmartLocalPanelModel(
	val folderCount: Int,
	val titleCount: Int,
	val chapterCount: Int,
	val readingCount: Int,
	val newCount: Int,
	val query: String,
	val contentType: LocalContentType?,
) : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other is SmartLocalPanelModel
}
