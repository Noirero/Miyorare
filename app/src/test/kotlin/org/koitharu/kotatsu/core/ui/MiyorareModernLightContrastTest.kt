package org.koitharu.kotatsu.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

class MiyorareModernLightContrastTest {

	@Test
	fun `Modern Light accent labels use readable semantic foregrounds`() {
		for (preset in MiyorareThemePreset.entries) {
			for (effect in VisualEffectLevel.entries) {
				val scheme = resolveMiyorareThemeColors(
					paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
					preset = preset,
					customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
					darkTheme = false,
					amoled = false,
					effectLevel = effect,
				).colorScheme

				val emphasized = miyorareReadableAccentContentColor(
					colorScheme = scheme,
					preferredAccent = scheme.primary,
					lightSurface = true,
					emphasized = true,
				)
				val secondary = miyorareReadableAccentContentColor(
					colorScheme = scheme,
					preferredAccent = scheme.secondary,
					lightSurface = true,
					emphasized = false,
				)

				assertEquals("$preset $effect emphasized", scheme.onSurface, emphasized)
				assertEquals("$preset $effect secondary", scheme.onSurfaceVariant, secondary)
				assertContrast("$preset $effect emphasized/surfaceContainer", emphasized, scheme.surfaceContainer)
				assertContrast("$preset $effect emphasized/surfaceContainerHigh", emphasized, scheme.surfaceContainerHigh)
				assertContrast("$preset $effect secondary/surfaceContainer", secondary, scheme.surfaceContainer)
				assertContrast("$preset $effect secondary/surfaceContainerHigh", secondary, scheme.surfaceContainerHigh)
			}
		}
	}

	@Test
	fun `dark surfaces preserve authored accent foreground`() {
		val scheme = resolveMiyorareThemeColors(
			paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
			preset = MiyorareThemePreset.SAKURA,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = false,
			effectLevel = VisualEffectLevel.BALANCED,
		).colorScheme
		val preferred = scheme.primary

		assertEquals(
			preferred,
			miyorareReadableAccentContentColor(
				colorScheme = scheme,
				preferredAccent = preferred,
				lightSurface = false,
				emphasized = true,
			),
		)
	}

	private fun assertContrast(label: String, foreground: Color, background: Color) {
		val ratio = contrastRatio(foreground, background)
		assertTrue("$label contrast $ratio < 4.5", ratio >= 4.5f)
	}

	private fun contrastRatio(first: Color, second: Color): Float {
		val lighter = maxOf(first.luminance(), second.luminance())
		val darker = minOf(first.luminance(), second.luminance())
		return (lighter + 0.05f) / (darker + 0.05f)
	}
}
