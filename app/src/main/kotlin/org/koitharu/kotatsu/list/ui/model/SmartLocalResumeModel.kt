package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.parsers.model.Manga

/** Presentation only; all positions and progress still come from HistoryEntity. */
data class SmartLocalResumeModel(val manga: Manga, val subtitle: String, val progress: Int) : ListModel {
	override fun areItemsTheSame(other: ListModel) = other is SmartLocalResumeModel && manga.id == other.manga.id
}

data class SmartLocalCollectionHeaderModel(val mode: ListMode) : ListModel {
	override fun areItemsTheSame(other: ListModel) = other is SmartLocalCollectionHeaderModel
}
