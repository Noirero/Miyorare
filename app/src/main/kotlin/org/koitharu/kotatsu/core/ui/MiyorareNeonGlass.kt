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
 * Canonical Normal-Favourites light color. For the default Miyorare seeds, 25% secondary lands
 * around 221 degrees (blue-cyan), matching the approved reference without drifting into teal.
 */
internal fun normalFavouritesLuminousAccent(
	primary: Int,
	secondary: Int,
): Int = luminousThemeColor(
	ColorUtils.blendARGB(primary, secondary, 0.25f),
	saturationFloor = 0.86f,
	valueFloor = 1.00f,
	valueBoost = 0.14f,
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

	// Exclusive Themes arrive here as fully-resolved Favourites roles. This helper may adjust
	// luminance/alpha for glass readability, but it must never infer rank identity or rebuild a palette.
	val authored = exclusiveTheme?.favourites
	val luminousPrimary = luminousThemeColor(primary)
	val authoredBorder = authored?.borderStops.orEmpty()
	val authoredSelected = authored?.selectedStops.orEmpty()
	val authoredGlow = authored?.glowStops.orEmpty()
	val luminousAccent = authored?.interactiveText ?: normalFavouritesLuminousAccent(primary, secondary)
	val luminousEdge = authoredBorder.firstOrNull() ?: luminousAccent
	val hotEdge = authoredBorder.getOrNull(authoredBorder.size / 2)
		?: ColorUtils.blendARGB(luminousEdge, Color.WHITE, 0.42f)
	val selectedEdge = authoredBorder.lastOrNull()
		?: authoredSelected.lastOrNull()
		?: ColorUtils.blendARGB(luminousEdge, Color.WHITE, 0.62f)

	// Keep glass dark enough for wallpaper contrast, but let more theme light live inside the
	// material. Component identity already comes from the Theme Engine above.
	val glassBase = ColorUtils.blendARGB(Color.BLACK, luminousPrimary, 0.30f)
	val strongBase = ColorUtils.blendARGB(Color.BLACK, luminousPrimary, 0.40f)
	val railBase = ColorUtils.blendARGB(Color.BLACK, luminousAccent, 0.34f)
	val selectedBase = ColorUtils.blendARGB(luminousAccent, Color.WHITE, 0.22f)

	val signatureGlow = authoredGlow.firstOrNull() ?: luminousAccent
	val signatureSelectedGlow = authoredGlow.lastOrNull() ?: luminousAccent
	val signatureCardGlow = authoredGlow.getOrNull(1) ?: luminousEdge
	val authoredContent = authored?.content ?: Color.WHITE
	val authoredMutedContent = authored?.mutedContent ?: ColorUtils.setAlphaComponent(Color.WHITE, 234)

	return MiyorareNeonGlassColors(
		// Full mode targets the supplied golden reference. Lower effect levels reduce alpha/halo,
		// not saturation, so the palette stays alive instead of returning to muddy navy.
		surface = ColorUtils.setAlphaComponent(glassBase, alpha(62, 84)),
		surfaceStrong = ColorUtils.setAlphaComponent(strongBase, alpha(82, 108)),
		railSurface = ColorUtils.setAlphaComponent(railBase, alpha(82, 106)),
		border = ColorUtils.setAlphaComponent(luminousEdge, alpha(164, 208)),
		borderStrong = ColorUtils.setAlphaComponent(hotEdge, alpha(228, 246)),
		selectedSurface = ColorUtils.setAlphaComponent(selectedBase, alpha(146, 176)),
		selectedBorder = ColorUtils.setAlphaComponent(selectedEdge, alpha(246, 255)),
		innerHighlight = ColorUtils.setAlphaComponent(selectedEdge, alpha(214, 250)),
		glow = ColorUtils.setAlphaComponent(signatureGlow, alpha(118, 176)),
		selectedGlow = ColorUtils.setAlphaComponent(signatureSelectedGlow, alpha(178, 228)),
		cardGlow = ColorUtils.setAlphaComponent(signatureCardGlow, alpha(66, 104)),
		content = authoredContent,
		contentMuted = authoredMutedContent,
	)
}
