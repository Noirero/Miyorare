package org.koitharu.kotatsu.favourites.groups.ui

import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.list.ui.model.ListModel

interface LibraryGroupUiModel : ListModel {
	val group: LibraryGroup
	val isPinned: Boolean

	val id: Long
		get() = group.id

	val title: String
		get() = group.title

	val memberCount: Int
		get() = group.members.size

	val coverUrl: String?
		get() = group.coverUrl ?: group.members.firstOrNull()?.displayCoverUrl

	val fallbackCoverSource: MangaSource
		get() = MangaSource(group.members.first().source)
}

data class LibraryGroupListModel(
	override val group: LibraryGroup,
	override val isPinned: Boolean = false,
) : LibraryGroupUiModel {

	override fun areItemsTheSame(other: ListModel): Boolean =
		other is LibraryGroupListModel && other.group.id == group.id
}

data class LibraryGroupGridModel(
	override val group: LibraryGroup,
	override val isPinned: Boolean = false,
) : LibraryGroupUiModel {

	override fun areItemsTheSame(other: ListModel): Boolean =
		other is LibraryGroupGridModel && other.group.id == group.id
}
