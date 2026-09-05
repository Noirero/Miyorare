package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.parsers.model.Manga

data class MangaCompactListModel(
	override val manga: Manga,
	override val override: MangaOverride?,
	val subtitle: String,
	override val counter: Int,
	val isPinned: Boolean = false,
	val isSaved: Boolean = false,
	val isLocalSource: Boolean = false,
	val languageLabel: String? = null,
	val showContinueReading: Boolean = false,
) : MangaListModel()
