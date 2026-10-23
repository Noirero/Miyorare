package org.koitharu.kotatsu.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

class MiyorareCustomAccentNeutralTest {

	@Test
	fun `neutral custom accents stay achromatic in light and dark palettes`() {
		for (accent in listOf("#000000", "#FFFFFF", "#808080")) {
			for (dark in listOf(false, true)) {
				val scheme = customScheme(accent, dark)
				assertNearNeutral("$accent primary dark=$dark", scheme.primary, 0.001f)
				assertNearNeutral("$accent secondary dark=$dark", scheme.secondary, 0.001f)
				assertNearNeutral("$accent tertiary dark=$dark", scheme.tertiary, 0.001f)
			}
		}
	}

	@Test
	fun `device tested near neutral accent does not gain a saturated companion hue`() {
		for (dark in listOf(false, true)) {
			val scheme = customScheme("#A19292", dark)
			assertNearNeutral("#A19292 primary dark=$dark", scheme.primary, 0.07f)
			assertNearNeutral("#A19292 secondary dark=$dark", scheme.secondary, 0.001f)
			assertNearNeutral("#A19292 tertiary dark=$dark", scheme.tertiary, 0.001f)
		}
	}

	@Test
	fun `chromatic custom accents keep chromatic companions`() {
		for (accent in listOf("#FC0000", "#0006FC")) {
			val scheme = customScheme(accent, dark = false)
			assertTrue("$accent primary should remain chromatic", channelSpread(scheme.primary) > 0.10f)
			assertTrue("$accent secondary should remain chromatic", channelSpread(scheme.secondary) > 0.10f)
			assertTrue("$accent tertiary should remain chromatic", channelSpread(scheme.tertiary) > 0.10f)
		}
	}

	@Test
	fun `custom identity colour only overrides Match Theme palette`() {
		assertTrue(
			shouldUseMiyorareCustomAccentIdentity(
				MiyorareModernPaletteSource.MATCH_THEME,
				MiyorareThemePreset.CUSTOM,
			),
		)
		assertFalse(
			shouldUseMiyorareCustomAccentIdentity(
				MiyorareModernPaletteSource.CLASSIC_MIKU,
				MiyorareThemePreset.CUSTOM,
			),
		)
		assertFalse(
			shouldUseMiyorareCustomAccentIdentity(
				MiyorareModernPaletteSource.MATCH_THEME,
				MiyorareThemePreset.MIYORARE,
			),
		)
	}

	private fun customScheme(accent: String, dark: Boolean) = miyorareThemeColors(
		preset = MiyorareThemePreset.CUSTOM,
		customAccent = accent,
		darkTheme = dark,
		amoled = false,
		effectLevel = VisualEffectLevel.FULL,
	).colorScheme

	private fun assertNearNeutral(label: String, color: Color, maxSpread: Float) {
		assertTrue("$label unexpectedly gained hue: $color", channelSpread(color) <= maxSpread)
	}

	private fun channelSpread(color: Color): Float =
		maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)
}
