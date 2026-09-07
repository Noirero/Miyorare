package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.ui.graphics.toArgb
import androidx.preference.PreferenceManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences

/**
 * Android View bridge for the same semantic Modern palette used by Compose.
 *
 * The Modern XML overlay intentionally remains a safe static fallback. View-based screens that
 * opt into this bridge receive the currently selected Miyorare preset (including Custom) without
 * duplicating any palette math or touching feature/data behavior.
 */
data class MiyorareViewPalette(
	val resources: Resources,
	val preset: MiyorareThemePreset,
	val background: Int,
	val surface: Int,
	val surfaceContainer: Int,
	val surfaceContainerHigh: Int,
	val primary: Int,
	val onPrimary: Int,
	val primaryContainer: Int,
	val onPrimaryContainer: Int,
	val secondary: Int,
	val accent: Int,
	val onSurface: Int,
	val onSurfaceVariant: Int,
	val outline: Int,
	val outlineVariant: Int,
	val selectedSurface: Int,
	val borderHighlight: Int,
	val glow: Int,
	val button: Int,
	val onButton: Int,
	val backgroundGradientStart: Int,
	val backgroundGradientMiddle: Int,
	val backgroundGradientEnd: Int,
	val surfaceGradientStart: Int,
	val surfaceGradientMiddle: Int,
	val surfaceGradientEnd: Int,
	val activeGradientStart: Int,
	val activeGradientEnd: Int,
)

fun Context.miyorareViewPalette(
	settings: AppSettings,
	effectLevel: VisualEffectLevel,
): MiyorareViewPalette = buildMiyorareViewPalette(
	preset = settings.miyorareThemePreset,
	customAccent = settings.miyorareCustomAccent,
	amoled = settings.isAmoledTheme,
	effectLevel = effectLevel,
)

/**
 * Preference-backed palette entry point for custom Views that cannot receive Hilt dependencies.
 * Returns null for Classic so Modern-only header shells stay completely isolated from Classic.
 */
fun Context.miyorareViewPaletteFromPreferences(): MiyorareViewPalette? {
	val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
	val designStyle = prefs.getString(MiyorareAppearance.KEY_DESIGN_STYLE, null)
		?.let { value -> MiyorareDesignStyle.entries.firstOrNull { it.name == value } }
		?: MiyorareDesignStyle.CLASSIC
	if (designStyle != MiyorareDesignStyle.MODERN) return null

	val preset = prefs.getString(MiyorareAppearance.KEY_THEME_PRESET, null)
		?.let { value -> MiyorareThemePreset.entries.firstOrNull { it.name == value } }
		?: MiyorareThemePreset.MIYORARE
	val customAccent = prefs.getString(
		MiyorareAppearance.KEY_CUSTOM_ACCENT,
		MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
	)?.let { value -> MiyorareAppearance.normalizeAccent(value) }
		?: MiyorareAppearance.DEFAULT_CUSTOM_ACCENT
	val effectLevel = prefs.getString(VisualEffectPreferences.KEY_LEVEL, null)
		?.let { value -> VisualEffectLevel.entries.firstOrNull { it.name == value } }
		?: VisualEffectLevel.BALANCED

	return buildMiyorareViewPalette(
		preset = preset,
		customAccent = customAccent,
		amoled = prefs.getBoolean(AppSettings.KEY_THEME_AMOLED, false),
		effectLevel = effectLevel,
	)
}

private fun Context.buildMiyorareViewPalette(
	preset: MiyorareThemePreset,
	customAccent: String,
	amoled: Boolean,
	effectLevel: VisualEffectLevel,
): MiyorareViewPalette {
	val darkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
		Configuration.UI_MODE_NIGHT_YES
	val colors = miyorareThemeColors(
		preset = preset,
		customAccent = customAccent,
		darkTheme = darkTheme,
		amoled = amoled,
		effectLevel = effectLevel,
	)
	val scheme = colors.colorScheme
	val palette = colors.visualPalette
	return MiyorareViewPalette(
		resources = resources,
		preset = preset,
		background = scheme.background.toArgb(),
		surface = scheme.surface.toArgb(),
		surfaceContainer = scheme.surfaceContainer.toArgb(),
		surfaceContainerHigh = scheme.surfaceContainerHigh.toArgb(),
		primary = scheme.primary.toArgb(),
		onPrimary = scheme.onPrimary.toArgb(),
		primaryContainer = scheme.primaryContainer.toArgb(),
		onPrimaryContainer = scheme.onPrimaryContainer.toArgb(),
		secondary = scheme.secondary.toArgb(),
		accent = scheme.tertiary.toArgb(),
		onSurface = scheme.onSurface.toArgb(),
		onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
		outline = scheme.outline.toArgb(),
		outlineVariant = scheme.outlineVariant.toArgb(),
		selectedSurface = palette.selectedSurface.toArgb(),
		borderHighlight = palette.borderHighlight.toArgb(),
		glow = palette.glow.toArgb(),
		button = palette.button.toArgb(),
		onButton = palette.onButton.toArgb(),
		backgroundGradientStart = palette.backgroundGradientStart.toArgb(),
		backgroundGradientMiddle = palette.backgroundGradientMiddle.toArgb(),
		backgroundGradientEnd = palette.backgroundGradientEnd.toArgb(),
		surfaceGradientStart = palette.surfaceGradientStart.toArgb(),
		surfaceGradientMiddle = palette.surfaceGradientMiddle.toArgb(),
		surfaceGradientEnd = palette.surfaceGradientEnd.toArgb(),
		activeGradientStart = palette.activeGradientStart.toArgb(),
		activeGradientEnd = palette.activeGradientEnd.toArgb(),
	)
}
