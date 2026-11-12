package org.koitharu.kotatsu.favourites.groups.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupTimelineItem

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
}
