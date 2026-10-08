package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCollectionEmptyReasonTest {
    @Test fun selectedEmptyOrRevokedRootNeverAsksToAddFirstFolder() {
        assertEquals(LocalCollectionEmptyReason.NO_FOLDERS, localCollectionEmptyReason(0, emptySet(), false, "", null))
        assertEquals(LocalCollectionEmptyReason.NO_CONTENT, localCollectionEmptyReason(1, emptySet(), false, "", null))
        assertEquals(LocalCollectionEmptyReason.ACCESS, localCollectionEmptyReason(1, emptySet(), true, "", null))
    }
    @Test fun searchAndTypeProjectionRetainTheUnderlyingLibraryContext() {
        assertEquals(LocalCollectionEmptyReason.SEARCH, localCollectionEmptyReason(1, setOf(LocalContentType.MANGA), false, "missing", LocalContentType.NOVEL))
        assertEquals(LocalCollectionEmptyReason.MANGA, localCollectionEmptyReason(1, setOf(LocalContentType.NOVEL), false, "", LocalContentType.MANGA))
        assertEquals(LocalCollectionEmptyReason.NOVEL, localCollectionEmptyReason(1, setOf(LocalContentType.MANGA), false, "", LocalContentType.NOVEL))
        assertEquals(LocalCollectionEmptyReason.FILTER, localCollectionEmptyReason(1, setOf(LocalContentType.MANGA), false, "", LocalContentType.MANGA))
    }
    @Test fun partialAccessDiagnosisDoesNotMislabelAFilteredNonemptyIndex() {
        assertEquals(LocalCollectionEmptyReason.SEARCH, localCollectionEmptyReason(1, setOf(LocalContentType.MANGA), true, "missing", null))
    }
}
