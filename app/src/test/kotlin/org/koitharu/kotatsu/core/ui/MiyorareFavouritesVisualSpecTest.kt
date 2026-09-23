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
	fun topChromeAndToggleResolveToGoldenWidths() {
		val searchWidth =
			MiyorareFavouritesVisualSpec.CANONICAL_WIDTH_DP -
				MiyorareFavouritesVisualSpec.SEARCH_ROW_HORIZONTAL_MARGIN_DP * 2f -
				MiyorareFavouritesVisualSpec.SEARCH_SIDE_BUTTON_DP * 2f -
				MiyorareFavouritesVisualSpec.SEARCH_CONTROL_GAP_DP * 2f
		val toggleWidth =
			MiyorareFavouritesVisualSpec.CANONICAL_WIDTH_DP -
				MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_HORIZONTAL_MARGIN_DP * 2f

		assertEquals(284f, searchWidth, 0.001f)
		assertEquals(384f, toggleWidth, 0.001f)
		assertEquals(24f, MiyorareFavouritesVisualSpec.TITLE_HORIZONTAL_MARGIN_DP, 0.001f)
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
	fun quickActionsResolveToSameWidthAsCardsAndCompactVerticalBand() {
		val recyclerInnerWidth =
			MiyorareFavouritesVisualSpec.CANONICAL_WIDTH_DP -
				MiyorareFavouritesVisualSpec.GRID_RECYCLER_HORIZONTAL_PADDING_DP * 2f
		val quickActionWidth = (
			recyclerInnerWidth -
				MiyorareFavouritesVisualSpec.QUICK_FILTER_OUTER_PADDING_DP * 2f -
				MiyorareFavouritesVisualSpec.QUICK_FILTER_GAP_DP * 2f
			) / 3f
		val quickRowHeight =
			MiyorareFavouritesVisualSpec.QUICK_FILTER_TOP_PADDING_DP +
				MiyorareFavouritesVisualSpec.QUICK_FILTER_HEIGHT_DP +
				MiyorareFavouritesVisualSpec.QUICK_FILTER_BOTTOM_PADDING_DP

		assertEquals(126.6667f, quickActionWidth, 0.05f)
		assertEquals(36f, quickRowHeight, 0.001f)
	}

	@Test
	fun verticalControlsStayWithinCanonicalReferenceBands() {
		assertTrue(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_HEIGHT_DP in 42f..46f)
		assertTrue(MiyorareFavouritesVisualSpec.CATEGORY_RAIL_HEIGHT_DP in 34f..38f)
		assertTrue(MiyorareFavouritesVisualSpec.QUICK_FILTER_HEIGHT_DP in 30f..34f)
		assertTrue(MiyorareFavouritesVisualSpec.BOTTOM_NAV_HEIGHT_DP in 65f..70f)
	}
}
