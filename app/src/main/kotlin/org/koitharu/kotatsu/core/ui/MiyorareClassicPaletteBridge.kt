package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.AttrRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.use
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/**
 * Resolve a Classic-authored semantic palette without changing the host Activity theme.
 *
 * Static Classic schemes read their existing values / values-night resources through the same style
 * overlay Classic uses today. Expressive uses its real DynamicColors style and is reachable only
 * when [MiyorareModernPaletteSource] reports it available. No Classic artwork, shapes or widget
 * styles escape this temporary wrapper; only semantic colours are copied into Modern.
 */
fun Context.resolveMiyorareClassicDerivedColorScheme(
	paletteSource: MiyorareModernPaletteSource,
	darkTheme: Boolean,
): ColorScheme? {
	val classicScheme = paletteSource.classicScheme ?: return null
	if (!paletteSource.isAvailable()) return null

	val nightMode = if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
	val configuration = Configuration(resources.configuration).apply {
		uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
	}
	val configuredContext = createConfigurationContext(configuration)
	val themedContext = ContextThemeWrapper(configuredContext, classicScheme.styleResId)
	return themedContext.readClassicSemanticColorScheme(darkTheme)
}

private fun Context.readClassicSemanticColorScheme(darkTheme: Boolean): ColorScheme {
	val fallback = if (darkTheme) darkColorScheme() else lightColorScheme()
	return fallback.copy(
		primary = themeColor(appcompatR.attr.colorPrimary, fallback.primary),
		onPrimary = themeColor(materialR.attr.colorOnPrimary, fallback.onPrimary),
		primaryContainer = themeColor(materialR.attr.colorPrimaryContainer, fallback.primaryContainer),
		onPrimaryContainer = themeColor(materialR.attr.colorOnPrimaryContainer, fallback.onPrimaryContainer),
		inversePrimary = themeColor(materialR.attr.colorPrimaryInverse, fallback.inversePrimary),
		secondary = themeColor(materialR.attr.colorSecondary, fallback.secondary),
		onSecondary = themeColor(materialR.attr.colorOnSecondary, fallback.onSecondary),
		secondaryContainer = themeColor(materialR.attr.colorSecondaryContainer, fallback.secondaryContainer),
		onSecondaryContainer = themeColor(materialR.attr.colorOnSecondaryContainer, fallback.onSecondaryContainer),
		tertiary = themeColor(materialR.attr.colorTertiary, fallback.tertiary),
		onTertiary = themeColor(materialR.attr.colorOnTertiary, fallback.onTertiary),
		tertiaryContainer = themeColor(materialR.attr.colorTertiaryContainer, fallback.tertiaryContainer),
		onTertiaryContainer = themeColor(materialR.attr.colorOnTertiaryContainer, fallback.onTertiaryContainer),
		error = themeColor(appcompatR.attr.colorError, fallback.error),
		onError = themeColor(materialR.attr.colorOnError, fallback.onError),
		errorContainer = themeColor(materialR.attr.colorErrorContainer, fallback.errorContainer),
		onErrorContainer = themeColor(materialR.attr.colorOnErrorContainer, fallback.onErrorContainer),
		background = themeColor(android.R.attr.colorBackground, fallback.background),
		onBackground = themeColor(materialR.attr.colorOnBackground, fallback.onBackground),
		surface = themeColor(materialR.attr.colorSurface, fallback.surface),
		onSurface = themeColor(materialR.attr.colorOnSurface, fallback.onSurface),
		surfaceVariant = themeColor(materialR.attr.colorSurfaceVariant, fallback.surfaceVariant),
		onSurfaceVariant = themeColor(materialR.attr.colorOnSurfaceVariant, fallback.onSurfaceVariant),
		outline = themeColor(materialR.attr.colorOutline, fallback.outline),
		outlineVariant = themeColor(materialR.attr.colorOutlineVariant, fallback.outlineVariant),
		inverseSurface = themeColor(materialR.attr.colorSurfaceInverse, fallback.inverseSurface),
		inverseOnSurface = themeColor(materialR.attr.colorOnSurfaceInverse, fallback.inverseOnSurface),
		surfaceTint = themeColor(appcompatR.attr.colorPrimary, fallback.primary),
		scrim = Color.Black,
		surfaceDim = themeColor(materialR.attr.colorSurfaceDim, fallback.surfaceDim),
		surfaceBright = themeColor(materialR.attr.colorSurfaceBright, fallback.surfaceBright),
		surfaceContainerLowest = themeColor(materialR.attr.colorSurfaceContainerLowest, fallback.surfaceContainerLowest),
		surfaceContainerLow = themeColor(materialR.attr.colorSurfaceContainerLow, fallback.surfaceContainerLow),
		surfaceContainer = themeColor(materialR.attr.colorSurfaceContainer, fallback.surfaceContainer),
		surfaceContainerHigh = themeColor(materialR.attr.colorSurfaceContainerHigh, fallback.surfaceContainerHigh),
		surfaceContainerHighest = themeColor(materialR.attr.colorSurfaceContainerHighest, fallback.surfaceContainerHighest),
	)
}

private fun Context.themeColor(@AttrRes attr: Int, fallback: Color): Color =
	obtainStyledAttributes(intArrayOf(attr)).use { array ->
		Color(array.getColor(0, fallback.toArgb()))
	}
