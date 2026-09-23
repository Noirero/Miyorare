package org.koitharu.kotatsu.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiyorareFavouritesVisualSpecTest {

	@Test
	fun canonicalThreeColumnGridMatchesGoldenReference() {
		val outerMargin = MiyorareFavouritesVisualSpec.SCREEN_HORIZONTAL_MARGIN_DP
		val gap = MiyorareFavouritesVisualSpec.GRID_ITEM_MARGIN_DP * 2f
		val cardWidth = (
			MiyorareFavouritesVisualSpec.CANONICAL_WIDTH_DP -
				outerMargin * 2f -
				gap * 2f
			) / 3f
		val cardHeight = cardWidth / MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO

		assertEquals(8f, gap, 0.001f)
		assertEquals(126.6667f, cardWidth, 0.05f)
		assertEquals(149.9f, cardHeight, 0.2f)
	}

	@Test
	fun recyclerPaddingAndItemMarginResolveToGoldenOuterMargin() {
		assertEquals(
			MiyorareFavouritesVisualSpec.SCREEN_HORIZONTAL_MARGIN_DP,
			MiyorareFavouritesVisualSpec.GRID_RECYCLER_HORIZONTAL_PADDING_DP +
				MiyorareFavouritesVisualSpec.GRID_ITEM_MARGIN_DP,
			0.001f,
		)
	}

	@Test
	fun verticalControlsStayWithinCanonicalReferenceBands() {
		assertTrue(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_HEIGHT_DP in 42f..46f)
		assertTrue(MiyorareFavouritesVisualSpec.CATEGORY_RAIL_HEIGHT_DP in 34f..38f)
		assertTrue(MiyorareFavouritesVisualSpec.QUICK_FILTER_HEIGHT_DP in 30f..34f)
		assertTrue(MiyorareFavouritesVisualSpec.BOTTOM_NAV_HEIGHT_DP in 65f..70f)
	}
}
