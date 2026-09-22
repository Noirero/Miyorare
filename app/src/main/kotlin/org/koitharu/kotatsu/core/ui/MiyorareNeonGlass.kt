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

	// Keep the glass itself translucent, then carry the wallpaper accent through its tint and edge.
	// This reads brighter than an opaque dark panel while remaining inexpensive to render.
	val glassBase = ColorUtils.blendARGB(surfaceContainer, primary, 0.20f + 0.08f * strength)
	val strongBase = ColorUtils.blendARGB(surfaceContainerHigh, primary, 0.26f + 0.10f * strength)
	val railBase = ColorUtils.blendARGB(surfaceContainerHigh, primary, 0.30f + 0.10f * strength)
	val selectedAccent = ColorUtils.blendARGB(primary, borderHighlight, 0.20f)
	val selectedBase = ColorUtils.blendARGB(surfaceContainerHigh, selectedAccent, 0.62f + 0.10f * strength)
	val edge = ColorUtils.blendARGB(borderHighlight, primary, 0.64f)
	val innerEdge = ColorUtils.blendARGB(borderHighlight, Color.WHITE, 0.22f)
	val glowBase = ColorUtils.blendARGB(primary, accent, 0.12f)

	return MiyorareNeonGlassColors(
		// Keep the wallpaper legible through every Normal-Favourites glass surface.
		surface = ColorUtils.setAlphaComponent(glassBase, alpha(82, 122)),
		surfaceStrong = ColorUtils.setAlphaComponent(strongBase, alpha(94, 136)),
		railSurface = ColorUtils.setAlphaComponent(railBase, alpha(106, 152)),
		border = ColorUtils.setAlphaComponent(edge, alpha(110, 170)),
		borderStrong = ColorUtils.setAlphaComponent(edge, alpha(156, 232)),
		selectedSurface = ColorUtils.setAlphaComponent(selectedBase, alpha(188, 220)),
		selectedBorder = ColorUtils.setAlphaComponent(primary, alpha(226, 255)),
		innerHighlight = ColorUtils.setAlphaComponent(innerEdge, alpha(82, 132)),
		glow = ColorUtils.setAlphaComponent(glowBase, alpha(68, 130)),
		selectedGlow = ColorUtils.setAlphaComponent(selectedAccent, alpha(116, 192)),
		cardGlow = ColorUtils.setAlphaComponent(glowBase, alpha(34, 74)),
		content = onSurface,
		contentMuted = ColorUtils.setAlphaComponent(onSurfaceVariant, 232),
	)
}
