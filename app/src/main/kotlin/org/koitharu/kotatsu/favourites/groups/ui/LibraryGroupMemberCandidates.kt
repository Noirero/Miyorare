package org.koitharu.kotatsu.favourites.groups.ui

import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Pure projection of space-scoped favourites. The caller must load [manga] from its active
 * FavouriteSpace; groups from other spaces must never influence conflict labels.
 * Repository validation remains authoritative at commit time.
 */
internal fun buildLibraryGroupCandidates(
    manga: List<Manga>,
    currentGroup: LibraryGroup,
    groups: List<LibraryGroup>,
): List<LibraryGroupMemberCandidate> {
    val alreadyGrouped = groups.asSequence()
        .filter { it.space == currentGroup.space && it.id != currentGroup.id }
        .flatMap { group -> group.memberIds.asSequence().map { it to group.title } }
        .toMap()
    return manga.asSequence()
        .filterNot { it.isNovelContent || it.id in currentGroup.memberIds }
        .distinctBy { it.id }
        .sortedBy { it.title.lowercase() }
        .map { LibraryGroupMemberCandidate(it, alreadyGrouped[it.id]) }
        .toList()
}
