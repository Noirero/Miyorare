package org.koitharu.kotatsu.settings.compose

import android.content.res.Configuration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.classicMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareThemeColors
import org.koitharu.kotatsu.main.ui.nav.composeColorSchemeFromTheme

private const val ROND_ROUNDED = 100f

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
 */
@OptIn(ExperimentalTextApi::class)
private val GoogleSansRounded: FontFamily
	@Composable
	get() = remember {
		FontFamily(
			Font(R.font.gflex_variable, weight = FontWeight.Normal, variationSettings = roundVariation(500)),
			Font(R.font.gflex_variable, weight = FontWeight.Medium, variationSettings = roundVariation(600)),
			Font(R.font.gflex_variable, weight = FontWeight.SemiBold, variationSettings = roundVariation(700)),
			Font(R.font.gflex_variable, weight = FontWeight.Bold, variationSettings = roundVariation(800)),
		)
	}

private fun roundVariation(weight: Int) = FontVariation.Settings(
	FontVariation.weight(weight),
	FontVariation.Setting("ROND", ROND_ROUNDED),
)

@Composable
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

/**
 * Shared Compose theme bridge. Classic keeps the exact host Android color scheme. Miyorare Modern
 * swaps only presentation colors while retaining the same content, navigation and data behavior.
 */
@Composable
fun DropSauceTheme(content: @Composable () -> Unit) {
	val ctx = LocalContext.current
	val isDark = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
		Configuration.UI_MODE_NIGHT_YES

	val designStyleValue by rememberStringPref(
		MiyorareAppearance.KEY_DESIGN_STYLE,
		MiyorareDesignStyle.CLASSIC.name,
	)
	val themePresetValue by rememberStringPref(
		MiyorareAppearance.KEY_THEME_PRESET,
		MiyorareThemePreset.MIYORARE.name,
	)
	val customAccent by rememberStringPref(
		MiyorareAppearance.KEY_CUSTOM_ACCENT,
		MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
	)
	val amoled by rememberBooleanPref(AppSettings.KEY_THEME_AMOLED, false)
	val effectLevelValue by rememberStringPref(
		VisualEffectPreferences.KEY_LEVEL,
		VisualEffectLevel.BALANCED.name,
	)

	val designStyle = MiyorareDesignStyle.entries.firstOrNull { it.name == designStyleValue }
		?: MiyorareDesignStyle.CLASSIC
	val themePreset = MiyorareThemePreset.entries.firstOrNull { it.name == themePresetValue }
		?: MiyorareThemePreset.MIYORARE
	val effectLevel = VisualEffectLevel.entries.firstOrNull { it.name == effectLevelValue }
		?: VisualEffectLevel.BALANCED

	val modernColors = if (designStyle == MiyorareDesignStyle.MODERN) {
		remember(themePreset, customAccent, isDark, amoled, effectLevel) {
			miyorareThemeColors(
				preset = themePreset,
				customAccent = customAccent,
				darkTheme = isDark,
				amoled = amoled,
				effectLevel = effectLevel,
			)
		}
	} else null
	val scheme = modernColors?.colorScheme ?: remember(ctx, isDark) { composeColorSchemeFromTheme(ctx, isDark) }
	val visualPalette = modernColors?.visualPalette ?: remember(scheme, effectLevel) {
		classicMiyorareVisualPalette(scheme, effectLevel)
	}
	val family = GoogleSansRounded
	val typography = bumpedTypography(family)
	CompositionLocalProvider(LocalMiyorareVisualPalette provides visualPalette) {
		MaterialTheme(
			colorScheme = scheme,
			shapes = miyorareShapes,
			typography = typography,
			content = content,
		)
	}
}
