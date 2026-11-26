package org.koitharu.kotatsu.core.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionalSlideTest {
	@Test fun directionUsesVisibleOrderAndSupportsDirectJumps() {
		assertEquals(1, DirectionalSlide.direction(0, 4))
		assertEquals(-1, DirectionalSlide.direction(4, 0))
		assertEquals(-1, DirectionalSlide.direction(0, 4, rtl = true))
		assertEquals(1, DirectionalSlide.direction(4, 0, rtl = true))
	}

	@Test fun invalidOrSameDestinationDoesNotSlide() {
		assertEquals(0, DirectionalSlide.direction(-1, 2))
		assertEquals(0, DirectionalSlide.direction(2, -1))
		assertEquals(0, DirectionalSlide.direction(2, 2))
	}
}
