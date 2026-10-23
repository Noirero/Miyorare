package org.koitharu.kotatsu.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

class MiyorarePaletteSourceFoundationTest {

	@Test
	fun `stored palette source defaults and falls back to match theme`() {
		assertEquals(
			MiyorareModernPaletteSource.MATCH_THEME,
			MiyorareModernPaletteSource.fromStoredValue(null),
		)
		assertEquals(
			MiyorareModernPaletteSource.MATCH_THEME,
			MiyorareModernPaletteSource.fromStoredValue("UNKNOWN_FUTURE_OR_REMOVED_VALUE"),
		)
		assertEquals(
			MiyorareModernPaletteSource.MATCH_THEME,
			MiyorareModernPaletteSource.fromStoredValue(MiyorareModernPaletteSource.MATCH_THEME.name),
		)
	}

	@Test
	fun `authored Modern preset seed identities stay frozen`() {
		val expected = mapOf(
			MiyorareThemePreset.MIYORARE to Triple(0xFF5B6CFF.toInt(), 0xFF20C9E8.toInt(), 0xFFFF5CC8.toInt()),
			MiyorareThemePreset.SAKURA to Triple(0xFFE85D9E.toInt(), 0xFFF58AAB.toInt(), 0xFFFFB38A.toInt()),
			MiyorareThemePreset.VIOLET to Triple(0xFF7C5CFF.toInt(), 0xFFA56EFF.toInt(), 0xFFD994FF.toInt()),
			MiyorareThemePreset.CYAN to Triple(0xFF16AFC4.toInt(), 0xFF35CFE2.toInt(), 0xFF42A5FF.toInt()),
			MiyorareThemePreset.EMERALD to Triple(0xFF2FA97D.toInt(), 0xFF39C7A0.toInt(), 0xFF72E6BB.toInt()),
			MiyorareThemePreset.AMBER to Triple(0xFFC47B1C.toInt(), 0xFFE9A62A.toInt(), 0xFFFFCC59.toInt()),
		)

		for ((preset, seeds) in expected) {
			assertEquals("$preset primary seed changed", seeds.first, preset.accentArgb)
			assertEquals("$preset secondary seed changed", seeds.second, preset.secondaryArgb)
			assertEquals("$preset tertiary seed changed", seeds.third, preset.tertiaryArgb)
		}
	}

	@Test
	fun `match theme resolver preserves authored renderer output`() {
		for (preset in MiyorareThemePreset.entries) {
			for (dark in listOf(false, true)) {
				val legacy = miyorareThemeColors(
					preset = preset,
					customAccent = "#A19292",
					darkTheme = dark,
					amoled = dark,
					effectLevel = VisualEffectLevel.BALANCED,
				)
				val resolved = resolveMiyorareThemeColors(
					paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
					preset = preset,
					customAccent = "#A19292",
					darkTheme = dark,
					amoled = dark,
					effectLevel = VisualEffectLevel.BALANCED,
				)

				assertEquals("$preset primary dark=$dark", legacy.colorScheme.primary, resolved.colorScheme.primary)
				assertEquals("$preset secondary dark=$dark", legacy.colorScheme.secondary, resolved.colorScheme.secondary)
				assertEquals("$preset tertiary dark=$dark", legacy.colorScheme.tertiary, resolved.colorScheme.tertiary)
				assertEquals("$preset background dark=$dark", legacy.colorScheme.background, resolved.colorScheme.background)
				assertEquals("$preset surface dark=$dark", legacy.colorScheme.surface, resolved.colorScheme.surface)
				assertEquals(
					"$preset selected surface dark=$dark",
					legacy.visualPalette.selectedSurface,
					resolved.visualPalette.selectedSurface,
				)
				assertEquals(
					"$preset gradient start dark=$dark",
					legacy.visualPalette.backgroundGradientStart,
					resolved.visualPalette.backgroundGradientStart,
				)
			}
		}
	}
}
