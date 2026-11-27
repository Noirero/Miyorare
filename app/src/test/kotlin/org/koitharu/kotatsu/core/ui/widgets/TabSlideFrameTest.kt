package org.koitharu.kotatsu.core.ui.widgets

import org.junit.Assert.*
import org.junit.Test

class TabSlideFrameTest {
	private val centers = listOf(1 to 20f, 2 to 80f, 3 to 160f)

	@Test fun progressIsContinuousAcrossUnequalSlotsAndReversal() {
		assertEquals(TabSlideFrame(1, 2, 0.25f), TabSlideFrame.at(centers, 35f))
		assertEquals(TabSlideFrame(1, 2, 0.75f), TabSlideFrame.at(centers, 65f))
		assertEquals(TabSlideFrame(1, 2, 0.25f), TabSlideFrame.at(centers, 35f))
		assertEquals(TabSlideFrame.single(2), TabSlideFrame.at(centers, 80f))
		assertEquals(TabSlideFrame(2, 3, 0.5f), TabSlideFrame.at(centers, 120f))
	}

	@Test fun physicalOrderClampsEndsAndSupportsReversedMenuOrder() {
		assertEquals(TabSlideFrame.single(1), TabSlideFrame.at(centers, -10f))
		assertEquals(TabSlideFrame.single(3), TabSlideFrame.at(centers, 200f))
		assertEquals(TabSlideFrame(2, 3, 0.5f), TabSlideFrame.at(centers.reversed(), 120f))
		assertNull(TabSlideFrame.at(emptyList(), 0f))
	}
}
