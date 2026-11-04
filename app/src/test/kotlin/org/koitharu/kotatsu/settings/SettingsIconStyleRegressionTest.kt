package org.koitharu.kotatsu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareSettingsIconStyle

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

	@Test fun customIsNotAnIncompletePersistedChoice() {
		assertEquals(listOf("FOLLOW_THEME", "MIYORARE", "MATERIAL_3"), MiyorareSettingsIconStyle.entries.map { it.name })
	}
}
