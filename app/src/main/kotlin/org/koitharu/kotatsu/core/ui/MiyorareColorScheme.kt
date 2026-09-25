package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.koitharu.kotatsu.core.prefs.MiyorareAdaptivePalette
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeSignatureRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeTokens

/** Reusable semantic colors for Modern components; screens never derive their own palette. */
data class MiyorareVisualPalette(
	val isModern: Boolean,
	val effectLevel: VisualEffectLevel,
	val primary: Color,
	val secondary: Color,
	val accent: Color,
	val selectedSurface: Color,
	val border: Color,
	val borderHighlight: Color,
	val glow: Color,
	val chip: Color,
	val button: Color,
	val onButton: Color,
	val backgroundGradientStart: Color,
	val backgroundGradientMiddle: Color,
	val backgroundGradientEnd: Color,
	val surfaceGradientStart: Color,
	val surfaceGradientMiddle: Color,
	val surfaceGradientEnd: Color,
	val iconGradientStart: Color,
	val iconGradientEnd: Color,
	val accentGradientStart: Color,
	val accentGradientMiddle: Color,
	val accentGradientEnd: Color,
	val activeGradientStart: Color,
	val activeGradientEnd: Color,
	val gradientStrength: Float,
	val error: Color,
	val warning: Color,
	val success: Color,
	val destructive: Color,
	val disabled: Color,
	val focusIndicator: Color,
	val adaptiveCustomBackground: Boolean = false,
	val rankThemeId: String? = null,
	val rankBorderGradient: List<Color> = emptyList(),
	val rankSelectedGradient: List<Color> = emptyList(),
)

data class MiyorareThemeColors(
	val colorScheme: ColorScheme,
	val visualPalette: MiyorareVisualPalette,
)

val LocalMiyorareVisualPalette = staticCompositionLocalOf {
	MiyorareVisualPalette(
		isModern = false,
		effectLevel = VisualEffectLevel.BALANCED,
		primary = Color.Unspecified,
		secondary = Color.Unspecified,
		accent = Color.Unspecified,
		selectedSurface = Color.Unspecified,
		border = Color.Unspecified,
		borderHighlight = Color.Transparent,
		glow = Color.Transparent,
		chip = Color.Unspecified,
		button = Color.Unspecified,
		onButton = Color.Unspecified,
		backgroundGradientStart = Color.Unspecified,
		backgroundGradientMiddle = Color.Unspecified,
		backgroundGradientEnd = Color.Unspecified,
		surfaceGradientStart = Color.Unspecified,
		surfaceGradientMiddle = Color.Unspecified,
		surfaceGradientEnd = Color.Unspecified,
		iconGradientStart = Color.Unspecified,
		iconGradientEnd = Color.Unspecified,
		accentGradientStart = Color.Unspecified,
		accentGradientMiddle = Color.Unspecified,
		accentGradientEnd = Color.Unspecified,
		activeGradientStart = Color.Unspecified,
		activeGradientEnd = Color.Unspecified,
		gradientStrength = 0f,
		error = Color(0xFFBA1A1A),
		warning = Color(0xFFF9A825),
		success = Color(0xFF2E7D32),
		destructive = Color(0xFFB3261E),
		disabled = Color(0xFF7A7A7A),
		focusIndicator = Color(0xFF0066CC),
	)
}

private data class PaletteSeeds(
	val primary: Color,
	val secondary: Color,
	val accent: Color,
)

/**
 * Builds one stable Material palette and semantic gradient token set from the selected Modern
 * preset. Built-in presets own three curated color seeds; Custom derives two harmonious companion
 * colors from the user's seed instead of inheriting Miyorare cyan/pink.
 *
 * Everything here is finite color math remembered by the theme caller: no continuous shader,
 * per-list-item animation or data/runtime behavior is introduced.
 */
fun miyorareThemeColors(
	preset: MiyorareThemePreset,
	customAccent: String,
	adaptivePalette: MiyorareAdaptivePalette? = null,
	darkTheme: Boolean,
	amoled: Boolean,
	effectLevel: VisualEffectLevel,
	rankThemeTokens: RankThemeTokens? = null,
	rankThemeId: String? = null,
): MiyorareThemeColors {
	val rawSeeds = if (rankThemeTokens != null) {
		PaletteSeeds(
			primary = rankThemeTokens.primaryAccent.toComposeColor(),
			secondary = rankThemeTokens.secondaryAccent.toComposeColor(),
			accent = rankThemeTokens.iconAccent.toComposeColor(),
		)
	} else if (preset == MiyorareThemePreset.CUSTOM) {
		adaptivePalette?.let { palette ->
			PaletteSeeds(
				primary = Color(palette.primaryArgb),
				secondary = Color(palette.secondaryArgb),
				accent = Color(palette.tertiaryArgb),
			)
		} ?: run {
			val customPrimary = Color(
				MiyorareAppearance.parseAccentArgb(customAccent)
					?: MiyorareThemePreset.MIYORARE.accentArgb,
			)
			deriveCustomPaletteSeeds(customPrimary)
		}
	} else {
		PaletteSeeds(
			primary = Color(preset.accentArgb),
			secondary = Color(preset.secondaryArgb),
			accent = Color(preset.tertiaryArgb),
		)
	}

	val useAmoled = darkTheme && amoled
	val authoredBackground = rankThemeTokens?.background?.toComposeColor()
	val authoredSurface = rankThemeTokens?.surface?.toComposeColor()
	val authoredSurfaceVariant = rankThemeTokens?.surfaceVariant?.toComposeColor()
	val authoredContainer = rankThemeTokens?.container?.toComposeColor()
	val contrastSurface = when {
		useAmoled -> Color.Black
		authoredSurface != null -> authoredSurface
		darkTheme -> Color(0xFF121218)
		else -> Color.White
	}
	val primary = ensureVisibleAgainst(rawSeeds.primary, contrastSurface, darkTheme)
	val secondary = ensureVisibleAgainst(rawSeeds.secondary, contrastSurface, darkTheme)
	val accent = ensureVisibleAgainst(rawSeeds.accent, contrastSurface, darkTheme)
	val tint = effectLevel.surfaceTintFraction.coerceIn(0f, 0.24f)
	val gradientStrength = when (effectLevel) {
		VisualEffectLevel.LIGHT -> MiyorareVisualTokens.GRADIENT_STRENGTH_LIGHT
		VisualEffectLevel.BALANCED -> MiyorareVisualTokens.GRADIENT_STRENGTH_BALANCED
		VisualEffectLevel.FULL -> MiyorareVisualTokens.GRADIENT_STRENGTH_FULL
	}
	val borderAlpha = when (effectLevel) {
		VisualEffectLevel.LIGHT -> MiyorareVisualTokens.BORDER_ALPHA_LIGHT
		VisualEffectLevel.BALANCED -> MiyorareVisualTokens.BORDER_ALPHA_BALANCED
		VisualEffectLevel.FULL -> MiyorareVisualTokens.BORDER_ALPHA_FULL
	}
	val glowAlpha = when (effectLevel) {
		VisualEffectLevel.LIGHT -> MiyorareVisualTokens.GLOW_ALPHA_LIGHT
		VisualEffectLevel.BALANCED -> MiyorareVisualTokens.GLOW_ALPHA_BALANCED
		VisualEffectLevel.FULL -> MiyorareVisualTokens.GLOW_ALPHA_FULL
	}

	val colorScheme: ColorScheme
	val selectedSurface: Color
	val border: Color
	val chip: Color
	if (darkTheme) {
		val background = if (useAmoled) Color.Black else authoredBackground
			?: lerp(Color(0xFF0B0B10), primary, tint * 0.10f)
		val surface = if (useAmoled) Color.Black else authoredSurface
			?: lerp(Color(0xFF121218), primary, tint * 0.14f)
		val surfaceVariant = authoredSurfaceVariant ?: lerp(Color(0xFF202028), secondary, tint * 0.18f)
		selectedSurface = rankThemeTokens?.selectedStateColor?.toComposeColor()?.let {
			lerp(surface, it, 0.24f + tint * 0.16f)
		} ?: lerp(Color(0xFF24212A), primary, 0.25f + tint * 0.20f)
		chip = lerp(authoredContainer ?: Color(0xFF18181F), secondary, 0.09f + tint * 0.17f)
		border = rankThemeTokens?.borderEmphasis?.toComposeColor()
			?: lerp(Color(0xFF6E6A74), primary, tint * 0.25f)
		colorScheme = darkColorScheme(
			primary = primary,
			onPrimary = bestContentColor(primary),
			primaryContainer = selectedSurface,
			onPrimaryContainer = bestContentColor(selectedSurface),
			secondary = secondary,
			onSecondary = bestContentColor(secondary),
			secondaryContainer = chip,
			onSecondaryContainer = bestContentColor(chip),
			tertiary = accent,
			onTertiary = bestContentColor(accent),
			background = background,
			onBackground = Color(0xFFF7F5F8),
			surface = surface,
			onSurface = Color(0xFFF5F3F6),
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = Color(0xFFCEC9D1),
			outline = border,
			outlineVariant = rankThemeTokens?.borderSubtle?.toComposeColor()
				?: lerp(Color(0xFF38343D), primary, tint * 0.13f),
			surfaceContainer = if (useAmoled) Color.Black else authoredContainer
				?: lerp(Color(0xFF16151B), primary, tint * 0.12f),
			surfaceContainerHigh = if (useAmoled) Color(0xFF080808) else authoredSurfaceVariant
				?: lerp(Color(0xFF1D1B22), secondary, tint * 0.16f),
			error = rankThemeTokens?.errorColor?.toComposeColor() ?: Color(0xFFFFB4AB),
		)
	} else {
		val background = authoredBackground ?: lerp(Color(0xFFFAF9FC), primary, tint * 0.045f)
		val surface = authoredSurface ?: lerp(Color.White, primary, tint * 0.035f)
		val surfaceVariant = authoredSurfaceVariant ?: lerp(Color(0xFFEEEAF0), secondary, tint * 0.10f)
		selectedSurface = rankThemeTokens?.selectedStateColor?.toComposeColor()?.let {
			lerp(surface, it, 0.13f + tint * 0.10f)
		} ?: lerp(Color(0xFFF1EDF3), primary, 0.11f + tint * 0.16f)
		chip = lerp(authoredContainer ?: Color(0xFFF3F1F5), secondary, 0.065f + tint * 0.12f)
		border = rankThemeTokens?.borderEmphasis?.toComposeColor()
			?: lerp(Color(0xFF817C86), primary, tint * 0.16f)
		colorScheme = lightColorScheme(
			primary = primary,
			onPrimary = bestContentColor(primary),
			primaryContainer = selectedSurface,
			onPrimaryContainer = bestContentColor(selectedSurface),
			secondary = secondary,
			onSecondary = bestContentColor(secondary),
			secondaryContainer = chip,
			onSecondaryContainer = bestContentColor(chip),
			tertiary = accent,
			onTertiary = bestContentColor(accent),
			background = background,
			onBackground = Color(0xFF1C1A20),
			surface = surface,
			onSurface = Color(0xFF211E24),
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = Color(0xFF625F68),
			outline = border,
			outlineVariant = rankThemeTokens?.borderSubtle?.toComposeColor()
				?: lerp(Color(0xFFD8D3DB), primary, tint * 0.10f),
			surfaceContainer = authoredContainer ?: lerp(Color(0xFFF5F2F6), primary, tint * 0.07f),
			surfaceContainerHigh = authoredSurfaceVariant ?: lerp(Color(0xFFEDE9EF), secondary, tint * 0.11f),
			error = rankThemeTokens?.errorColor?.toComposeColor() ?: Color(0xFFBA1A1A),
		)
	}

	val backgroundGradientStart: Color
	val backgroundGradientMiddle: Color
	val backgroundGradientEnd: Color
	if (useAmoled) {
		backgroundGradientStart = Color.Black
		backgroundGradientMiddle = Color.Black
		backgroundGradientEnd = Color.Black
	} else if (rankThemeTokens?.backgroundGradientStart != null) {
		backgroundGradientStart = checkNotNull(rankThemeTokens.backgroundGradientStart).toComposeColor()
		backgroundGradientMiddle = checkNotNull(rankThemeTokens.backgroundGradientMiddle).toComposeColor()
		backgroundGradientEnd = checkNotNull(rankThemeTokens.backgroundGradientEnd).toComposeColor()
	} else {
		backgroundGradientStart = lerp(colorScheme.background, primary, gradientStrength * 0.22f)
		backgroundGradientMiddle = lerp(colorScheme.background, secondary, gradientStrength * 0.12f)
		backgroundGradientEnd = lerp(colorScheme.background, accent, gradientStrength * 0.18f)
	}
	val surfaceGradientStart = rankThemeTokens?.surfaceGradientStart?.toComposeColor() ?: lerp(
		colorScheme.surfaceContainer,
		primary,
		gradientStrength * MiyorareVisualTokens.SURFACE_GRADIENT_MIX,
	)
	val surfaceGradientMiddle = rankThemeTokens?.surfaceGradientMiddle?.toComposeColor() ?: lerp(
		colorScheme.surfaceContainerHigh,
		accent,
		gradientStrength * 0.10f,
	)
	val surfaceGradientEnd = rankThemeTokens?.surfaceGradientEnd?.toComposeColor() ?: lerp(
		colorScheme.surfaceContainerHigh,
		secondary,
		gradientStrength * 0.32f,
	)
	val iconGradientStart = lerp(
		selectedSurface,
		primary,
		MiyorareVisualTokens.ICON_GRADIENT_MIX + gradientStrength * 0.22f,
	)
	val iconGradientEnd = lerp(
		selectedSurface,
		secondary,
		0.10f + gradientStrength * 0.34f,
	)
	val accentGradientMiddle = lerp(primary, secondary, 0.34f + gradientStrength * 0.32f)
	val accentGradientEnd = lerp(secondary, accent, 0.12f + gradientStrength * 0.40f)
	val activeGradientStart = rankThemeTokens?.activeGradientStart?.toComposeColor()
		?: lerp(primary, secondary, gradientStrength * MiyorareVisualTokens.ACTIVE_GRADIENT_MIX)
	val activeGradientEnd = rankThemeTokens?.activeGradientEnd?.toComposeColor()
		?: lerp(secondary, accent, 0.08f + gradientStrength * 0.44f)
	val borderHighlight = lerp(border, secondary, 0.22f + gradientStrength * 0.26f).copy(alpha = borderAlpha)
	val glow = lerp(primary, secondary, 0.34f).copy(alpha = glowAlpha)

	// Rank 90 is the first final-rank theme wired to its complete authored signature globally.
	// Keep this opt-in by stable ID so lower ranks and Rank 100 remain unchanged until their own pass.
	val imperialAuroraSignature = if (rankThemeId == RankThemeId.IMPERIAL_AURORA.stableId) {
		RankThemeSignatureRegistry.resolve(RankThemeId.IMPERIAL_AURORA)
	} else {
		null
	}
	val rankBorderGradient = imperialAuroraSignature?.borderStops?.map { it.toComposeColor() }.orEmpty()
	val rankSelectedGradient = imperialAuroraSignature?.selectedStops?.map { it.toComposeColor() }.orEmpty()

	return MiyorareThemeColors(
		colorScheme = colorScheme,
		visualPalette = MiyorareVisualPalette(
			isModern = true,
			effectLevel = effectLevel,
			primary = primary,
			secondary = secondary,
			accent = accent,
			selectedSurface = selectedSurface,
			border = border,
			borderHighlight = borderHighlight,
			glow = glow,
			chip = chip,
			button = primary,
			onButton = bestContentColor(primary),
			backgroundGradientStart = backgroundGradientStart,
			backgroundGradientMiddle = backgroundGradientMiddle,
			backgroundGradientEnd = backgroundGradientEnd,
			surfaceGradientStart = surfaceGradientStart,
			surfaceGradientMiddle = surfaceGradientMiddle,
			surfaceGradientEnd = surfaceGradientEnd,
			iconGradientStart = iconGradientStart,
			iconGradientEnd = iconGradientEnd,
			accentGradientStart = primary,
			accentGradientMiddle = accentGradientMiddle,
			accentGradientEnd = accentGradientEnd,
			activeGradientStart = activeGradientStart,
			activeGradientEnd = activeGradientEnd,
			gradientStrength = gradientStrength,
			error = rankThemeTokens?.errorColor?.toComposeColor() ?: colorScheme.error,
			warning = rankThemeTokens?.warningColor?.toComposeColor() ?: Color(0xFFF9A825),
			success = rankThemeTokens?.successColor?.toComposeColor() ?: Color(0xFF2E7D32),
			destructive = rankThemeTokens?.destructiveColor?.toComposeColor() ?: colorScheme.error,
			disabled = rankThemeTokens?.disabledColor?.toComposeColor()
				?: colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
			focusIndicator = rankThemeTokens?.focusIndicatorColor?.toComposeColor() ?: colorScheme.primary,
			adaptiveCustomBackground = adaptivePalette != null && rankThemeTokens == null,
			rankThemeId = rankThemeId,
			rankBorderGradient = rankBorderGradient,
			rankSelectedGradient = rankSelectedGradient,
		),
	)
}

fun classicMiyorareVisualPalette(
	colorScheme: ColorScheme,
	effectLevel: VisualEffectLevel,
): MiyorareVisualPalette = MiyorareVisualPalette(
	isModern = false,
	effectLevel = effectLevel,
	primary = colorScheme.primary,
	secondary = colorScheme.secondary,
	accent = colorScheme.tertiary,
	selectedSurface = colorScheme.primaryContainer,
	border = colorScheme.outline,
	borderHighlight = Color.Transparent,
	glow = Color.Transparent,
	chip = colorScheme.secondaryContainer,
	button = colorScheme.primary,
	onButton = colorScheme.onPrimary,
	backgroundGradientStart = colorScheme.background,
	backgroundGradientMiddle = colorScheme.background,
	backgroundGradientEnd = colorScheme.background,
	surfaceGradientStart = colorScheme.surfaceContainer,
	surfaceGradientMiddle = colorScheme.surfaceContainer,
	surfaceGradientEnd = colorScheme.surfaceContainer,
	iconGradientStart = colorScheme.primaryContainer,
	iconGradientEnd = colorScheme.primaryContainer,
	accentGradientStart = colorScheme.primary,
	accentGradientMiddle = colorScheme.primary,
	accentGradientEnd = colorScheme.primary,
	activeGradientStart = colorScheme.primary,
	activeGradientEnd = colorScheme.primary,
	gradientStrength = 0f,
	error = colorScheme.error,
	warning = Color(0xFFF9A825),
	success = Color(0xFF2E7D32),
	destructive = colorScheme.error,
	disabled = colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
	focusIndicator = colorScheme.primary,
)

private fun Long.toComposeColor(): Color = Color(toInt())

private fun deriveCustomPaletteSeeds(primary: Color): PaletteSeeds {
	val hsl = primary.toHsl()
	val secondary = hslColor(
		hue = hsl.hue + 34f,
		saturation = maxOf(hsl.saturation * 0.92f, 0.45f),
		lightness = (hsl.lightness + 0.07f).coerceAtMost(0.72f),
	)
	val accent = hslColor(
		hue = hsl.hue - 42f,
		saturation = maxOf(hsl.saturation * 0.86f, 0.50f),
		lightness = (hsl.lightness + 0.11f).coerceAtMost(0.78f),
	)
	return PaletteSeeds(primary = primary, secondary = secondary, accent = accent)
}

private data class HslColor(
	val hue: Float,
	val saturation: Float,
	val lightness: Float,
)

private fun Color.toHsl(): HslColor {
	val max = maxOf(red, green, blue)
	val min = minOf(red, green, blue)
	val delta = max - min
	val lightness = (max + min) / 2f
	if (delta == 0f) return HslColor(0f, 0f, lightness)

	val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
	val hue = when (max) {
		red -> 60f * (((green - blue) / delta) % 6f)
		green -> 60f * (((blue - red) / delta) + 2f)
		else -> 60f * (((red - green) / delta) + 4f)
	}.let { if (it < 0f) it + 360f else it }
	return HslColor(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
}

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
	val normalizedHue = ((hue % 360f) + 360f) % 360f
	val s = saturation.coerceIn(0f, 1f)
	val l = lightness.coerceIn(0f, 1f)
	val chroma = (1f - kotlin.math.abs(2f * l - 1f)) * s
	val x = chroma * (1f - kotlin.math.abs((normalizedHue / 60f) % 2f - 1f))
	val m = l - chroma / 2f
	val (r1, g1, b1) = when {
		normalizedHue < 60f -> Triple(chroma, x, 0f)
		normalizedHue < 120f -> Triple(x, chroma, 0f)
		normalizedHue < 180f -> Triple(0f, chroma, x)
		normalizedHue < 240f -> Triple(0f, x, chroma)
		normalizedHue < 300f -> Triple(x, 0f, chroma)
		else -> Triple(chroma, 0f, x)
	}
	return Color(
		red = (r1 + m).coerceIn(0f, 1f),
		green = (g1 + m).coerceIn(0f, 1f),
		blue = (b1 + m).coerceIn(0f, 1f),
		alpha = 1f,
	)
}

private fun ensureVisibleAgainst(accent: Color, surface: Color, darkTheme: Boolean): Color {
	var candidate = accent
	val target = if (darkTheme) Color.White else Color.Black
	repeat(8) {
		if (contrastRatio(candidate, surface) >= 3f) return candidate
		candidate = lerp(candidate, target, 0.16f)
	}
	return candidate
}

private fun bestContentColor(background: Color): Color {
	val dark = Color(0xFF0A0A10)
	return if (contrastRatio(Color.White, background) >= contrastRatio(dark, background)) Color.White else dark
}

private fun contrastRatio(first: Color, second: Color): Float {
	val lighter = maxOf(first.luminance(), second.luminance())
	val darker = minOf(first.luminance(), second.luminance())
	return (lighter + 0.05f) / (darker + 0.05f)
}
