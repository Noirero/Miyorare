package org.koitharu.kotatsu.settings

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareSettingsIconStyle
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.classicMiyorareVisualPalette
import org.koitharu.kotatsu.settings.compose.forSettingsIconSurface

class SettingsIconStyleRegressionTest {
	@Test fun followThemePreservesLegacySettingsIconTreatment() {
		assertFalse(MiyorareSettingsIconStyle.FOLLOW_THEME.usesModernIcons(false))
		assertTrue(MiyorareSettingsIconStyle.FOLLOW_THEME.usesModernIcons(true))
	}

	@Test fun forcedStylesAreIndependentOfTheme() {
		for (modern in listOf(false, true)) {
			assertTrue(MiyorareSettingsIconStyle.MIYORARE.usesModernIcons(modern))
			assertFalse(MiyorareSettingsIconStyle.MATERIAL_3.usesModernIcons(modern))
		}
	}

	@Test fun manualModernIconsHaveVisibleSurfaceEvenWithClassicTheme() {
		for (colors in listOf(lightColorScheme(), darkColorScheme())) {
			val classicPalette = classicMiyorareVisualPalette(colors, VisualEffectLevel.BALANCED)
			assertFalse(classicPalette.isModern)
			val forced = classicPalette.forSettingsIconSurface(colors)
			assertTrue(forced.isModern)
			assertEquals(colors.primaryContainer, forced.iconGradientStart)
			assertEquals(colors.secondaryContainer, forced.iconGradientEnd)
			assertEquals(colors.outlineVariant, forced.borderHighlight)
		}
	}

	@Test fun customIsNotAnIncompletePersistedChoice() {
		assertEquals(listOf("FOLLOW_THEME", "MIYORARE", "MATERIAL_3"), MiyorareSettingsIconStyle.entries.map { it.name })
	}
}
