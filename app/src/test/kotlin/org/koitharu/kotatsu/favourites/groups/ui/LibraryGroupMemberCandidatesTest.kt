package org.koitharu.kotatsu.favourites.groups.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupMember

class LibraryGroupMemberCandidatesTest {
    private fun member(id: Long) = LibraryGroupMember(
        mangaId = id, position = 0, displayTitle = "Title $id", displayCoverUrl = null,
        isNsfw = false, contentRating = null, source = "MIHON_123",
    )
    private fun group(id: Long, name: String, space: FavouriteSpace, vararg mangaIds: Long) =
        LibraryGroup(
            id = id, title = name, coverUrl = null, members = mangaIds.map(::member),
            createdAt = 0L, space = space,
        )
    private fun title(id: Long) = SampleData.manga.copy(id = id, title = "Title $id")

    @Test fun excludesCurrentMembersAndMarksSameSpaceConflicts() {
        val current = group(1, "Current", FavouriteSpace.NORMAL, 11, 12)
        val other = group(2, "Other", FavouriteSpace.NORMAL, 13, 15)
        val items = buildLibraryGroupCandidates(
            listOf(title(11), title(12), title(13), title(14)), current, listOf(current, other),
        )
        assertEquals(listOf(13L, 14L), items.map { it.manga.id })
        assertEquals("Other", items[0].currentGroupName)
        assertNull(items[1].currentGroupName)
    }

    @Test fun privateCandidatesNeverExposeNormalGroupMembership() {
        val private = group(9, "Private group", FavouriteSpace.PRIVATE, 21, 22)
        val normal = group(2, "Normal group", FavouriteSpace.NORMAL, 23, 24)
        val items = buildLibraryGroupCandidates(
            listOf(title(21), title(23)), private, listOf(private, normal),
        )
        assertEquals(listOf(23L), items.map { it.manga.id })
        assertNull(items.single().currentGroupName)
    }

    @Test fun duplicateLibraryRowsBecomeSingleCandidate() {
        val current = group(1, "Current", FavouriteSpace.NORMAL, 11, 12)
        assertEquals(
            listOf(13L),
            buildLibraryGroupCandidates(
                listOf(title(13), title(13)), current, listOf(current),
            ).map { it.manga.id },
        )
    }
}
