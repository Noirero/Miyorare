package org.koitharu.kotatsu.tracker.ui.feed.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedSwipeDecisionTest {

	@Test fun leftSwipeReadsAnUnreadNotification() {
		assertEquals(FeedSwipeDecision.MARK_READ, resolveFeedSwipeDecision(true, true))
	}
	@Test fun leftSwipeReopensAReadNotification() {
		assertEquals(FeedSwipeDecision.MARK_UNREAD, resolveFeedSwipeDecision(true, false))
	}
	@Test fun rightSwipeAlwaysDeletesRegardlessOfReadState() {
		assertEquals(FeedSwipeDecision.DELETE, resolveFeedSwipeDecision(false, true))
		assertEquals(FeedSwipeDecision.DELETE, resolveFeedSwipeDecision(false, false))
	}
	@Test fun cancelledOrReversedSwipeNeverToggles() {
		assertFalse(shouldToggleFeedSwipe(0f, 200))
		assertFalse(shouldToggleFeedSwipe(-79f, 200))
		assertFalse(shouldToggleFeedSwipe(80f, 200))
		assertFalse(shouldToggleFeedSwipe(-80f, 0))
		assertTrue(shouldToggleFeedSwipe(-80f, 200))
	}
}
