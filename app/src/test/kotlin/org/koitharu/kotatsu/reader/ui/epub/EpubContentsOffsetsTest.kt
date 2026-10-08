package org.koitharu.kotatsu.reader.ui.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.reader.ui.ReaderState

class EpubContentsOffsetsTest {
    @Test fun trimsLeadingWhitespaceWithoutShiftingSavedTextPosition() {
        assertEquals(listOf("Start" to 0, "Later" to 117),
            mapEpubContentsOffsets(listOf("Later" to 120, "Start" to 2), 3, 200))
    }
    @Test fun clampsEmptyAndTrailingSectionsAndPreservesDistinctTocLabels() {
        assertEquals(listOf("A" to 0, "B" to 0), mapEpubContentsOffsets(listOf("A" to 0, "B" to 5, "A" to 0), 0, 0))
        assertEquals(listOf("End" to 9), mapEpubContentsOffsets(listOf("End" to 12), 0, 10))
    }
    @Test fun exactResumeOffsetSurvivesReaderStateRoundTripAndLegacyRemainsLegacy() {
        for (offset in listOf(0, 1, 117, 1000000)) {
            assertEquals(offset, requireNotNull(ReaderState.decodeEpubOffset(ReaderState.encodeEpubOffset(offset))))
        }
        assertNull(ReaderState.decodeEpubOffset(500))
    }
}
