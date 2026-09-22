package org.koitharu.kotatsu.core.ui

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * Lightweight, theme-driven glass tokens used by Normal Favourites.
 *
 * This deliberately uses only tinted translucent fills, alpha borders and static glow colors.
 * It does not allocate blur effects, shaders or animations per item, keeping large library grids cheap.
 */
data class MiyorareNeonGlassColors(
	val surface: Int,
	val surfaceStrong: Int,
	val border: Int,
	val borderStrong: Int,
	val selectedSurface: Int,
	val selectedBorder: Int,
	val glow: Int,
	val content: Int,
	val contentMuted: Int,
)

/**
 * Derive a consistent glass family from the active Miyorare palette.
 *
 * The palette's glow alpha already reflects the user's Light / Balanced / Full visual-effect level,
 * so custom Views that only have a [MiyorareViewPalette] stay in sync without reading preferences again.
 */
fun MiyorareViewPalette.neonGlass(): MiyorareNeonGlassColors {
	val glowAlpha = Color.alpha(glow)
	val strength = when {
		glowAlpha >= 64 -> 1f
		glowAlpha >= 30 -> 0.72f
		else -> 0.46f
	}

	fun alpha(light: Int, full: Int): Int =
		(light + ((full - light) * strength)).toInt().coerceIn(0, 255)

	val glassBase = ColorUtils.blendARGB(surfaceContainer, primary, 0.08f + 0.04f * strength)
	val strongBase = ColorUtils.blendARGB(surfaceContainerHigh, primary, 0.11f + 0.06f * strength)
	val selectedBase = ColorUtils.blendARGB(selectedSurface, primary, 0.12f + 0.10f * strength)
	val edge = ColorUtils.blendARGB(borderHighlight, primary, 0.30f)
	val glowBase = ColorUtils.blendARGB(primary, accent, 0.22f)

	return MiyorareNeonGlassColors(
		surface = ColorUtils.setAlphaComponent(glassBase, alpha(150, 194)),
		surfaceStrong = ColorUtils.setAlphaComponent(strongBase, alpha(176, 214)),
		border = ColorUtils.setAlphaComponent(edge, alpha(76, 138)),
		borderStrong = ColorUtils.setAlphaComponent(edge, alpha(116, 188)),
		selectedSurface = ColorUtils.setAlphaComponent(selectedBase, alpha(205, 238)),
		selectedBorder = ColorUtils.setAlphaComponent(primary, alpha(150, 222)),
		glow = ColorUtils.setAlphaComponent(glowBase, alpha(32, 78)),
		content = onSurface,
		contentMuted = ColorUtils.setAlphaComponent(onSurfaceVariant, 224),
	)
}
