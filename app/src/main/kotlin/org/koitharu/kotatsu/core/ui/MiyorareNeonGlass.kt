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

	// The approved reference reads as luminous tinted glass, not an opaque navy card.
	// Keep the foundation dark enough for text, but move it toward the active palette and let the
	// wallpaper show through. This remains fully static: no realtime blur or per-item shader work.
	val darkSurface = ColorUtils.blendARGB(Color.BLACK, surfaceContainer, 0.56f)
	val darkSurfaceHigh = ColorUtils.blendARGB(Color.BLACK, surfaceContainerHigh, 0.62f)
	val glassBase = ColorUtils.blendARGB(darkSurface, primary, 0.20f + 0.08f * strength)
	val strongBase = ColorUtils.blendARGB(darkSurfaceHigh, primary, 0.24f + 0.10f * strength)
	val railBase = ColorUtils.blendARGB(darkSurfaceHigh, primary, 0.28f + 0.10f * strength)
	val selectedAccent = ColorUtils.blendARGB(primary, borderHighlight, 0.58f)
	val selectedBase = ColorUtils.blendARGB(darkSurfaceHigh, selectedAccent, 0.70f + 0.10f * strength)
	val edge = ColorUtils.blendARGB(borderHighlight, primary, 0.36f)
	val innerEdge = ColorUtils.blendARGB(borderHighlight, Color.WHITE, 0.58f)
	val glowBase = ColorUtils.blendARGB(primary, accent, 0.30f)

	return MiyorareNeonGlassColors(
		// Lower alpha keeps authored wallpaper visible; brighter edges carry the glass definition.
		surface = ColorUtils.setAlphaComponent(glassBase, alpha(82, 112)),
		surfaceStrong = ColorUtils.setAlphaComponent(strongBase, alpha(98, 132)),
		railSurface = ColorUtils.setAlphaComponent(railBase, alpha(108, 144)),
		border = ColorUtils.setAlphaComponent(edge, alpha(168, 224)),
		borderStrong = ColorUtils.setAlphaComponent(edge, alpha(206, 248)),
		selectedSurface = ColorUtils.setAlphaComponent(selectedBase, alpha(178, 214)),
		selectedBorder = ColorUtils.setAlphaComponent(innerEdge, alpha(236, 255)),
		innerHighlight = ColorUtils.setAlphaComponent(innerEdge, alpha(132, 196)),
		glow = ColorUtils.setAlphaComponent(glowBase, alpha(78, 132)),
		selectedGlow = ColorUtils.setAlphaComponent(selectedAccent, alpha(152, 210)),
		cardGlow = ColorUtils.setAlphaComponent(glowBase, alpha(48, 82)),
		content = Color.WHITE,
		contentMuted = ColorUtils.setAlphaComponent(Color.WHITE, 232),
	)
}
