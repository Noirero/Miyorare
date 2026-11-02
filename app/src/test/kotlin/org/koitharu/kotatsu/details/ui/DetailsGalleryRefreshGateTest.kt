package org.koitharu.kotatsu.details.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsGalleryRefreshGateTest {

    @Test fun detailsTabAllowsRefreshOnlyAtActualListTop() {
        assertTrue(detailsPullRefreshAllowed(gallerySelected = false, firstItem = 0, firstOffset = 0))
        assertFalse(detailsPullRefreshAllowed(gallerySelected = false, firstItem = 0, firstOffset = 1))
        assertFalse(detailsPullRefreshAllowed(gallerySelected = false, firstItem = 2, firstOffset = 0))
    }

    @Test fun galleryNeverEnablesDetailsRefreshEvenWhenParentListIsAtTop() {
        // Reproduces the former mismatch: the parent list is at top while the gallery grid
        // owns vertical scrolling. Its own scroll position must not enable parent refresh.
        assertFalse(detailsPullRefreshAllowed(gallerySelected = true, firstItem = 0, firstOffset = 0))
        assertFalse(detailsPullRefreshAllowed(gallerySelected = true, firstItem = 0, firstOffset = 150))
        assertFalse(detailsPullRefreshAllowed(gallerySelected = true, firstItem = 25, firstOffset = 0))
    }

    @Test fun switchingTabsAndReturningFromReaderRestoresCorrectOwnership() {
        assertTrue(detailsPullRefreshAllowed(false, 0, 0)) // Details top
        assertFalse(detailsPullRefreshAllowed(true, 0, 0)) // Gallery selected
        assertFalse(detailsPullRefreshAllowed(true, 0, 0)) // Restore gallery after Reader
        assertTrue(detailsPullRefreshAllowed(false, 0, 0)) // Switch back to Details
        assertFalse(detailsPullRefreshAllowed(false, 3, 0)) // Details scrolls
        assertFalse(detailsPullRefreshAllowed(true, 3, 0)) // Gallery selected again
        assertFalse(detailsPullRefreshAllowed(false, 3, 0)) // Restored scrolled Details
    }
}
