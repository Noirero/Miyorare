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
	if (hsv[1] > NEUTRAL_SATURATION_THRESHOLD) {
		hsv[1] = maxOf(hsv[1], saturationFloor).coerceIn(0f, 1f)
	}
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
 * An explicitly authored neutral Custom accent is an identity color, not a luminance seed.
 * Preserve it for identity-bearing chrome instead of turning black/gray/white luminous.
 */
internal fun resolveNeonGlassIdentityColor(
	authoredIdentity: Int?,
	luminousFallback: Int,
): Int {
	val identity = authoredIdentity ?: return luminousFallback
	val red = identity ushr 16 and 0xFF
	val green = identity ushr 8 and 0xFF
	val blue = identity and 0xFF
	val max = maxOf(red, green, blue)
	val min = minOf(red, green, blue)
	val saturation = if (max == 0) 0f else (max - min).toFloat() / max.toFloat()
	return if (saturation <= NEUTRAL_SATURATION_THRESHOLD) identity else luminousFallback
}

/** Exact authored neutral Custom identity, or null when normal luminous treatment should apply. */
internal fun MiyorareViewPalette.neutralCustomAccentIdentityOrNull(): Int? {
	val identity = customAccentIdentity ?: return null
	return identity.takeIf { resolveNeonGlassIdentityColor(identity, Int.MIN_VALUE) == identity }
}

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

fun MiyorareViewPalette.neonGlass(): MiyorareNeonGlassColors {
	val glowAlpha = Color.alpha(glow)
	val strength = when {
		glowAlpha >= 64 -> 1f
		glowAlpha >= 30 -> 0.72f
		else -> 0.46f
	}

	fun alpha(light: Int, full: Int): Int =
		(light + ((full - light) * strength)).toInt().coerceIn(0, 255)

	val luminousPrimary = resolveNeonGlassIdentityColor(customAccentIdentity, luminousThemeColor(primary))
	val luminousAccent = resolveNeonGlassIdentityColor(
		customAccentIdentity,
		normalFavouritesLuminousAccent(primary, secondary),
	)
	val luminousEdge = luminousAccent
	val authoredNeutralIdentity = customAccentIdentity == luminousEdge
	val hotEdge = if (authoredNeutralIdentity) luminousEdge else ColorUtils.blendARGB(luminousEdge, Color.WHITE, 0.42f)
	val selectedEdge = if (authoredNeutralIdentity) luminousEdge else ColorUtils.blendARGB(luminousEdge, Color.WHITE, 0.62f)

	val glassBase = ColorUtils.blendARGB(Color.BLACK, luminousPrimary, 0.30f)
	val strongBase = ColorUtils.blendARGB(Color.BLACK, luminousPrimary, 0.40f)
	val railBase = ColorUtils.blendARGB(Color.BLACK, luminousAccent, 0.34f)
	val selectedBase = ColorUtils.blendARGB(luminousAccent, Color.WHITE, 0.22f)

	return MiyorareNeonGlassColors(
		surface = ColorUtils.setAlphaComponent(glassBase, alpha(62, 84)),
		surfaceStrong = ColorUtils.setAlphaComponent(strongBase, alpha(82, 108)),
		railSurface = ColorUtils.setAlphaComponent(railBase, alpha(82, 106)),
		border = ColorUtils.setAlphaComponent(luminousEdge, alpha(164, 208)),
		borderStrong = ColorUtils.setAlphaComponent(hotEdge, alpha(228, 246)),
		selectedSurface = ColorUtils.setAlphaComponent(selectedBase, alpha(146, 176)),
		selectedBorder = ColorUtils.setAlphaComponent(selectedEdge, alpha(246, 255)),
		innerHighlight = ColorUtils.setAlphaComponent(selectedEdge, alpha(214, 250)),
		glow = ColorUtils.setAlphaComponent(luminousAccent, alpha(118, 176)),
		selectedGlow = ColorUtils.setAlphaComponent(luminousAccent, alpha(178, 228)),
		cardGlow = ColorUtils.setAlphaComponent(luminousEdge, alpha(66, 104)),
		content = Color.WHITE,
		contentMuted = ColorUtils.setAlphaComponent(Color.WHITE, 234),
	)
}

private const val NEUTRAL_SATURATION_THRESHOLD = 0.08f
