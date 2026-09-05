package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

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
	)
}

/**
 * Derives one stable Material palette and semantic gradient token set from the selected accent.
 * Everything here is finite color math remembered by the theme caller: no continuous shader,
 * per-list-item animation or data/runtime behavior is introduced.
 */
fun miyorareThemeColors(
	preset: MiyorareThemePreset,
	customAccent: String,
	darkTheme: Boolean,
	amoled: Boolean,
	effectLevel: VisualEffectLevel,
): MiyorareThemeColors {
	val requestedArgb = if (preset == MiyorareThemePreset.CUSTOM) {
		MiyorareAppearance.parseAccentArgb(customAccent) ?: MiyorareThemePreset.MIYORARE.accentArgb
	} else {
		preset.accentArgb
	}
	val useAmoled = darkTheme && amoled
	val baseSurface = when {
		useAmoled -> Color.Black
		darkTheme -> Color(0xFF0F1220)
		else -> Color.White
	}
	val primary = ensureVisibleAgainst(Color(requestedArgb), baseSurface, darkTheme)
	val secondary = ensureVisibleAgainst(lerp(primary, Color(0xFF00D4FF), 0.52f), baseSurface, darkTheme)
	val accent = ensureVisibleAgainst(lerp(primary, Color(0xFFFF5CC8), 0.48f), baseSurface, darkTheme)
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
		val background = if (useAmoled) Color.Black else lerp(Color(0xFF080B14), primary, tint * 0.08f)
		val surface = if (useAmoled) Color.Black else lerp(Color(0xFF0F1220), primary, tint * 0.12f)
		val surfaceVariant = lerp(Color(0xFF20263A), primary, tint * 0.20f)
		selectedSurface = lerp(Color(0xFF211B3B), primary, 0.28f + tint * 0.22f)
		chip = lerp(Color(0xFF171C31), secondary, 0.10f + tint * 0.18f)
		border = lerp(Color(0xFF70758C), primary, tint * 0.28f)
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
			onBackground = Color(0xFFF7F6FF),
			surface = surface,
			onSurface = Color(0xFFF5F3FF),
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = Color(0xFFCBC7D8),
			outline = border,
			outlineVariant = lerp(Color(0xFF343A52), primary, tint * 0.14f),
			surfaceContainer = lerp(Color(0xFF121729), primary, tint * 0.12f),
			surfaceContainerHigh = lerp(Color(0xFF181E34), primary, tint * 0.18f),
		)
	} else {
		val background = lerp(Color(0xFFF8F7FC), primary, tint * 0.05f)
		val surface = lerp(baseSurface, primary, tint * 0.04f)
		val surfaceVariant = lerp(Color(0xFFE8E5F0), primary, tint * 0.12f)
		selectedSurface = lerp(Color(0xFFEEE9FF), primary, 0.12f + tint * 0.18f)
		chip = lerp(Color(0xFFF0F4FA), secondary, 0.07f + tint * 0.14f)
		border = lerp(Color(0xFF81798D), primary, tint * 0.18f)
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
			onBackground = Color(0xFF17131F),
			surface = surface,
			onSurface = Color(0xFF1B1724),
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = Color(0xFF625B70),
			outline = border,
			outlineVariant = lerp(Color(0xFFD7D0E0), primary, tint * 0.12f),
			surfaceContainer = lerp(Color(0xFFF3F0F9), primary, tint * 0.08f),
			surfaceContainerHigh = lerp(Color(0xFFECE8F5), primary, tint * 0.13f),
		)
	}

	val backgroundGradientStart: Color
	val backgroundGradientMiddle: Color
	val backgroundGradientEnd: Color
	if (useAmoled) {
		backgroundGradientStart = Color.Black
		backgroundGradientMiddle = Color.Black
		backgroundGradientEnd = Color.Black
	} else {
		backgroundGradientStart = lerp(colorScheme.background, primary, gradientStrength * 0.22f)
		backgroundGradientMiddle = lerp(colorScheme.background, secondary, gradientStrength * 0.12f)
		backgroundGradientEnd = lerp(colorScheme.background, accent, gradientStrength * 0.18f)
	}
	val surfaceGradientStart = lerp(
		colorScheme.surfaceContainer,
		primary,
		gradientStrength * MiyorareVisualTokens.SURFACE_GRADIENT_MIX,
	)
	val surfaceGradientMiddle = lerp(
		colorScheme.surfaceContainerHigh,
		accent,
		gradientStrength * 0.10f,
	)
	val surfaceGradientEnd = lerp(
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
	val activeGradientStart = lerp(primary, secondary, gradientStrength * MiyorareVisualTokens.ACTIVE_GRADIENT_MIX)
	val activeGradientEnd = lerp(secondary, accent, 0.08f + gradientStrength * 0.44f)
	val borderHighlight = lerp(border, secondary, 0.22f + gradientStrength * 0.26f).copy(alpha = borderAlpha)
	val glow = lerp(primary, secondary, 0.34f).copy(alpha = glowAlpha)

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
)

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
	val dark = Color(0xFF0A1020)
	return if (contrastRatio(Color.White, background) >= contrastRatio(dark, background)) Color.White else dark
}

private fun contrastRatio(first: Color, second: Color): Float {
	val lighter = maxOf(first.luminance(), second.luminance())
	val darker = minOf(first.luminance(), second.luminance())
	return (lighter + 0.05f) / (darker + 0.05f)
}
