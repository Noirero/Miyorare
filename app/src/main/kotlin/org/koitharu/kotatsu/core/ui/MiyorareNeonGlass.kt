package org.koitharu.kotatsu.core.ui

import android.graphics.Color
import androidx.core.graphics.ColorUtils


/**
 * Preserve the active theme hue while lifting saturation/value to the luminous range used by
 * the approved Favourites reference. This is pure color math; no blur, shader or animation.
 */
internal fun luminousThemeColor(
	color: Int,
	saturationFloor: Float = 0.74f,
	valueFloor: Float = 0.94f,
	valueBoost: Float = 0.12f,
): Int {
	val hsv = FloatArray(3)
	Color.colorToHSV(color, hsv)
	hsv[1] = maxOf(hsv[1], saturationFloor).coerceIn(0f, 1f)
	hsv[2] = maxOf(valueFloor, hsv[2] + valueBoost).coerceIn(0f, 1f)
	return Color.HSVToColor(Color.alpha(color), hsv)
}

internal fun luminousThemeBlend(
	primary: Int,
	accent: Int,
	accentMix: Float,
): Int = luminousThemeColor(
	ColorUtils.blendARGB(primary, accent, accentMix.coerceIn(0f, 1f)),
)


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

	// Surface and light are deliberately separated. Surface stays dark/translucent while the
	// luminous family keeps the active theme hue but restores saturation/value lost in Material
	// container blending. This is what lets blue/pink/green/etc. stay adaptive without becoming gray.
	val luminousPrimary = luminousThemeColor(primary)
	val luminousAccent = luminousThemeColor(
		secondary,
		saturationFloor = 0.86f,
		valueFloor = 0.98f,
		valueBoost = 0.14f,
	)
	val luminousEdge = ColorUtils.blendARGB(luminousPrimary, luminousAccent, 0.78f)
	val selectedEdge = ColorUtils.blendARGB(luminousEdge, Color.WHITE, 0.20f)

	val glassBase = ColorUtils.blendARGB(Color.BLACK, luminousPrimary, 0.34f)
	val strongBase = ColorUtils.blendARGB(Color.BLACK, luminousPrimary, 0.42f)
	val railBase = ColorUtils.blendARGB(Color.BLACK, luminousAccent, 0.38f)
	val selectedBase = ColorUtils.blendARGB(luminousAccent, Color.WHITE, 0.18f)

	return MiyorareNeonGlassColors(
		// Full mode targets the supplied golden reference. Lower effect levels reduce alpha/halo,
		// not saturation, so the palette stays alive instead of returning to muddy navy.
		surface = ColorUtils.setAlphaComponent(glassBase, alpha(68, 96)),
		surfaceStrong = ColorUtils.setAlphaComponent(strongBase, alpha(86, 118)),
		railSurface = ColorUtils.setAlphaComponent(railBase, alpha(98, 130)),
		border = ColorUtils.setAlphaComponent(luminousEdge, alpha(188, 236)),
		borderStrong = ColorUtils.setAlphaComponent(luminousEdge, alpha(226, 255)),
		selectedSurface = ColorUtils.setAlphaComponent(selectedBase, alpha(180, 210)),
		selectedBorder = ColorUtils.setAlphaComponent(selectedEdge, alpha(246, 255)),
		innerHighlight = ColorUtils.setAlphaComponent(selectedEdge, alpha(150, 204)),
		glow = ColorUtils.setAlphaComponent(luminousAccent, alpha(112, 178)),
		selectedGlow = ColorUtils.setAlphaComponent(luminousAccent, alpha(186, 236)),
		cardGlow = ColorUtils.setAlphaComponent(luminousEdge, alpha(80, 128)),
		content = Color.WHITE,
		contentMuted = ColorUtils.setAlphaComponent(Color.WHITE, 234),
	)
}
