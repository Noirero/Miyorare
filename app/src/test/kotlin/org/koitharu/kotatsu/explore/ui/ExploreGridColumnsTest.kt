package org.koitharu.kotatsu.explore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreGridColumnsTest {

	@Test
	fun `wide landscape phone caps at seven columns across layout widths`() {
		assertEquals(7, exploreGridColumns(945, 135, true))
		assertEquals(7, exploreGridColumns(1080, 135, true))
		assertEquals(7, exploreGridColumns(1350, 135, true))
	}

	@Test
	fun `narrow landscape window adapts below the cap`() {
		assertEquals(1, exploreGridColumns(90, 135, true))
		assertEquals(4, exploreGridColumns(500, 135, true))
	}

	@Test
	fun `portrait and larger devices retain adaptive sizing`() {
		assertEquals(3, exploreGridColumns(405, 135, false))
		assertEquals(4, exploreGridColumns(406, 135, false))
		assertEquals(10, exploreGridColumns(1350, 135, false))
	}

	@Test
	fun `unmeasured width does not request a span change`() {
		assertNull(exploreGridColumns(0, 135, true))
		assertNull(exploreGridColumns(-1, 135, true))
		assertNull(exploreGridColumns(500, 0, true))
	}
}
