package org.koitharu.kotatsu.tracker.ui.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedHistoryAdvancePolicyTest {

    @Test
    fun `acknowledging an old notification never rewinds reader progress`() {
        assertFalse(shouldAdvanceFeedHistory(currentIndex = 18, feedIndex = 11))
        assertFalse(shouldAdvanceFeedHistory(currentIndex = 11, feedIndex = 11))
    }

    @Test
    fun `acknowledging a future notification may advance progress`() {
        assertTrue(shouldAdvanceFeedHistory(currentIndex = 11, feedIndex = 18))
        assertTrue(shouldAdvanceFeedHistory(currentIndex = null, feedIndex = 18))
    }
}
