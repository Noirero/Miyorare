package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCollectionEmptyReasonTest {
    @Test fun selectedEmptyOrRevokedRootNeverAsksToAddFirstFolder() {
        assertEquals(LocalCollectionEmptyReason.NO_FOLDERS, localCollectionEmptyReason(0, emptySet(), emptyList(), "", null))
        assertEquals(LocalCollectionEmptyReason.NO_CONTENT, localCollectionEmptyReason(1, emptySet(), emptyList(), "", null))
        assertEquals(LocalCollectionEmptyReason.ACCESS, localCollectionEmptyReason(1, emptySet(), listOf(LocalDiagnosis("root", null, "unavailable")), "", null))
    }
    @Test fun searchAndTypeProjectionRetainTheUnderlyingLibraryContext() {
        assertEquals(LocalCollectionEmptyReason.SEARCH, localCollectionEmptyReason(1, setOf(LocalContentType.MANGA), emptyList(), "missing", LocalContentType.NOVEL))
        assertEquals(LocalCollectionEmptyReason.MANGA, localCollectionEmptyReason(1, setOf(LocalContentType.NOVEL), emptyList(), "", LocalContentType.MANGA))
        assertEquals(LocalCollectionEmptyReason.NOVEL, localCollectionEmptyReason(1, setOf(LocalContentType.MANGA), emptyList(), "", LocalContentType.NOVEL))
        assertEquals(LocalCollectionEmptyReason.FILTER, localCollectionEmptyReason(1, setOf(LocalContentType.MANGA), emptyList(), "", LocalContentType.MANGA))
    }
    @Test fun emptyAndReviewDiagnosesNeverImplyRevokedPermission() {
        for (reason in listOf("empty", "review")) {
            assertEquals(LocalCollectionEmptyReason.NO_CONTENT,
                localCollectionEmptyReason(1, emptySet(), listOf(LocalDiagnosis("root", null, reason)), "", null))
        }
    }
    @Test fun partialAccessDiagnosisDoesNotMislabelAFilteredNonemptyIndex() {
        assertEquals(LocalCollectionEmptyReason.SEARCH, localCollectionEmptyReason(1, setOf(LocalContentType.MANGA), listOf(LocalDiagnosis("root", null, "unavailable")), "missing", null))
    }
}
