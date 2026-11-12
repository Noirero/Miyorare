package org.koitharu.kotatsu.list.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSpanResolverTransientWidthTest {

	@Test
	fun `ignore intermediate pager widths even when multiple cells fit`() {
		// The old one-cell threshold allowed this width through and recalculated 3+ columns as 2.
		assertFalse(isUsableGridWidth(width = 480, pagerWidth = 1024, cellWidth = 160f))
		assertFalse(isUsableGridWidth(width = 900, pagerWidth = 1024, cellWidth = 160f))
	}

	@Test
	fun `accept stable full pager width and subsequent legitimate resize`() {
		assertTrue(isUsableGridWidth(width = 1024, pagerWidth = 1024, cellWidth = 160f))
		assertTrue(isUsableGridWidth(width = 1250, pagerWidth = 1024, cellWidth = 160f))
		assertTrue(isUsableGridWidth(width = 430, pagerWidth = 430, cellWidth = 160f))
	}

	@Test
	fun `standalone lists preserve the original one-cell minimum`() {
		assertTrue(isUsableGridWidth(width = 480, pagerWidth = 0, cellWidth = 160f))
		assertFalse(isUsableGridWidth(width = 120, pagerWidth = 0, cellWidth = 160f))
	}
}
