package org.koitharu.kotatsu.core.ui

/**
 * Geometry and visual calibration for Normal Favourites.
 *
 * Source of truth: the 864x1536 golden reference, treated as a 432x768 dp canonical viewport.
 * Values here describe proportional layout geometry; they are not device-specific screenshot hacks.
 */
object MiyorareFavouritesVisualSpec {
	const val CANONICAL_WIDTH_DP = 432f
	const val CANONICAL_HEIGHT_DP = 768f

	// Shared horizontal geometry.
	const val SCREEN_HORIZONTAL_MARGIN_DP = 18f

	// Top chrome / header.
	const val SEARCH_VISUAL_HEIGHT_DP = 42f
	const val SEARCH_SIDE_BUTTON_DP = 42f
	const val SEARCH_RADIUS_DP = 22f
	const val SEARCH_ROW_HORIZONTAL_MARGIN_DP = 18f
	const val SEARCH_CONTROL_GAP_DP = 14f
	const val SEARCH_ROW_BOTTOM_PADDING_DP = 12f
	const val HEADER_TOP_PADDING_DP = 6f
	const val HEADER_BOTTOM_PADDING_DP = 2f
	const val TITLE_HORIZONTAL_MARGIN_DP = 24f
	const val TITLE_TEXT_SP = 30f
	const val SUBTITLE_TEXT_SP = 14f
	const val TITLE_SUBTITLE_GAP_DP = 2f
	const val TITLE_HEART_DP = 22f
	const val TITLE_HEART_GAP_DP = 4f

	// Manga / Novel segmented control.
	const val CONTENT_TOGGLE_HEIGHT_DP = 44f
	const val CONTENT_TOGGLE_HORIZONTAL_MARGIN_DP = 20f
	const val CONTENT_TOGGLE_INSET_DP = 3f
	const val CONTENT_TOGGLE_TOP_GAP_DP = 8f
	const val CONTENT_TOGGLE_BOTTOM_GAP_DP = 4f
	const val CONTENT_TOGGLE_BUTTON_MIN_HEIGHT_DP = 38f
	const val CONTENT_TOGGLE_ICON_DP = 20f
	const val CONTENT_TOGGLE_ICON_GAP_DP = 6f
	const val CONTENT_TOGGLE_TEXT_SP = 14f

	// Category rail.
	const val CATEGORY_RAIL_HEIGHT_DP = 36f
	const val CATEGORY_RAIL_HORIZONTAL_MARGIN_DP = 14f
	const val CATEGORY_RAIL_TOP_GAP_DP = 4f
	const val CATEGORY_RAIL_BOTTOM_GAP_DP = 4f
	const val CATEGORY_RAIL_INSET_DP = 6f
	const val CATEGORY_TAB_MIN_HEIGHT_DP = 30f
	const val CATEGORY_TAB_HORIZONTAL_PADDING_DP = 7f
	const val CATEGORY_SELECTED_SURFACE_ALPHA_FACTOR = 0.34f

	// Quick actions.
	const val QUICK_FILTER_HEIGHT_DP = 32f
	const val QUICK_FILTER_RADIUS_DP = 16f
	const val QUICK_FILTER_ICON_DP = 16f
	const val QUICK_FILTER_HORIZONTAL_PADDING_DP = 9f
	const val QUICK_FILTER_TEXT_GAP_DP = 4f
	const val QUICK_FILTER_GAP_DP = 8f
	const val QUICK_FILTER_OUTER_PADDING_DP = 4f
	const val QUICK_FILTER_TEXT_SP = 13f
	const val QUICK_FILTER_TOP_PADDING_DP = 2f
	const val QUICK_FILTER_BOTTOM_PADDING_DP = 2f

	// Three-column manga grid. RecyclerView padding + item margin = 18dp outer margin.
	const val GRID_ITEM_MARGIN_DP = 4f
	const val GRID_ITEM_MARGIN_INCREASED_DP = 6f
	const val GRID_RECYCLER_HORIZONTAL_PADDING_DP =
		SCREEN_HORIZONTAL_MARGIN_DP - GRID_ITEM_MARGIN_DP
	const val MANGA_CARD_ASPECT_RATIO = 0.845f
	const val MANGA_CARD_RADIUS_DP = 14f
	const val MANGA_CARD_SCRIM_HEIGHT_DP = 54f
	const val MANGA_CARD_TITLE_TEXT_SP = 10.5f
	const val MANGA_CARD_TITLE_LINE_MULTIPLIER = 0.96f
	const val MANGA_CARD_TITLE_HORIZONTAL_MARGIN_DP = 8f
	const val MANGA_CARD_TITLE_BOTTOM_MARGIN_DP = 7f
	const val MANGA_CARD_BORDER_WIDTH_DP = 1f

	// Bottom navigation.
	const val BOTTOM_NAV_HORIZONTAL_MARGIN_DP = 18f
	const val BOTTOM_NAV_VERTICAL_MARGIN_DP = 8f
	const val BOTTOM_NAV_HEIGHT_DP = 68f
	const val BOTTOM_NAV_RADIUS_DP = 30f
	const val BOTTOM_NAV_ITEM_RADIUS_DP = 24f
	const val BOTTOM_NAV_SELECTED_HEIGHT_DP = 58f
	const val BOTTOM_NAV_DARK_THEME_SURFACE_MIX = 0.58f
	const val BOTTOM_NAV_BASE_ACCENT_MIX = 0.24f
	const val BOTTOM_NAV_SELECTED_ACCENT_MIX = 0.72f
	const val BOTTOM_NAV_CONTAINER_ALPHA = 164
	const val BOTTOM_NAV_SELECTED_ALPHA = 218
	const val BOTTOM_NAV_GRADIENT_START_ACCENT_MIX = 0.30f
	const val BOTTOM_NAV_GRADIENT_CENTER_ACCENT_MIX = 0.20f
	const val BOTTOM_NAV_GRADIENT_END_ACCENT_MIX = 0.28f
	const val BOTTOM_NAV_GRADIENT_START_ALPHA = 176
	const val BOTTOM_NAV_GRADIENT_CENTER_ALPHA = 150
	const val BOTTOM_NAV_GRADIENT_END_ALPHA = 168
	const val BOTTOM_NAV_INACTIVE_CONTENT_ALPHA = 0.84f
	const val BOTTOM_NAV_OUTER_GLOW_ALPHA = 0.28f
	const val BOTTOM_NAV_BORDER_ALPHA = 0.98f
	const val BOTTOM_NAV_SELECTED_BORDER_ALPHA = 1f
	const val BOTTOM_NAV_SELECTED_HALO_ALPHA = 0.32f
	const val BOTTOM_NAV_ICON_DP = 20f
	const val BOTTOM_NAV_SELECTED_ICON_DP = 22f
	const val BOTTOM_NAV_LABEL_TEXT_SP = 13f
	const val BOTTOM_NAV_LABEL_LINE_HEIGHT_SP = 16f
}
