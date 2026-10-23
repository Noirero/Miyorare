package org.koitharu.kotatsu.core.ui

import org.koitharu.kotatsu.core.prefs.MiyorareAdaptivePalette
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

/**
 * Canonical Modern semantic-palette source boundary.
 *
 * Batch 1 intentionally has only MATCH_THEME, which delegates to the existing authored Modern
 * renderer without changing any colour math. Later palette sources extend this resolver rather than
 * overloading [MiyorareThemePreset] or duplicating palette derivation in screens.
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
	MiyorareModernPaletteSource.MATCH_THEME -> miyorareThemeColors(
		preset = preset,
		customAccent = customAccent,
		adaptivePalette = adaptivePalette,
		darkTheme = darkTheme,
		amoled = amoled,
		effectLevel = effectLevel,
	)
}
