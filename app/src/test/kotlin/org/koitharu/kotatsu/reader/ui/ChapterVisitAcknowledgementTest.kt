package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterVisitAcknowledgementTest {
    @Test fun pageChangesInSameChapterCannotUndoManualUnread() {
        val gate = ChapterVisitAcknowledgement()
        assertTrue(gate.shouldAcknowledge(218))
        assertFalse(gate.shouldAcknowledge(218))
        assertFalse(gate.shouldAcknowledge(218))
        assertTrue(gate.shouldAcknowledge(217))
        assertTrue(gate.shouldAcknowledge(218))
    }

    @Test fun explicitReopenCreatesNewVisitForSameChapter() {
        val gate = ChapterVisitAcknowledgement()
        assertTrue(gate.shouldAcknowledge(218))
        assertFalse(gate.shouldAcknowledge(218))
        gate.reset()
        assertTrue(gate.shouldAcknowledge(218))
        assertFalse(gate.shouldAcknowledge(218))
    }
}
