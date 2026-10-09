package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalContentSelectionTest {
    @Test fun savedAllOverridesPreviousPersistedNovelSelection() {
        assertNull(restoreLocalContentSelection(true, null, LocalContentType.NOVEL))
    }
    @Test fun savedTypeAndStartupPreferenceHaveDifferentPrecedence() {
        assertEquals(LocalContentType.MANGA, restoreLocalContentSelection(true, "MANGA", LocalContentType.NOVEL))
        assertEquals(LocalContentType.NOVEL, restoreLocalContentSelection(false, null, LocalContentType.NOVEL))
    }
    @Test fun unknownSavedTypeFallsBackToAll() {
        assertNull(restoreLocalContentSelection(true, "unknown", LocalContentType.MANGA))
    }
}
