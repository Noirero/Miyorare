package org.koitharu.kotatsu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.settings.compose.PrivateSettingsThemeSnapshot
import org.koitharu.kotatsu.settings.compose.SanctuaryAssets
import org.koitharu.kotatsu.settings.compose.sanctuaryAssets
import org.koitharu.kotatsu.settings.compose.sanctuaryEnabled

class PrivateSanctuaryTreatmentTest {
	@Test
	fun mapsAllExplicitPresetsToTheirOwnApprovedResourcePair() {
		val expected = mapOf(
			PrivateFavouritesThemePreset.MIYORARE to SanctuaryAssets(R.drawable.private_sanctuary_ornament_miyorare, R.drawable.private_sanctuary_landscape_miyorare),
			PrivateFavouritesThemePreset.SAKURA to SanctuaryAssets(R.drawable.private_sanctuary_ornament_sakura, R.drawable.private_sanctuary_landscape_sakura),
			PrivateFavouritesThemePreset.VIOLET to SanctuaryAssets(R.drawable.private_sanctuary_ornament_violet, R.drawable.private_sanctuary_landscape_violet),
			PrivateFavouritesThemePreset.CYAN to SanctuaryAssets(R.drawable.private_sanctuary_ornament_cyan, R.drawable.private_sanctuary_landscape_cyan),
			PrivateFavouritesThemePreset.EMERALD to SanctuaryAssets(R.drawable.private_sanctuary_ornament_emerald, R.drawable.private_sanctuary_landscape_emerald),
			PrivateFavouritesThemePreset.AMBER to SanctuaryAssets(R.drawable.private_sanctuary_ornament_amber, R.drawable.private_sanctuary_landscape_amber),
		)
		assertEquals(PrivateFavouritesThemePreset.entries.size - 1, expected.size)
		expected.forEach { (theme, pair) -> assertEquals(pair, sanctuaryAssets(theme)) }
		assertNull(sanctuaryAssets(PrivateFavouritesThemePreset.FOLLOW_NORMAL))
	}

	@Test
	fun activationRequiresActualSettingsModernAndExplicitTheme() {
		for (theme in PrivateFavouritesThemePreset.entries) {
			val snapshot = PrivateSettingsThemeSnapshot(theme, true, VisualEffectLevel.BALANCED)
			assertEquals(theme != PrivateFavouritesThemePreset.FOLLOW_NORMAL, sanctuaryEnabled(snapshot, true))
			assertFalse(sanctuaryEnabled(snapshot, false))
			assertFalse(sanctuaryEnabled(snapshot.copy(modern = false), true))
		}
		assertFalse(sanctuaryEnabled(null, true))
	}
}
