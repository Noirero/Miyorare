package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupTimelineItem

class LibraryGroupReaderBoundaryPolicyTest {
    private val timeline = listOf(
        LibraryGroupTimelineItem(10L, 100L, 0),
        LibraryGroupTimelineItem(20L, 200L, 1),
    )

    @Test fun noTimelineDoesNotEnableGroupChapterSwitch() {
        assertEquals(-1, groupTimelineChapterIndex(emptyList(), 10L, 100L))
    }

    @Test fun unlistedChapterDoesNotEnableGroupChapterSwitch() {
        assertEquals(-1, groupTimelineChapterIndex(timeline, 10L, 101L))
    }

    @Test fun explicitlyScheduledChaptersCanNavigateAcrossManga() {
        assertEquals(0, groupTimelineChapterIndex(timeline, 10L, 100L))
        assertEquals(1, groupTimelineChapterIndex(timeline, 20L, 200L))
    }

    @Test fun wrongMangaWithSameChapterIdCannotUseAnotherMemberTimeline() {
        assertEquals(-1, groupTimelineChapterIndex(timeline, 20L, 100L))
    }
}
