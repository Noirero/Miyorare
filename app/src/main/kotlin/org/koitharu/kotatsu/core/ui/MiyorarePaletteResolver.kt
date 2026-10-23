package org.koitharu.kotatsu.core.ui

import org.koitharu.kotatsu.core.prefs.MiyorareAdaptivePalette
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

/**
 * Canonical Modern semantic-palette source boundary.
 *
 * MATCH_THEME delegates authored identity to the existing Modern renderer, then completes semantic
 * Material roles without changing the authored core palette. Later palette sources extend this
 * resolver rather than overloading [MiyorareThemePreset] or duplicating derivation in screens.
 */
fun resolveMiyorareThemeColors(
	paletteSource: MiyorareModernPaletteSource,
	preset: MiyorareThemePreset,
	customAccent: String,
	adaptivePalette: MiyorareAdaptivePalette? = null,
	darkTheme: Boolean,
	amoled: Boolean,
	effectLevel: VisualEffectLevel,
): MiyorareThemeColors = when (paletteSource) {
	MiyorareModernPaletteSource.MATCH_THEME -> {
		val authored = miyorareThemeColors(
			preset = preset,
			customAccent = customAccent,
			adaptivePalette = adaptivePalette,
			darkTheme = darkTheme,
			amoled = amoled,
			effectLevel = effectLevel,
		)
		val fixedReference = if (darkTheme) {
			miyorareThemeColors(
				preset = preset,
				customAccent = customAccent,
				adaptivePalette = adaptivePalette,
				darkTheme = false,
				amoled = false,
				effectLevel = effectLevel,
			).colorScheme
		} else {
			authored.colorScheme
		}
		authored.withCompleteSemanticRoles(
			darkTheme = darkTheme,
			amoled = amoled,
			fixedReference = fixedReference,
		)
	}
}
