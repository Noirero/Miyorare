package org.koitharu.kotatsu.favourites.groups.ui

import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga

data class LibraryGroupListModel(
	val group: LibraryGroup,
	val coverManga: Manga,
) : ListModel {

	val id: Long
		get() = group.id

	val title: String
		get() = group.title

	val memberCount: Int
		get() = group.members.size

	val coverUrl: String?
		get() = group.coverUrl ?: group.members.firstOrNull()?.displayCoverUrl ?: coverManga.coverUrl

	override fun areItemsTheSame(other: ListModel): Boolean =
		other is LibraryGroupListModel && other.group.id == group.id
}
