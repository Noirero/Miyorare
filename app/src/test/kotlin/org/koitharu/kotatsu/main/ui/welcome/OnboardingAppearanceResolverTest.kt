package org.koitharu.kotatsu.main.ui.welcome

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.resolveMiyorareThemeColors

/**
 * The onboarding preview is under MiyorareTheme and consumes its Material and visual locals.
 * These tests pin the canonical resolver's actual Modern palette distinctions.
 */
class OnboardingAppearanceResolverTest {

    private val presets = MiyorareThemePreset.entries.filter { it != MiyorareThemePreset.CUSTOM }

    @Test fun curatedPresetsHaveDistinctResolvedPrimarySecondaryAndBackground() {
        assertEquals(6, presets.size)
        for (dark in listOf(false, true)) {
            val schemes = presets.map { preset ->
                resolveMiyorareThemeColors(
                    paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
                    preset = preset,
                    customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
                    darkTheme = dark,
                    amoled = false,
                    effectLevel = VisualEffectLevel.BALANCED,
                ).colorScheme
            }
            assertEquals("primary dark=$dark", presets.size, schemes.map { it.primary }.distinct().size)
            assertEquals("secondary dark=$dark", presets.size, schemes.map { it.secondary }.distinct().size)
            // The canonical renderer deliberately keeps a near-neutral background foundation;
            // distinct seeds can resolve to an identical foundation shade.
            assertTrue(
                "background has at least two resolved variants dark=$dark",
                schemes.map { it.background }.distinct().size >= 2,
            )
        }
    }

    @Test fun modernAmoledResolvesSameBlackFoundationAsMainTheme() {
        val standard = resolveMiyorareThemeColors(
            paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
            preset = MiyorareThemePreset.VIOLET,
            customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
            darkTheme = true,
            amoled = false,
            effectLevel = VisualEffectLevel.BALANCED,
        )
        val amoled = resolveMiyorareThemeColors(
            paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
            preset = MiyorareThemePreset.VIOLET,
            customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
            darkTheme = true,
            amoled = true,
            effectLevel = VisualEffectLevel.BALANCED,
        )
        assertNotEquals(Color.Black, standard.colorScheme.background)
        assertEquals(Color.Black, amoled.colorScheme.background)
        assertEquals(Color.Black, amoled.visualPalette.backgroundGradientStart)
    }

    @Test fun paletteSourceAvailabilityAndStoredIdentityStayCanonical() {
        val sources = MiyorareModernPaletteSource.getAvailableList()
        assertEquals(MiyorareModernPaletteSource.MATCH_THEME, sources.first())
        for (source in sources) {
            assertEquals(source, MiyorareModernPaletteSource.fromStoredValue(source.name))
        }
    }
}
