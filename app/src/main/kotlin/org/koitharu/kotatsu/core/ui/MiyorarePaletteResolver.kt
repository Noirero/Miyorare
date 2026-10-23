package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.koitharu.kotatsu.core.prefs.MiyorareAdaptivePalette
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

/**
 * Canonical Modern palette-source boundary.
 *
 * MATCH_THEME keeps the authored Modern palette exactly. A Classic-derived source keeps Modern
 * artwork, geometry and effect mechanics, while both Material semantics and colour-bearing visual
 * tokens (gradients, glow, borders, selected surfaces and icon treatments) follow the resolved
 * Classic light/dark palette.
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
	val resolvedScheme = classicDerivedColorScheme.withModernAmoledFoundation(
		darkTheme = darkTheme,
		amoled = amoled,
	)
	return authored.copy(
		colorScheme = resolvedScheme,
		visualPalette = resolvedScheme.toModernVisualPalette(
			authored = authored.visualPalette,
			darkTheme = darkTheme,
			amoled = amoled,
		),
	)
}

/**
 * Re-colour Modern's existing visual mechanics from the active semantic palette.
 *
 * The authored palette remains the authority for non-colour identity and effect strength. This
 * function only replaces colour-bearing tokens so Classic-derived palettes do not leave Modern
 * surfaces, active controls, glow and gradients painted with the previous authored hue.
 */
private fun ColorScheme.toModernVisualPalette(
	authored: MiyorareVisualPalette,
	darkTheme: Boolean,
	amoled: Boolean,
): MiyorareVisualPalette {
	val primaryColor = primary
	val secondaryColor = secondary
	val accentColor = tertiary
	val selectedSurface = primaryContainer
	val chipColor = secondaryContainer
	val borderColor = outline
	val gradientStrength = authored.gradientStrength
	val useAmoled = darkTheme && amoled

	val backgroundGradientStart: Color
	val backgroundGradientMiddle: Color
	val backgroundGradientEnd: Color
	if (useAmoled) {
		backgroundGradientStart = Color.Black
		backgroundGradientMiddle = Color.Black
		backgroundGradientEnd = Color.Black
	} else {
		backgroundGradientStart = lerp(background, primaryColor, gradientStrength * 0.22f)
		backgroundGradientMiddle = lerp(background, secondaryColor, gradientStrength * 0.12f)
		backgroundGradientEnd = lerp(background, accentColor, gradientStrength * 0.18f)
	}

	val surfaceGradientStart = lerp(
		surfaceContainer,
		primaryColor,
		gradientStrength * MiyorareVisualTokens.SURFACE_GRADIENT_MIX,
	)
	val surfaceGradientMiddle = lerp(
		surfaceContainerHigh,
		accentColor,
		gradientStrength * 0.10f,
	)
	val surfaceGradientEnd = lerp(
		surfaceContainerHigh,
		secondaryColor,
		gradientStrength * 0.32f,
	)
	val iconGradientStart = lerp(
		selectedSurface,
		primaryColor,
		MiyorareVisualTokens.ICON_GRADIENT_MIX + gradientStrength * 0.22f,
	)
	val iconGradientEnd = lerp(
		selectedSurface,
		secondaryColor,
		0.10f + gradientStrength * 0.34f,
	)
	val accentGradientMiddle = lerp(primaryColor, secondaryColor, 0.34f + gradientStrength * 0.32f)
	val accentGradientEnd = lerp(secondaryColor, accentColor, 0.12f + gradientStrength * 0.40f)
	val activeGradientStart = lerp(
		primaryColor,
		secondaryColor,
		gradientStrength * MiyorareVisualTokens.ACTIVE_GRADIENT_MIX,
	)
	val activeGradientEnd = lerp(secondaryColor, accentColor, 0.08f + gradientStrength * 0.44f)

	return authored.copy(
		primary = primaryColor,
		secondary = secondaryColor,
		accent = accentColor,
		selectedSurface = selectedSurface,
		border = borderColor,
		borderHighlight = lerp(borderColor, secondaryColor, 0.22f + gradientStrength * 0.26f)
			.copy(alpha = authored.borderHighlight.alpha),
		glow = lerp(primaryColor, secondaryColor, 0.34f).copy(alpha = authored.glow.alpha),
		chip = chipColor,
		button = primaryColor,
		onButton = onPrimary,
		backgroundGradientStart = backgroundGradientStart,
		backgroundGradientMiddle = backgroundGradientMiddle,
		backgroundGradientEnd = backgroundGradientEnd,
		surfaceGradientStart = surfaceGradientStart,
		surfaceGradientMiddle = surfaceGradientMiddle,
		surfaceGradientEnd = surfaceGradientEnd,
		iconGradientStart = iconGradientStart,
		iconGradientEnd = iconGradientEnd,
		accentGradientStart = primaryColor,
		accentGradientMiddle = accentGradientMiddle,
		accentGradientEnd = accentGradientEnd,
		activeGradientStart = activeGradientStart,
		activeGradientEnd = activeGradientEnd,
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
