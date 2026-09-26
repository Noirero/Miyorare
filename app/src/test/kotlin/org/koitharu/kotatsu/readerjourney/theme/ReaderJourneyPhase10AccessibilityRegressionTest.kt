package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 10 guards for production accessibility controls and live theme application.
 *
 * These checks are deliberately source-level because they protect wiring across Compose, legacy
 * Views, Reader celebration, Settings, and Reader Journey hosts without introducing a second
 * presentation source of truth.
 */
class ReaderJourneyPhase10AccessibilityRegressionTest {

	@Test
	fun `rank theme accessibility preferences are local safe defaults`() {
		val source = source("kotlin/org/koitharu/kotatsu/core/prefs/AppSettings.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(source.contains("KEY_RANK_THEME_REDUCE_MOTION"))
		assertTrue(source.contains("KEY_RANK_THEME_REDUCE_GLOW"))
		assertTrue(source.contains("KEY_RANK_THEME_MINIMAL_COSMETICS"))
		assertTrue(source.contains("KEY_RANK_THEME_WALLPAPER_ENABLED"))
		assertTrue(source.contains("prefs.getBoolean(KEY_RANK_THEME_REDUCE_MOTION,false)"))
		assertTrue(source.contains("prefs.getBoolean(KEY_RANK_THEME_REDUCE_GLOW,false)"))
		assertTrue(source.contains("prefs.getBoolean(KEY_RANK_THEME_MINIMAL_COSMETICS,false)"))
		assertTrue(source.contains("prefs.getBoolean(KEY_RANK_THEME_WALLPAPER_ENABLED,true)"))
	}

	@Test
	fun `Appearance exposes all required Phase 10 cosmetic controls`() {
		val source = source("kotlin/org/koitharu/kotatsu/settings/AppearanceSettingsFragment.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(source.contains("rank_theme_reduce_motion"))
		assertTrue(source.contains("rank_theme_reduce_glow"))
		assertTrue(source.contains("rank_theme_minimal_cosmetics"))
		assertTrue(source.contains("rank_theme_wallpaper"))
		assertTrue(source.contains("enabled=!rankThemeMinimalCosmetics"))
	}

	@Test
	fun `Reduce Glow and Minimal Cosmetic affect Compose and legacy palettes`() {
		val compose = source("kotlin/org/koitharu/kotatsu/settings/compose/SettingsTheme.kt")
			.replace(Regex("\\s+"), "")
		val legacy = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareViewPalette.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(compose.contains("rankThemeReduceGlow||rankThemeMinimalCosmetics"))
		assertTrue(compose.contains("VisualEffectLevel.LIGHT"))
		assertTrue(legacy.contains("reduceRankThemeEffects=settings.isRankThemeReduceGlow||settings.isRankThemeMinimalCosmetics"))
		assertTrue(legacy.contains("resolvedExclusiveTheme!=null&&reduceRankThemeEffects"))
	}

	@Test
	fun `Reduce Motion suppresses Reader Journey celebration animation`() {
		val source = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderActivity.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(source.contains("!settings.isRankThemeReduceMotion"))
		assertTrue(source.contains("!settings.isRankThemeMinimalCosmetics"))
		assertTrue(source.contains("isAnimationsEnabled"))
	}

	@Test
	fun `Wallpaper and reduced glow are applied on production Reader Profile`() {
		val source = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(source.contains("KEY_RANK_THEME_WALLPAPER_ENABLED"))
		assertTrue(source.contains("rankThemeWallpaperEnabled&&!rankThemeMinimalCosmetics"))
		assertTrue(source.contains("if(rankThemeReduceGlow||rankThemeMinimalCosmetics)0felse"))
		assertTrue(source.contains("ReferenceRankThemeWallpaper("))
	}

	@Test
	fun `cosmetic apply refreshes legacy hosts after the atomic snapshot is saved`() {
		val journey = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyFragment.kt")
			.replace(Regex("\\s+"), "")
		val legacyStats = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(journey.contains("viewModel.updateReaderCosmetics(loadout)"))
		assertTrue(journey.contains("activityRecreationHandle.recreateAll()"))
		assertTrue(legacyStats.contains("viewModel.updateReaderCosmetics(loadout)"))
		assertTrue(legacyStats.contains("activityRecreationHandle.recreateAll()"))
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
