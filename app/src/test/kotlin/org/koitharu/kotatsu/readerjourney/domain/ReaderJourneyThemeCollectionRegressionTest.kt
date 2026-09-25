package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyThemeCollectionRegressionTest {

	@Test
	fun `customize theme stays inside Reader Profile while choices remain staged`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("ReaderProfileCard("))
		assertTrue(screen.contains("ReaderJourneyThemeCard("))
		assertTrue(screen.contains("ReaderCosmeticsEditorSheet("))
		assertTrue(screen.contains("onCustomize={showCosmeticsEditor=true}"))
		assertTrue(screen.contains("KEY_RANK_THEME_ENABLED"))
		assertTrue(screen.contains("reader_journey_theme_choices_later"))
		assertFalse(screen.contains("ReaderJourneyCosmeticPolicy.collection(currentRank)"))
		assertFalse(screen.contains("ReaderJourneyCosmeticMode.entries"))
		assertFalse(screen.contains("BottomNavigation"))
		assertFalse(screen.contains("NavigationBarItem(onClick={showCosmeticsEditor"))
	}

	@Test
	fun `locked collection ownership remains enforced while collection UI is hidden`() {
		val policy = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCosmeticPolicy.kt")
			.replace(Regex("\\s+"), "")
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(policy.contains("unlocked=owns(spec.themeId,currentRank)"))
		assertTrue(policy.contains("if(!owns(theme,currentRank)){returnsanitizeForRank(loadout,currentRank)}"))
		assertTrue(policy.contains("if(!owns(theme,currentRank))returnsanitizeForRank(loadout,currentRank)"))
		assertFalse(screen.contains("onPreview={previewThemeId=entry.theme.stableId}"))
		assertFalse(screen.contains("reader_journey_unlock_at_level"))
	}

	@Test
	fun `full set and custom mix match remain centralized while choices are hidden`() {
		val policy = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCosmeticPolicy.kt")
			.replace(Regex("\\s+"), "")
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(policy.contains("funequipDefault("))
		assertTrue(policy.contains("funequipAuto("))
		assertTrue(policy.contains("funequipFullSet("))
		assertTrue(policy.contains("funequipCustom("))
		assertTrue(policy.contains("funtoggleFavorite("))
		assertTrue(policy.contains("funsanitizeForRank("))
		assertTrue(policy.contains("selectedBadgeId=spec.badgeId"))
		assertTrue(policy.contains("selectedWallpaperId=spec.wallpaperId"))
		assertTrue(policy.contains("selectedReaderCardId=spec.cardId"))
		assertTrue(policy.contains("selectedProgressStyleId=spec.progressId"))
		assertFalse(screen.contains("ReaderJourneyCosmeticPolicy.equipFullSet("))
		assertFalse(screen.contains("ReaderJourneyCosmeticPolicy.equipCustom("))
	}

	@Test
	fun `Reader Profile keeps reward pressure out while staged theme choices are hidden`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("ReaderJourneyThemeCard("))
		assertTrue(screen.contains("reader_journey_profile_current_theme"))
		assertTrue(screen.contains("reader_journey_theme_customize_action"))
		assertFalse(screen.contains("ReaderJourneyCosmeticPolicy.nextLockedTheme(progress.rank)"))
		assertFalse(screen.contains("reader_journey_next_reward"))
		assertFalse(screen.contains("HomeScreen"))
		assertFalse(screen.contains("countdown"))
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
