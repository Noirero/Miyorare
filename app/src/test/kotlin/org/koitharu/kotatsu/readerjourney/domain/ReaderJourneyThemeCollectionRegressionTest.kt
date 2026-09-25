package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyThemeCollectionRegressionTest {

	@Test
	fun `exclusive collection and customizer stay inside Reader Profile`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val exclusive = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("ReaderProfileCard("))
		assertTrue(screen.contains("ReaderJourneyThemeCard("))
		assertTrue(screen.contains("ReaderJourneyExclusiveCollection("))
		assertTrue(screen.contains("ReaderCosmeticsEditorSheet("))
		assertTrue(screen.contains("ReaderJourneyExclusiveCustomizerDialog("))
		assertTrue(screen.contains("customizerInitialThemeId=themeIdshowCosmeticsEditor=true"))
		assertTrue(exclusive.contains("ReaderJourneyCosmeticPolicy.collection(currentRank)"))
		assertTrue(exclusive.contains("ReaderJourneyCollectionFilter.entries"))
		assertTrue(exclusive.contains("THEMES(R.string.reader_journey_collection_filter_themes)"))
		assertTrue(exclusive.contains("FRAMES(R.string.reader_journey_collection_filter_frames)"))
		assertTrue(exclusive.contains("BADGES(R.string.reader_journey_collection_filter_badges)"))
		assertTrue(exclusive.contains("WALLPAPERS(R.string.reader_journey_collection_filter_wallpapers)"))
		assertFalse(screen.contains("BottomNavigation"))
		assertFalse(screen.contains("NavigationBarItem(onClick={showCosmeticsEditor"))
	}

	@Test
	fun `locked collection entries cannot open and customizer only exposes owned themes`() {
		val exclusive = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(exclusive.contains("valmodifier=if(entry.unlocked)baseModifier.clickable(onClick=onClick)elsebaseModifier"))
		assertTrue(exclusive.contains("reader_journey_unlock_at_level"))
		assertTrue(exclusive.contains("collection.filter{it.unlocked}.map{it.visualSpec}"))
		assertTrue(exclusive.contains("takeIf{ReaderJourneyCosmeticPolicy.owns(it,currentRank)}"))
		assertTrue(exclusive.contains("if(!entry.unlocked)"))
	}

	@Test
	fun `custom mix match flows through centralized ownership policy`() {
		val policy = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCosmeticPolicy.kt")
			.replace(Regex("\\s+"), "")
		val exclusive = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(policy.contains("funequipDefault("))
		assertTrue(policy.contains("funequipAuto("))
		assertTrue(policy.contains("funequipFullSet("))
		assertTrue(policy.contains("funequipCustom("))
		assertTrue(policy.contains("funtoggleFavorite("))
		assertTrue(policy.contains("funsanitizeForRank("))
		assertTrue(exclusive.contains("onApply(ReaderJourneyCosmeticPolicy.sanitizeForRank(finalDraft,currentRank))"))
		assertTrue(exclusive.contains("selectedThemeId=previewTheme.stableId"))
		assertTrue(exclusive.contains("selectedBadgeId=draft.selectedBadgeId?:finalSpec.badgeId"))
		assertTrue(exclusive.contains("selectedWallpaperId=draft.selectedWallpaperId?:finalSpec.wallpaperId"))
		assertTrue(exclusive.contains("selectedReaderCardId=draft.selectedReaderCardId?:finalSpec.cardId"))
		assertTrue(exclusive.contains("selectedProgressStyleId=draft.selectedProgressStyleId?:finalSpec.progressId"))
	}

	@Test
	fun `collection keeps reward pressure out of Home while preserving lock feedback`() {
		val exclusive = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(exclusive.contains("reader_journey_unlock_at_level"))
		assertTrue(exclusive.contains("reader_journey_equipped"))
		assertFalse(exclusive.contains("HomeScreen"))
		assertFalse(exclusive.contains("countdown"))
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
