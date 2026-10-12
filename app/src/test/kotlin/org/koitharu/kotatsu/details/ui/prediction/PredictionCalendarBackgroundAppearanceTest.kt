package org.koitharu.kotatsu.details.ui.prediction

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
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

	@Test fun `image scrim preserves primary and secondary text contrast on light and dark image regions`() {
		val schemes = listOf(lightColorScheme(), darkColorScheme()) + MiyorareThemePreset.entries.flatMap { preset ->
			listOf(false, true).flatMap { dark ->
				VisualEffectLevel.entries.map { effect ->
					miyorareThemeColors(preset, "#5B6CFF", darkTheme = dark, amoled = false, effectLevel = effect).colorScheme
				}
			}
		} + listOf(miyorareThemeColors(MiyorareThemePreset.MIYORARE, "#5B6CFF", darkTheme = true,
			amoled = true, effectLevel = VisualEffectLevel.BALANCED).colorScheme)
		for (scheme in schemes) for (image in listOf(Color.Black, Color.White)) {
			val background = scheme.surface.copy(alpha = PREDICTION_BACKGROUND_SCRIM_ALPHA).compositeOver(image)
			for (foreground in listOf(scheme.onSurface, scheme.onSurfaceVariant)) {
				val contrast = (maxOf(foreground.luminance(), background.luminance()) + 0.05f) /
					(minOf(foreground.luminance(), background.luminance()) + 0.05f)
				assertTrue("Foreground $foreground over $background has contrast $contrast", contrast >= 4.5f)
			}
		}
	}
}
