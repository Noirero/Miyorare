package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Completes the Material semantic role set without changing the authored Modern core palette.
 * Existing primary/secondary/tertiary, background/surface, authored containers, outlines, and all
 * decorative tokens remain owned by [miyorareThemeColors]. This layer only fills roles that would
 * otherwise fall back to generic Material defaults.
 */
internal fun MiyorareThemeColors.withCompleteSemanticRoles(
	darkTheme: Boolean,
	amoled: Boolean,
	fixedReference: ColorScheme,
): MiyorareThemeColors {
	val scheme = colorScheme
	val inverseSurface = if (darkTheme) {
		lerp(Color(0xFFF2EEF4), fixedReference.primary, 0.025f)
	} else {
		lerp(Color(0xFF2E2B31), fixedReference.primary, 0.035f)
	}
	val inverseOnSurface = bestSemanticContentColor(inverseSurface)
	val inversePrimary = ensureSemanticContrast(
		color = fixedReference.primary,
		background = inverseSurface,
		minimumRatio = 3f,
	)
	val tertiaryContainer = if (darkTheme) {
		lerp(scheme.surfaceContainerHigh, scheme.tertiary, 0.24f)
	} else {
		lerp(scheme.surfaceContainer, scheme.tertiary, 0.13f)
	}
	val onTertiaryContainer = bestSemanticContentColor(tertiaryContainer)

	val surfaceDim: Color
	val surfaceBright: Color
	val surfaceContainerLowest: Color
	val surfaceContainerLow: Color
	val surfaceContainerHighest: Color
	if (darkTheme) {
		surfaceDim = if (amoled) Color.Black else scheme.background
		surfaceBright = if (amoled) {
			Color(0xFF181818)
		} else {
			lerp(scheme.surfaceContainerHigh, Color.White, 0.12f)
		}
		surfaceContainerLowest = if (amoled) {
			Color.Black
		} else {
			lerp(scheme.background, Color.Black, 0.18f)
		}
		surfaceContainerLow = if (amoled) {
			Color.Black
		} else {
			lerp(scheme.surface, scheme.surfaceContainer, 0.45f)
		}
		surfaceContainerHighest = if (amoled) {
			Color(0xFF101010)
		} else {
			lerp(scheme.surfaceContainerHigh, Color.White, 0.055f)
		}
	} else {
		surfaceDim = lerp(scheme.surfaceContainerHigh, Color.Black, 0.06f)
		surfaceBright = lerp(scheme.surface, Color.White, 0.72f)
		surfaceContainerLowest = lerp(scheme.surface, Color.White, 0.88f)
		surfaceContainerLow = lerp(scheme.surface, scheme.surfaceContainer, 0.45f)
		surfaceContainerHighest = lerp(scheme.surfaceContainerHigh, Color.Black, 0.055f)
	}

	val error = if (darkTheme) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
	val onError = if (darkTheme) Color(0xFF690005) else Color.White
	val errorContainer = if (darkTheme) Color(0xFF93000A) else Color(0xFFFFDAD6)
	val onErrorContainer = if (darkTheme) Color(0xFFFFDAD6) else Color(0xFF410002)

	val primaryFixed = fixedSemanticRoles(fixedReference.primary)
	val secondaryFixed = fixedSemanticRoles(fixedReference.secondary)
	val tertiaryFixed = fixedSemanticRoles(fixedReference.tertiary)

	return copy(
		colorScheme = scheme.copy(
			inversePrimary = inversePrimary,
			tertiaryContainer = tertiaryContainer,
			onTertiaryContainer = onTertiaryContainer,
			surfaceTint = scheme.primary,
			inverseSurface = inverseSurface,
			inverseOnSurface = inverseOnSurface,
			error = error,
			onError = onError,
			errorContainer = errorContainer,
			onErrorContainer = onErrorContainer,
			scrim = Color.Black,
			surfaceBright = surfaceBright,
			surfaceDim = surfaceDim,
			surfaceContainerLowest = surfaceContainerLowest,
			surfaceContainerLow = surfaceContainerLow,
			surfaceContainerHighest = surfaceContainerHighest,
			primaryFixed = primaryFixed.fixed,
			primaryFixedDim = primaryFixed.fixedDim,
			onPrimaryFixed = primaryFixed.onFixed,
			onPrimaryFixedVariant = primaryFixed.onFixedVariant,
			secondaryFixed = secondaryFixed.fixed,
			secondaryFixedDim = secondaryFixed.fixedDim,
			onSecondaryFixed = secondaryFixed.onFixed,
			onSecondaryFixedVariant = secondaryFixed.onFixedVariant,
			tertiaryFixed = tertiaryFixed.fixed,
			tertiaryFixedDim = tertiaryFixed.fixedDim,
			onTertiaryFixed = tertiaryFixed.onFixed,
			onTertiaryFixedVariant = tertiaryFixed.onFixedVariant,
		),
	)
}

private data class FixedSemanticRoles(
	val fixed: Color,
	val fixedDim: Color,
	val onFixed: Color,
	val onFixedVariant: Color,
)

private fun fixedSemanticRoles(seed: Color): FixedSemanticRoles {
	val fixed = lerp(Color.White, seed, 0.30f)
	val fixedDim = lerp(Color.White, seed, 0.50f)
	return FixedSemanticRoles(
		fixed = fixed,
		fixedDim = fixedDim,
		onFixed = bestSemanticContentColor(fixed),
		onFixedVariant = bestSemanticContentColor(fixedDim),
	)
}

private fun bestSemanticContentColor(background: Color): Color {
	val dark = Color(0xFF0A0A10)
	return if (semanticContrastRatio(Color.White, background) >= semanticContrastRatio(dark, background)) {
		Color.White
	} else {
		dark
	}
}

private fun ensureSemanticContrast(
	color: Color,
	background: Color,
	minimumRatio: Float,
): Color {
	var candidate = color
	val target = if (background.luminance() >= 0.5f) Color.Black else Color.White
	repeat(8) {
		if (semanticContrastRatio(candidate, background) >= minimumRatio) return candidate
		candidate = lerp(candidate, target, 0.16f)
	}
	return candidate
}

internal fun semanticContrastRatio(first: Color, second: Color): Float {
	val lighter = maxOf(first.luminance(), second.luminance())
	val darker = minOf(first.luminance(), second.luminance())
	return (lighter + 0.05f) / (darker + 0.05f)
}
