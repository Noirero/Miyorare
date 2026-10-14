package org.koitharu.kotatsu.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

class MiyorareCustomAccentNeutralTest {

	@Test
	fun `neutral custom accents stay achromatic in light and dark palettes`() {
		for (accent in listOf("#000000", "#FFFFFF", "#808080", "#A19292")) {
			for (dark in listOf(false, true)) {
				val scheme = miyorareThemeColors(
					preset = MiyorareThemePreset.CUSTOM,
					customAccent = accent,
					darkTheme = dark,
					amoled = false,
					effectLevel = VisualEffectLevel.FULL,
				).colorScheme
				assertAchromatic("$accent primary dark=$dark", scheme.primary)
				assertAchromatic("$accent secondary dark=$dark", scheme.secondary)
				assertAchromatic("$accent tertiary dark=$dark", scheme.tertiary)
			}
		}
	}

	@Test
	fun `chromatic custom accents keep chromatic companions`() {
		for (accent in listOf("#FC0000", "#0006FC")) {
			val scheme = miyorareThemeColors(
				preset = MiyorareThemePreset.CUSTOM,
				customAccent = accent,
				darkTheme = false,
				amoled = false,
				effectLevel = VisualEffectLevel.FULL,
			).colorScheme
			assertTrue("$accent primary should remain chromatic", channelSpread(scheme.primary) > 0.10f)
			assertTrue("$accent secondary should remain chromatic", channelSpread(scheme.secondary) > 0.10f)
			assertTrue("$accent tertiary should remain chromatic", channelSpread(scheme.tertiary) > 0.10f)
		}
	}

	private fun assertAchromatic(label: String, color: Color) {
		assertTrue("$label unexpectedly gained hue: $color", channelSpread(color) <= 0.001f)
	}

	private fun channelSpread(color: Color): Float =
		maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)
}
