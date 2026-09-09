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
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace

/** Android View bridge for the same semantic Modern palette used by Compose. */
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

/**
 * Hilt-backed View entry point. FOLLOW_NORMAL stays on the exact Normal palette path. Explicit
 * Private themes resolve their own authored visual spec and force a dark Private foundation.
 */
fun Context.miyorareViewPalette(
	settings: AppSettings,
	effectLevel: VisualEffectLevel,
): MiyorareViewPalette {
	val privateFavourites = isPrivateFavouritesHost()
	val privateTheme = if (privateFavourites) privateFavouritesThemeFromPreferences() else PrivateFavouritesThemePreset.FOLLOW_NORMAL
	val privateSpec = if (privateFavourites) PrivateFavouritesVisualResolver.resolve(privateTheme) else null
	val preset = if (privateFavourites) privateTheme.resolve(settings.miyorareThemePreset) else settings.miyorareThemePreset
	val palette = buildMiyorareViewPalette(
		preset = preset,
		customAccent = settings.miyorareCustomAccent,
		amoled = settings.isAmoledTheme,
		effectLevel = effectLevel,
		forceDark = privateSpec != null,
	)
	return privateSpec?.let(palette::applyPrivateFavouritesVisualSpec) ?: palette
}

/**
 * Preference-backed palette entry point for custom Views that cannot receive Hilt dependencies.
 * Returns null for Classic so Modern-only header shells stay completely isolated from Classic.
 * FOLLOW_NORMAL inherits the current Normal preset/light-dark path. Explicit Private themes use
 * their own dark palette and artwork identity.
 */
fun Context.miyorareViewPaletteFromPreferences(
	privateFavourites: Boolean = false,
): MiyorareViewPalette? {
	val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
	val designStyle = prefs.getString(MiyorareAppearance.KEY_DESIGN_STYLE, null)
		?.let { value -> MiyorareDesignStyle.entries.firstOrNull { it.name == value } }
		?: MiyorareDesignStyle.CLASSIC
	if (designStyle != MiyorareDesignStyle.MODERN) return null

	val normalPreset = prefs.getString(MiyorareAppearance.KEY_THEME_PRESET, null)
		?.let { value -> MiyorareThemePreset.entries.firstOrNull { it.name == value } }
		?: MiyorareThemePreset.MIYORARE
	val privateTheme = if (privateFavourites) privateFavouritesThemeFromPreferences() else PrivateFavouritesThemePreset.FOLLOW_NORMAL
	val privateSpec = if (privateFavourites) PrivateFavouritesVisualResolver.resolve(privateTheme) else null
	val preset = if (privateFavourites) privateTheme.resolve(normalPreset) else normalPreset
	val customAccent = prefs.getString(
		MiyorareAppearance.KEY_CUSTOM_ACCENT,
		MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
	)?.let { value -> MiyorareAppearance.normalizeAccent(value) }
		?: MiyorareAppearance.DEFAULT_CUSTOM_ACCENT
	val effectLevel = prefs.getString(VisualEffectPreferences.KEY_LEVEL, null)
		?.let { value -> VisualEffectLevel.entries.firstOrNull { it.name == value } }
		?: VisualEffectLevel.BALANCED

	val palette = buildMiyorareViewPalette(
		preset = preset,
		customAccent = customAccent,
		amoled = prefs.getBoolean(AppSettings.KEY_THEME_AMOLED, false),
		effectLevel = effectLevel,
		forceDark = privateSpec != null,
	)
	return privateSpec?.let(palette::applyPrivateFavouritesVisualSpec) ?: palette
}

private fun Context.isPrivateFavouritesHost(): Boolean {
	val activity = findActivity() ?: return false
	return activity.intent?.getIntExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue) ==
		FavouriteSpace.PRIVATE.dbValue
}

private fun Context.buildMiyorareViewPalette(
	preset: MiyorareThemePreset,
	customAccent: String,
	amoled: Boolean,
	effectLevel: VisualEffectLevel,
	forceDark: Boolean,
): MiyorareViewPalette {
	val darkTheme = forceDark || (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
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
