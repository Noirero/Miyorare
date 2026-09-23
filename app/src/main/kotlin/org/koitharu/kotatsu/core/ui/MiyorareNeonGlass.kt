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
	val railSurface: Int,
	val border: Int,
	val borderStrong: Int,
	val selectedSurface: Int,
	val selectedBorder: Int,
	val innerHighlight: Int,
	val glow: Int,
	val selectedGlow: Int,
	val cardGlow: Int,
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

	// The golden Favourites reference uses dark navy glass with bright, thin adaptive edges.
	// Darkening the base (instead of the wallpaper) preserves artwork while preventing cover/detail
	// content behind controls from washing out labels.
	val darkSurface = ColorUtils.blendARGB(Color.BLACK, surfaceContainer, 0.34f)
	val darkSurfaceHigh = ColorUtils.blendARGB(Color.BLACK, surfaceContainerHigh, 0.40f)
	val glassBase = ColorUtils.blendARGB(darkSurface, primary, 0.12f + 0.06f * strength)
	val strongBase = ColorUtils.blendARGB(darkSurfaceHigh, primary, 0.16f + 0.07f * strength)
	val railBase = ColorUtils.blendARGB(darkSurfaceHigh, primary, 0.18f + 0.08f * strength)
	val selectedAccent = ColorUtils.blendARGB(primary, borderHighlight, 0.36f)
	val selectedBase = ColorUtils.blendARGB(darkSurfaceHigh, selectedAccent, 0.54f + 0.08f * strength)
	val edge = ColorUtils.blendARGB(borderHighlight, primary, 0.62f)
	val innerEdge = ColorUtils.blendARGB(borderHighlight, Color.WHITE, 0.42f)
	val glowBase = ColorUtils.blendARGB(primary, accent, 0.14f)

	return MiyorareNeonGlassColors(
		// More opacity belongs to the glass surfaces, not to the wallpaper itself.
		surface = ColorUtils.setAlphaComponent(glassBase, alpha(118, 150)),
		surfaceStrong = ColorUtils.setAlphaComponent(strongBase, alpha(134, 168)),
		railSurface = ColorUtils.setAlphaComponent(railBase, alpha(144, 180)),
		border = ColorUtils.setAlphaComponent(edge, alpha(148, 210)),
		borderStrong = ColorUtils.setAlphaComponent(edge, alpha(198, 250)),
		selectedSurface = ColorUtils.setAlphaComponent(selectedBase, alpha(202, 228)),
		selectedBorder = ColorUtils.setAlphaComponent(primary, alpha(242, 255)),
		innerHighlight = ColorUtils.setAlphaComponent(innerEdge, alpha(118, 176)),
		glow = ColorUtils.setAlphaComponent(glowBase, alpha(72, 126)),
		selectedGlow = ColorUtils.setAlphaComponent(selectedAccent, alpha(152, 218)),
		cardGlow = ColorUtils.setAlphaComponent(glowBase, alpha(44, 88)),
		// Normal Favourites always renders these controls over a deliberately dark glass foundation.
		content = Color.WHITE,
		contentMuted = ColorUtils.setAlphaComponent(Color.WHITE, 222),
	)
}
