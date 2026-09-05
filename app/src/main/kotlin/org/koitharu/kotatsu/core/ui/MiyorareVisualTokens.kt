package org.koitharu.kotatsu.core.ui

/**
 * Shared dimensions, motion timings and finite visual-strength constants for the Miyorare visual
 * language. Keeping these values centralized prevents individual screens from drifting into
 * unrelated radii, spacing, gradients or glow intensity.
 */
object MiyorareVisualTokens {
	// Radius values mirror dimens_miyorare_modern.xml.
	const val RADIUS_SMALL_DP = 12f
	const val RADIUS_CONTROL_DP = 18f
	const val RADIUS_CARD_DP = 20f
	const val RADIUS_SURFACE_DP = 24f
	const val RADIUS_DIALOG_DP = 32f
	const val RADIUS_COVER_DP = 14f

	// Canonical layout spacing. Micro is intentionally outside the layout scale.
	const val SPACING_MICRO_DP = 2f
	const val SPACING_XS_DP = 4f
	const val SPACING_S_DP = 8f
	const val SPACING_M_DP = 12f
	const val SPACING_L_DP = 16f
	const val SPACING_XL_DP = 20f
	const val SPACING_XXL_DP = 24f
	const val SPACING_XXXL_DP = 32f

	const val SPACING_COMPACT_DP = SPACING_S_DP
	const val SPACING_STANDARD_DP = SPACING_L_DP
	const val SPACING_SECTION_DP = SPACING_XXL_DP

	// Static decoration strengths. Balanced is intentionally expressive enough to read as the
	// reference Miyorare look, while Light stays restrained and Full adds depth without blur loops.
	const val GRADIENT_STRENGTH_LIGHT = 0.07f
	const val GRADIENT_STRENGTH_BALANCED = 0.24f
	const val GRADIENT_STRENGTH_FULL = 0.38f
	const val BORDER_ALPHA_LIGHT = 0.16f
	const val BORDER_ALPHA_BALANCED = 0.34f
	const val BORDER_ALPHA_FULL = 0.52f
	const val GLOW_ALPHA_LIGHT = 0.04f
	const val GLOW_ALPHA_BALANCED = 0.16f
	const val GLOW_ALPHA_FULL = 0.28f
	const val ACTIVE_GRADIENT_MIX = 0.42f
	const val SURFACE_GRADIENT_MIX = 0.28f
	const val ICON_GRADIENT_MIX = 0.34f

	// Finite, inexpensive motion values mirror integers_miyorare_modern.xml.
	const val MOTION_NONE_MS = 0
	const val MOTION_QUICK_MS = 120
	const val MOTION_STANDARD_MS = 180
	const val MOTION_EMPHASIZED_MS = 240
}
