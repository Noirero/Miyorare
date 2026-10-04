package org.koitharu.kotatsu.local.domain

import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.Manga

private val TITLE_WHITESPACE = Regex("\\s+")

/** Title equality is only a hint for a badge, never a manga identity or membership key. */
internal fun String.localTitleKey(): String = trim().lowercase().replace(TITLE_WHITESPACE, " ")

internal fun Collection<Manga>.localTitleKeys(): Set<String> = asSequence()
	.flatMap { sequenceOf(it.title) + it.altTitles.asSequence() }
	.map { it.localTitleKey() }
	.filter { it.isNotEmpty() }
	.toHashSet()

internal fun MangaListModel.withLocalAvailability(titleKeys: Set<String>): MangaListModel {
	val available = !manga.isLocal && titleKeys.isNotEmpty() &&
		(manga.title.localTitleKey() in titleKeys || manga.altTitles.any { it.localTitleKey() in titleKeys })
	return when (this) {
		is MangaGridModel -> copy(isAvailableInLocal = available)
		is MangaDetailedListModel -> copy(isAvailableInLocal = available)
		is MangaCompactListModel -> copy(isAvailableInLocal = available)
	}
}
