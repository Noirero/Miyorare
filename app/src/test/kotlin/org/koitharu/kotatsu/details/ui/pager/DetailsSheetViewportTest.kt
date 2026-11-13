package org.koitharu.kotatsu.details.ui.pager

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsSheetViewportTest {

	@Test
	fun `half expanded sheet excludes the offscreen body from its scroll viewport`() {
		// The full-height sheet starts 380px below the top; only 620px are visible.
		assertEquals(500, detailsSheetVisibleBodyHeight(
			parentHeight = 1000,
			sheetTop = 380,
			headerHeight = 40,
			toolbarHeight = 80,
		))
	}

	@Test
	fun `expanded sheet makes the entire available body scrollable`() {
		assertEquals(880, detailsSheetVisibleBodyHeight(1000, 0, 40, 80))
	}

	@Test
	fun `collapsed sheet does not expose an offscreen page grid`() {
		assertEquals(0, detailsSheetVisibleBodyHeight(1000, 960, 40, 80))
	}

	@Test
	fun `viewport adapts when available window height changes`() {
		assertEquals(370, detailsSheetVisibleBodyHeight(800, 320, 40, 70))
	}
}
