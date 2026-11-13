package org.koitharu.kotatsu.details.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterTitlePanTimingTest {

	@Test fun panSpeedIsIndependentOfScreenDensity() {
		assertEquals(2_000, chapterTitleTravelMillis(72, 1f))
		assertEquals(2_000, chapterTitleTravelMillis(216, 3f))
	}

	@Test fun tinyOverflowStillGetsAReadableTransition() {
		assertEquals(300, chapterTitleTravelMillis(1, 3f))
	}
}
