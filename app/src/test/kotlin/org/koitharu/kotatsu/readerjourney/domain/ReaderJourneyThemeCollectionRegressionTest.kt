package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyThemeCollectionRegressionTest {

	@Test
	fun `collection stays inside Reader Profile and exposes all approved modes`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("ReaderProfileCard("))
		assertTrue(screen.contains("ReaderCosmeticsEditorSheet("))
		assertTrue(screen.contains("ReaderJourneyCosmeticPolicy.collection(currentRank)"))
		assertTrue(screen.contains("ReaderJourneyCosmeticMode.entries"))
		assertTrue(screen.contains("ReaderJourneyCosmeticMode.DEFAULT"))
		assertTrue(screen.contains("ReaderJourneyCosmeticMode.AUTO"))
		assertTrue(screen.contains("ReaderJourneyCosmeticMode.FULL_SET"))
		assertTrue(screen.contains("ReaderJourneyCosmeticMode.CUSTOM"))
		assertFalse(screen.contains("BottomNavigation"))
		assertFalse(screen.contains("NavigationBarItem(onClick={showCosmeticsEditor"))
	}

	@Test
	fun `locked collection entries can preview but cannot equip or favorite`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("onPreview={previewThemeId=entry.theme.stableId}"))
		assertTrue(screen.contains("enabled=entry.unlocked"))
		assertTrue(screen.contains("if(entry.unlocked){TextButton(onClick=onFavorite)"))
		assertTrue(screen.contains("reader_journey_unlock_at_level"))
	}

	@Test
	fun `full set and custom mix match flow through centralized ownership policy`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("ReaderJourneyCosmeticPolicy.equipDefault("))
		assertTrue(screen.contains("ReaderJourneyCosmeticPolicy.equipAuto("))
		assertTrue(screen.contains("ReaderJourneyCosmeticPolicy.equipFullSet("))
		assertTrue(screen.contains("ReaderJourneyCosmeticPolicy.equipCustom("))
		assertTrue(screen.contains("ReaderJourneyCosmeticPolicy.toggleFavorite("))
		assertTrue(screen.contains("ReaderJourneyCosmeticPolicy.sanitizeForRank(draft,currentRank)"))
		assertTrue(screen.contains("selectedBadgeId=spec?.badgeId"))
		assertTrue(screen.contains("selectedWallpaperId=spec?.wallpaperId"))
		assertTrue(screen.contains("selectedReaderCardId=spec?.cardId"))
		assertTrue(screen.contains("selectedProgressStyleId=spec?.progressId"))
	}

	@Test
	fun `Reader Profile shows one natural next reward preview without home pressure`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("ReaderJourneyCosmeticPolicy.nextLockedTheme(progress.rank)"))
		assertTrue(screen.contains("reader_journey_next_reward"))
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
