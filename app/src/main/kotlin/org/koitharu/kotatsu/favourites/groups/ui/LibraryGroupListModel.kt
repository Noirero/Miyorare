package org.koitharu.kotatsu.favourites.groups.ui

import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga

data class LibraryGroupListModel(
	val group: LibraryGroup,
) : ListModel {

	constructor(group: LibraryGroup, @Suppress("UNUSED_PARAMETER") coverManga: Manga) : this(group)

	val id: Long
		get() = group.id

	val title: String
		get() = group.title

	val memberCount: Int
		get() = group.members.size

	val coverUrl: String?
		get() = group.coverUrl ?: group.members.firstOrNull()?.displayCoverUrl

	val fallbackCoverSource
		get() = MangaSource(group.members.first().source)

	override fun areItemsTheSame(other: ListModel): Boolean =
		other is LibraryGroupListModel && other.group.id == group.id
}
