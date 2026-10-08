package org.koitharu.kotatsu.details.ui.pager.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpandedChaptersTest {
    @Test fun activeIsExpandedAndOtherChaptersStayClosed() {
        assertEquals(setOf(2L), reconcileExpandedChapters(emptySet(), setOf(1L, 2L, 3L), 2L, null))
    }
    @Test fun preservesUserChoicesUntilCurrentChapterChanges() {
        assertEquals(setOf(3L), reconcileExpandedChapters(setOf(3L), setOf(1L, 2L, 3L), 2L, 2L))
        assertEquals(setOf(1L, 3L), reconcileExpandedChapters(setOf(3L), setOf(1L, 2L, 3L), 1L, 2L))
    }
    @Test fun prunesRemovedChapterAndNeverExpandsMissingCurrent() {
        assertEquals(setOf(3L), reconcileExpandedChapters(setOf(2L, 3L), setOf(1L, 3L), 2L, null))
    }
}
