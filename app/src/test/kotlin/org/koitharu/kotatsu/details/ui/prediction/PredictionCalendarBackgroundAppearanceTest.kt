package org.koitharu.kotatsu.details.ui.prediction

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.miyorareThemeColors

class PredictionCalendarBackgroundAppearanceTest {
	@Test fun `cover blur preference cannot blur custom images or default backgrounds`() {
		for (enabled in listOf(false, true)) {
			assertEquals(enabled, PredictionCalendarBackgroundMode.MANGA_COVER.shouldBlur(enabled))
			assertEquals(false, PredictionCalendarBackgroundMode.CUSTOM.shouldBlur(enabled))
			assertEquals(false, PredictionCalendarBackgroundMode.DEFAULT.shouldBlur(enabled))
		}
	}

	@Test fun `default background retains the host theme without image styling`() {
		for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
			assertSame(scheme, predictionCalendarColorScheme(scheme, imageBackground = false))
		}
	}

	@Test fun `image scrim preserves primary and secondary text contrast on light and dark image regions`() {
		val schemes = listOf(lightColorScheme(), darkColorScheme()) + MiyorareThemePreset.entries.flatMap { preset ->
			listOf(false, true).flatMap { dark ->
				VisualEffectLevel.entries.map { effect ->
					miyorareThemeColors(preset, "#5B6CFF", darkTheme = dark, amoled = false, effectLevel = effect).colorScheme
				}
			}
		} + listOf(miyorareThemeColors(MiyorareThemePreset.MIYORARE, "#5B6CFF", darkTheme = true,
			amoled = true, effectLevel = VisualEffectLevel.BALANCED).colorScheme)
		for (base in schemes) for (image in listOf(Color.Black, Color.White)) {
			val scheme = predictionCalendarColorScheme(base, imageBackground = true)
			assertTrue(scheme.surface.luminance() < 0.1f)
			val backdrop = scheme.scrim.copy(alpha = PREDICTION_BACKGROUND_SCRIM_ALPHA).compositeOver(image)
			val calendar = scheme.surface.copy(alpha = 0.76f).compositeOver(backdrop)
			for (background in listOf(backdrop, calendar)) {
				for (foreground in listOf(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, scheme.secondary, scheme.tertiary)) {
					assertTrue("Foreground $foreground over $background", contrast(foreground, background) >= 4.5f)
				}
				for (border in listOf(scheme.outline, scheme.outlineVariant)) {
					assertTrue("Border/indicator $border over $background", contrast(border.compositeOver(background), background) >= 3f)
				}
			}
			assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
			val detailsCard = scheme.primaryContainer.copy(alpha = 0.55f).compositeOver(scheme.surfaceContainerHigh)
			assertTrue(contrast(scheme.onPrimaryContainer, detailsCard) >= 4.5f)
		}
	}

	private fun contrast(first: Color, second: Color) =
		(maxOf(first.luminance(), second.luminance()) + 0.05f) / (minOf(first.luminance(), second.luminance()) + 0.05f)
}
