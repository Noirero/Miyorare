package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.koitharu.kotatsu.core.prefs.MiyorareAdaptivePalette
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

/**
 * Canonical Modern semantic-palette source boundary.
 *
 * MATCH_THEME keeps the authored Modern palette. A Classic-derived source may replace only the
 * semantic [ColorScheme]; Modern artwork, gradients, glow, geometry and other decorative identity
 * remain owned by the authored [MiyorareThemePreset] visual palette.
 */
fun resolveMiyorareThemeColors(
	paletteSource: MiyorareModernPaletteSource,
	preset: MiyorareThemePreset,
	customAccent: String,
	adaptivePalette: MiyorareAdaptivePalette? = null,
	darkTheme: Boolean,
	amoled: Boolean,
	effectLevel: VisualEffectLevel,
	classicDerivedColorScheme: ColorScheme? = null,
): MiyorareThemeColors {
	val authored = miyorareThemeColors(
		preset = preset,
		customAccent = customAccent,
		adaptivePalette = adaptivePalette,
		darkTheme = darkTheme,
		amoled = amoled,
		effectLevel = effectLevel,
	)
	if (!paletteSource.isClassicDerived || classicDerivedColorScheme == null) {
		return authored.withCompleteSemanticRoles(
			darkTheme = darkTheme,
			amoled = amoled,
		)
	}
	return authored.copy(
		colorScheme = classicDerivedColorScheme.withModernAmoledFoundation(
			darkTheme = darkTheme,
			amoled = amoled,
		),
	)
}

/** Keep the same foundational true-black hierarchy Modern already uses in AMOLED mode. */
private fun ColorScheme.withModernAmoledFoundation(
	darkTheme: Boolean,
	amoled: Boolean,
): ColorScheme {
	if (!darkTheme || !amoled) return this
	return copy(
		background = Color.Black,
		surface = Color.Black,
		surfaceDim = Color.Black,
		surfaceContainerLowest = Color.Black,
		surfaceContainerLow = Color.Black,
		surfaceContainer = Color.Black,
		surfaceContainerHigh = Color(0xFF080808),
		surfaceContainerHighest = Color(0xFF101010),
		surfaceBright = Color(0xFF181818),
	)
}
