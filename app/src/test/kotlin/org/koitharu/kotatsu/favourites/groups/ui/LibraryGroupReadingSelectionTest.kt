package org.koitharu.kotatsu.favourites.groups.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupTimelineItem
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryEntity

class LibraryGroupReadingSelectionTest {
    private val timeline = listOf(
        LibraryGroupTimelineItem(1L, 11L, 0),
        LibraryGroupTimelineItem(2L, 21L, 1),
        LibraryGroupTimelineItem(1L, 12L, 2),
    )
    private val all = timeline.mapTo(HashSet()) { it.mangaId to it.chapterId }

    @Test fun emptyTimelineHasNoGroupReadingAction() {
        assertNull(selectGroupContinueEntry(emptyList(), 0, emptySet()))
    }

    @Test fun missingProgressStartsAtFirstSavedEntry() {
        assertEquals(timeline[0], selectGroupContinueEntry(timeline, 0, all))
        assertEquals(timeline[0], selectGroupContinueEntry(timeline, -1, all))
    }

    @Test fun progressPicksNextSavedChapterAcrossManga() {
        assertEquals(timeline[1], selectGroupContinueEntry(timeline, 1, all))
        assertEquals(timeline[2], selectGroupContinueEntry(timeline, 3, all))
    }

    @Test fun missingTargetDoesNotSilentlySkipToNextManga() {
        val missingMiddle = setOf(1L to 11L, 1L to 12L)
        assertNull(selectGroupContinueEntry(timeline, 1, missingMiddle))
        assertEquals(1, unresolvedGroupTimelineCount(timeline, missingMiddle))
    }

    @Test fun resolvedCountReflectsPersistedTimelineWithoutDataMutation() {
        assertEquals(3, unresolvedGroupTimelineCount(timeline, emptySet()))
        assertEquals(0, unresolvedGroupTimelineCount(timeline, all))
    }
    @Test fun historyProgressCannotCrossNormalPrivateBoundary() {
        val privateHistory = HistoryEntity(
            mangaId = 1L, createdAt = 0L, updatedAt = 0L, chapterId = 11L,
            page = 3, scroll = 0f, percent = 0f, deletedAt = 0L,
            chaptersCount = 3, space = FavouriteSpace.PRIVATE.dbValue,
        )
        assertFalse(isGroupHistoryEligible(privateHistory, FavouriteSpace.NORMAL))
        assertTrue(isGroupHistoryEligible(privateHistory, FavouriteSpace.PRIVATE))
        assertFalse(isGroupHistoryEligible(privateHistory.copy(deletedAt = 1L), FavouriteSpace.PRIVATE))
    }

    @Test fun resumeUsesNewestHistoryNotFurthestTrackedChapter() {
        val base = HistoryEntity(
            mangaId = 1L, createdAt = 0L, updatedAt = 100L, chapterId = 12L,
            page = 0, scroll = 0f, percent = 0f, deletedAt = 0L,
            chaptersCount = 3, space = FavouriteSpace.NORMAL.dbValue,
        )
        val recentlyRead = base.copy(mangaId = 2L, chapterId = 21L, updatedAt = 300L)
        assertEquals(2, groupContinuePositionFromHistories(timeline, listOf(base, recentlyRead), FavouriteSpace.NORMAL))
        assertEquals(0, groupContinuePositionFromHistories(timeline, listOf(base.copy(space = 1)), FavouriteSpace.NORMAL))
        assertEquals(0, groupContinuePositionFromHistories(emptyList(), listOf(base), FavouriteSpace.NORMAL))
    }
}
