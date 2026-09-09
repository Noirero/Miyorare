package org.koitharu.kotatsu.favourites.groups.ui

import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupMember
import org.koitharu.kotatsu.parsers.model.Manga

data class LibraryGroupManageItem(
	val member: LibraryGroupMember,
	val manga: Manga,
)
