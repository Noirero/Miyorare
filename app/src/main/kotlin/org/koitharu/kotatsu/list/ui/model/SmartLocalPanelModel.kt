package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.local.library.LocalContentType
import org.koitharu.kotatsu.local.library.LocalLibrarySort
import org.koitharu.kotatsu.local.library.LocalReadingFilter

data class SmartLocalPanelModel(
	val folderCount: Int,
	val titleCount: Int,
	val chapterCount: Int,
	val query: String,
	val contentType: LocalContentType?,
	val sort: LocalLibrarySort,
	val readingFilter: LocalReadingFilter,
) : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other is SmartLocalPanelModel
}
