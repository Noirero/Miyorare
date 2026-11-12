package org.koitharu.kotatsu.settings.compose

import android.content.res.Configuration
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareCustomBackgroundIntensity
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareSettingsIconStyle
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.MiyorareCustomBackgroundStore
import org.koitharu.kotatsu.core.ui.PrivateFavouritesVisualResolver
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.classicMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.resolveMiyorareClassicDerivedColorScheme
import org.koitharu.kotatsu.core.ui.resolveMiyorareThemeColors
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.main.ui.nav.composeColorSchemeFromTheme

/** One preference observer per settings theme composition, not per icon row. */
val LocalSettingsIconStyle = compositionLocalOf { MiyorareSettingsIconStyle.FOLLOW_THEME }

private const val ROND_ROUNDED = 100f

private val classicShapes = Shapes()

private val miyorareShapes = Shapes(
	extraSmall = RoundedCornerShape(MiyorareVisualTokens.RADIUS_SMALL_DP.dp),
	small = RoundedCornerShape(MiyorareVisualTokens.RADIUS_SMALL_DP.dp),
	medium = RoundedCornerShape(MiyorareVisualTokens.RADIUS_CONTROL_DP.dp),
	large = RoundedCornerShape(MiyorareVisualTokens.RADIUS_SURFACE_DP.dp),
	extraLarge = RoundedCornerShape(MiyorareVisualTokens.RADIUS_DIALOG_DP.dp),
)

/**
 * Variable-font family that mirrors the project's `gflex_variable.ttf` with the rounded
 * ROND axis enabled. Weights here are already +1 step over PixelPlayer's reference, to
 * match the project-wide font bump.
 *
 * This descriptor is immutable, so keep one process-wide instance instead of rebuilding it
 * every time a Compose settings fragment creates a new composition.
 */
@OptIn(ExperimentalTextApi::class)
private val googleSansRounded = FontFamily(
	Font(R.font.gflex_variable, weight = FontWeight.Normal, variationSettings = roundVariation(500)),
	Font(R.font.gflex_variable, weight = FontWeight.Medium, variationSettings = roundVariation(600)),
	Font(R.font.gflex_variable, weight = FontWeight.SemiBold, variationSettings = roundVariation(700)),
	Font(R.font.gflex_variable, weight = FontWeight.Bold, variationSettings = roundVariation(800)),
)

private fun roundVariation(weight: Int) = FontVariation.Settings(
	FontVariation.weight(weight),
	FontVariation.Setting("ROND", ROND_ROUNDED),
)

private fun bumpedTypography(family: FontFamily): Typography {
	val noPadding = PlatformTextStyle(includeFontPadding = false)
	return Typography(
		displayLarge = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Bold,
			fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = 0.sp,
			platformStyle = noPadding,
		),
		displayMedium = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Bold,
			fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp,
			platformStyle = noPadding,
		),
		displaySmall = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Normal,
			fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 0.sp,
			platformStyle = noPadding,
		),
		headlineLarge = TextStyle(
			fontFamily = family, fontWeight = FontWeight.SemiBold,
			fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp,
			platformStyle = noPadding,
		),
		headlineMedium = TextStyle(
			fontFamily = family, fontWeight = FontWeight.SemiBold,
			fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp,
			platformStyle = noPadding,
		),
		headlineSmall = TextStyle(
			fontFamily = family, fontWeight = FontWeight.SemiBold,
			fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp,
			platformStyle = noPadding,
		),
		titleLarge = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Normal,
			fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
			platformStyle = noPadding,
		),
		titleMedium = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Medium,
			fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp,
			platformStyle = noPadding,
		),
		titleSmall = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Medium,
			fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
			platformStyle = noPadding,
		),
		bodyLarge = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Normal,
			fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp,
			platformStyle = noPadding,
		),
		bodyMedium = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Normal,
			fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp,
			platformStyle = noPadding,
		),
		bodySmall = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Normal,
			fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
			platformStyle = noPadding,
		),
		labelLarge = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Medium,
			fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
			platformStyle = noPadding,
		),
		labelMedium = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Medium,
			fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
			platformStyle = noPadding,
		),
		labelSmall = TextStyle(
			fontFamily = family, fontWeight = FontWeight.Medium,
			fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
			platformStyle = noPadding,
		),
	)
}

private val miyorareTypography = bumpedTypography(googleSansRounded)

/**
 * Shared Compose theme bridge. Classic keeps the host Android colors and baseline Material shapes.
 * Miyorare Modern swaps presentation colors and Miyorare geometry while retaining the same content,
 * navigation and data behavior.
 */
@Composable
fun MiyorareTheme(content: @Composable () -> Unit) {
	val ctx = LocalContext.current
	val isDark = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
		Configuration.UI_MODE_NIGHT_YES

	val designStyleValue by rememberStringPref(
		MiyorareAppearance.KEY_DESIGN_STYLE,
		MiyorareDesignStyle.CLASSIC.name,
	)
	val iconStyleValue by rememberStringPref(
		MiyorareAppearance.KEY_SETTINGS_ICON_STYLE,
		MiyorareSettingsIconStyle.FOLLOW_THEME.name,
	)
	val themePresetValue by rememberStringPref(
		MiyorareAppearance.KEY_THEME_PRESET,
		MiyorareThemePreset.MIYORARE.name,
	)
	val paletteSourceValue by rememberStringPref(
		MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE,
		MiyorareModernPaletteSource.MATCH_THEME.name,
	)
	val privateThemeValue by rememberStringPref(
		MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME,
		PrivateFavouritesThemePreset.FOLLOW_NORMAL.name,
	)
	val customAccent by rememberStringPref(
		MiyorareAppearance.KEY_CUSTOM_ACCENT,
		MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
	)
	val customBackgroundColorSync by rememberBooleanPref(
		MiyorareAppearance.KEY_CUSTOM_BACKGROUND_COLOR_SYNC,
		true,
	)
	val customBackgroundIntensityValue by rememberStringPref(
		MiyorareAppearance.KEY_CUSTOM_BACKGROUND_INTENSITY,
		MiyorareCustomBackgroundIntensity.BALANCED.name,
	)
	val customBackgroundPrimary by rememberStringPref(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_PRIMARY, "")
	val customBackgroundSecondary by rememberStringPref(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_SECONDARY, "")
	val customBackgroundTertiary by rememberStringPref(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_TERTIARY, "")
	val customBackgroundRevision by rememberIntPref(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_REVISION, 0)
	val amoled by rememberBooleanPref(AppSettings.KEY_THEME_AMOLED, false)
	val effectLevelValue by rememberStringPref(
		VisualEffectPreferences.KEY_LEVEL,
		VisualEffectLevel.BALANCED.name,
	)

	val designStyle = MiyorareDesignStyle.entries.firstOrNull { it.name == designStyleValue }
		?: MiyorareDesignStyle.CLASSIC
	val settingsIconStyle = MiyorareSettingsIconStyle.entries.firstOrNull { it.name == iconStyleValue }
		?: MiyorareSettingsIconStyle.FOLLOW_THEME
	val themePreset = MiyorareThemePreset.entries.firstOrNull { it.name == themePresetValue }
		?: MiyorareThemePreset.MIYORARE
	val normalPaletteSource = MiyorareModernPaletteSource.fromStoredValue(paletteSourceValue)
	val privateTheme = PrivateFavouritesThemePreset.entries.firstOrNull { it.name == privateThemeValue }
		?: PrivateFavouritesThemePreset.FOLLOW_NORMAL
	val effectLevel = VisualEffectLevel.entries.firstOrNull { it.name == effectLevelValue }
		?: VisualEffectLevel.BALANCED
	val customBackgroundIntensity = MiyorareCustomBackgroundIntensity.entries.firstOrNull {
		it.name == customBackgroundIntensityValue
	} ?: MiyorareCustomBackgroundIntensity.BALANCED
	val hostActivity = ctx.findActivity()
	val privateHost = hostActivity?.intent?.getIntExtra(
		EXTRA_FAVOURITE_SPACE,
		FavouriteSpace.NORMAL.dbValue,
	) == FavouriteSpace.PRIVATE.dbValue ||
		hostActivity?.intent?.action == AppRouter.ACTION_PRIVATE_FAVOURITES_SETTINGS ||
		hostActivity?.intent?.action == AppRouter.ACTION_PRIVATE_EXTENSIONS_SETTINGS
	// FOLLOW_NORMAL is intentionally unchanged; explicit Private themes own their preset and dark foundation.
	val privateSpec = if (privateHost) PrivateFavouritesVisualResolver.resolve(privateTheme) else null
	val effectivePreset = if (privateHost) privateTheme.resolve(themePreset) else themePreset
	val effectiveDark = isDark || privateSpec != null
	val paletteSource = if (privateSpec != null) MiyorareModernPaletteSource.MATCH_THEME else normalPaletteSource
	val customBackgroundAvailable = remember(customBackgroundRevision, themePreset, privateHost) {
		!privateHost &&
			themePreset == MiyorareThemePreset.CUSTOM &&
			MiyorareCustomBackgroundStore.hasBackground(ctx)
	}
	val adaptivePalette = remember(
		themePreset,
		customBackgroundAvailable,
		customBackgroundColorSync,
		customBackgroundPrimary,
		customBackgroundSecondary,
		customBackgroundTertiary,
		customBackgroundIntensity,
	) {
		if (themePreset == MiyorareThemePreset.CUSTOM && customBackgroundAvailable && customBackgroundColorSync) {
			MiyorareAppearance.resolveAdaptivePalette(
				primary = customBackgroundPrimary,
				secondary = customBackgroundSecondary,
				tertiary = customBackgroundTertiary,
				intensity = customBackgroundIntensity,
			)
		} else null
	}

	val modernColors = if (designStyle == MiyorareDesignStyle.MODERN) {
		remember(ctx, paletteSource, effectivePreset, customAccent, adaptivePalette, effectiveDark, amoled, effectLevel) {
			val classicDerivedScheme = ctx.resolveMiyorareClassicDerivedColorScheme(
				paletteSource = paletteSource,
				darkTheme = effectiveDark,
			)
			resolveMiyorareThemeColors(
				paletteSource = paletteSource,
				preset = effectivePreset,
				customAccent = customAccent,
				adaptivePalette = adaptivePalette,
				darkTheme = effectiveDark,
				amoled = amoled,
				effectLevel = effectLevel,
				classicDerivedColorScheme = classicDerivedScheme,
			)
		}
	} else null
	val fallbackScheme = modernColors?.colorScheme ?: remember(ctx, isDark) { composeColorSchemeFromTheme(ctx, isDark) }
	// Match the authored Private palette used by the existing View chrome, not just its hue.
	val scheme = if (privateSpec != null && modernColors != null) {
		val spec = privateSpec
		fallbackScheme.copy(
			background = Color(spec.background),
			surface = Color(spec.surface),
			surfaceContainer = Color(ColorUtils.blendARGB(spec.surface, spec.background, 0.18f)),
			surfaceContainerHigh = Color(ColorUtils.blendARGB(spec.surfaceHigh, spec.primary, 0.06f)),
			primary = Color(spec.primary),
			onPrimary = Color(spec.onPrimary),
			primaryContainer = Color(ColorUtils.blendARGB(spec.surfaceHigh, spec.primary, 0.34f)),
			onPrimaryContainer = Color(spec.onSurface),
			secondary = Color(spec.secondary),
			tertiary = Color(spec.accent),
			onSurface = Color(spec.onSurface),
			onSurfaceVariant = Color(spec.onSurfaceVariant),
			outline = Color(spec.outline),
			outlineVariant = Color(ColorUtils.blendARGB(spec.outline, spec.background, 0.34f)),
		)
	} else fallbackScheme
	val basePalette = modernColors?.visualPalette ?: remember(scheme, effectLevel) {
		classicMiyorareVisualPalette(scheme, effectLevel)
	}
	val visualPalette = if (privateSpec != null && modernColors != null) {
		val spec = privateSpec
		val high = ColorUtils.blendARGB(spec.surfaceHigh, spec.primary, 0.06f)
		val activeStart = ColorUtils.blendARGB(spec.surfaceHigh, spec.primary, 0.34f)
		basePalette.copy(
			primary = Color(spec.primary),
			secondary = Color(spec.secondary),
			accent = Color(spec.accent),
			border = Color(spec.outline),
			selectedSurface = Color(ColorUtils.blendARGB(high, spec.primary, 0.18f)),
			borderHighlight = Color(ColorUtils.setAlphaComponent(spec.primary, 0xB8)),
			glow = Color(ColorUtils.setAlphaComponent(spec.primary, 0x78)),
			button = Color(ColorUtils.blendARGB(spec.primary, spec.surfaceHigh, 0.16f)),
			onButton = Color(spec.onPrimary),
			backgroundGradientStart = Color(spec.background),
			backgroundGradientMiddle = Color(ColorUtils.blendARGB(spec.background, spec.secondary, 0.08f)),
			backgroundGradientEnd = Color(ColorUtils.blendARGB(spec.background, spec.surface, 0.46f)),
			surfaceGradientStart = Color(spec.surfaceHigh),
			surfaceGradientMiddle = Color(spec.surface),
			surfaceGradientEnd = Color(spec.background),
			activeGradientStart = Color(activeStart),
			activeGradientEnd = Color(ColorUtils.blendARGB(activeStart, spec.accent, 0.22f)),
		)
	} else basePalette
	val shapes = if (designStyle == MiyorareDesignStyle.MODERN) miyorareShapes else classicShapes
	CompositionLocalProvider(
		LocalMiyorareVisualPalette provides visualPalette,
		LocalSettingsIconStyle provides settingsIconStyle,
	) {
		MaterialTheme(
			colorScheme = scheme,
			shapes = shapes,
			typography = miyorareTypography,
			content = content,
		)
	}
}
