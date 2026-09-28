package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
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
		assertTrue(exclusive.contains("ReaderJourneyRewardAccess.cosmeticAccessRank(currentRank)"))
		assertTrue(exclusive.contains("ReaderJourneyCosmeticPolicy.collection(accessRank)"))
		assertTrue(exclusive.contains("ReaderJourneyCollectionFilter.entries"))
		assertTrue(exclusive.contains("THEMES(R.string.reader_journey_collection_filter_themes)"))
		assertTrue(exclusive.contains("FRAMES(R.string.reader_journey_collection_filter_frames)"))
		assertTrue(exclusive.contains("BADGES(R.string.reader_journey_collection_filter_badges)"))
		assertTrue(exclusive.contains("WALLPAPERS(R.string.reader_journey_collection_filter_wallpapers)"))
		assertFalse(screen.contains("BottomNavigation"))
		assertFalse(screen.contains("NavigationBarItem(onClick={showCosmeticsEditor"))
	}

	@Test
	fun `all themes can be previewed but applying locked selections stays hidden`() {
		val exclusive = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(exclusive.contains("valmodifier=baseModifier.clickable(onClick=onClick)"))
		assertTrue(exclusive.contains("reader_journey_unlock_at_level"))
		assertTrue(exclusive.contains("collection.map{it.visualSpec}"))
		assertFalse(exclusive.contains("collection.filter{it.unlocked}"))
		assertFalse(exclusive.contains("RankThemeId.fromStableId(initialThemeId)?.takeIf"))
		assertTrue(exclusive.contains("if(canApply){item(\"apply\")"))
		assertTrue(exclusive.contains("if(canApply){item(\"exclusive-switch\")"))
		assertTrue(exclusive.contains("if(ReaderJourneyCosmeticPolicy.canApply(draft,accessRank)){onApply("))
		assertTrue(exclusive.contains("takeIf{ReaderJourneyCosmeticPolicy.owns(it,accessRank)}"))
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
		assertTrue(exclusive.contains("onApply(ReaderJourneyCosmeticPolicy.sanitizeForRank(draft,accessRank))"))
		assertTrue(exclusive.contains("ReaderJourneyCustomizeTab.THEME_MIX"))
		assertTrue(exclusive.contains("selectedThemeId=selected.stableId"))
		assertTrue(exclusive.contains("navigationThemeId=selected?.stableId"))
		assertTrue(exclusive.contains("accentThemeId=selected?.stableId"))
		assertTrue(exclusive.contains("glowThemeId=selected?.stableId"))
		assertTrue(exclusive.contains("selectedFrameId=spec?.frameId"))
		assertTrue(exclusive.contains("selectedNameplateId=spec?.nameplateId"))
		assertTrue(exclusive.contains("selectedWallpaperId=spec?.wallpaperId"))
		assertTrue(exclusive.contains("selectedBadgeId=spec?.badgeId"))
		assertTrue(exclusive.contains("selectedProgressStyleId=spec?.progressId"))
		assertTrue(exclusive.contains("ReaderJourneyCosmeticPolicy.equipFullSet("))
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

	@Test
	fun `official Beta keeps cosmetic ownership rank gated`() {
		val gradle = sequenceOf(
			File("app/build.gradle"),
			File("build.gradle"),
		).firstOrNull(File::isFile)?.readText()
			?: error("Cannot find app build.gradle")
		val preview = gradle
			.substringAfter("preview {")
			.substringBefore("release {")

		assertTrue(
			preview.contains(
				"buildConfigField 'boolean', 'READER_JOURNEY_UNLOCK_ALL_REWARDS', 'false'",
			),
		)
	}

	@Test
	fun `all twelve foundation previews unlock at their own rank boundary`() {
		assertEquals(12, ReaderJourneyCosmeticPolicy.collection(ReaderRank.NEWCOMER).size)
		for (theme in RankThemeId.entries) {
			val preview = ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.CUSTOM,
				selectedThemeId = theme.stableId,
			)
			assertEquals(theme.rank, ReaderJourneyCosmeticPolicy.requiredRank(preview))
			for (rank in ReaderRank.entries) {
				assertEquals(
					"${theme.stableId} at $rank",
					rank.minLevel >= theme.rank.minLevel,
					ReaderJourneyCosmeticPolicy.canApply(preview, rank),
				)
			}
		}
	}

	@Test
	fun `each mixed component enforces its rank even with an unlocked foundation`() {
		val base = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = RankThemeId.FIRST_PAGE.stableId,
		)
		for (spec in RankThemeVisualRegistry.all) {
			val previews = listOf(
				base.copy(navigationThemeId = spec.themeId.stableId),
				base.copy(accentThemeId = spec.themeId.stableId),
				base.copy(glowThemeId = spec.themeId.stableId),
				base.copy(selectedBadgeId = spec.badgeId),
				base.copy(selectedWallpaperId = spec.wallpaperId),
				base.copy(selectedFrameId = spec.frameId),
				base.copy(selectedNameplateId = spec.nameplateId),
				base.copy(selectedReaderCardId = spec.cardId),
				base.copy(selectedProgressStyleId = spec.progressId),
				base.copy(frame = spec.themeId.rank),
				base.copy(glow = spec.themeId.rank),
				base.copy(background = spec.themeId.rank),
				base.copy(progressBar = spec.themeId.rank),
			)
			for (preview in previews) {
				assertEquals(spec.themeId.rank, ReaderJourneyCosmeticPolicy.requiredRank(preview))
				for (rank in ReaderRank.entries) {
					assertEquals(
						"$preview at $rank",
						rank.minLevel >= spec.themeId.rank.minLevel,
						ReaderJourneyCosmeticPolicy.canApply(preview, rank),
					)
				}
			}
		}
	}

	@Test
	fun `removing locked overrides restores apply and unknown items remain rejected`() {
		val base = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = RankThemeId.FIRST_PAGE.stableId,
		)
		val preview = base.copy(
			navigationThemeId = RankThemeId.ETERNAL_LIBRARY.stableId,
			accentThemeId = RankThemeId.GOLDEN_MANUSCRIPT.stableId,
		)
		assertEquals(ReaderRank.LEGEND, ReaderJourneyCosmeticPolicy.requiredRank(preview))
		assertFalse(ReaderJourneyCosmeticPolicy.canApply(preview, ReaderRank.GRAND_READER))
		assertTrue(ReaderJourneyCosmeticPolicy.canApply(preview, ReaderRank.LEGEND))
		assertTrue(ReaderJourneyCosmeticPolicy.canApply(
			preview.copy(navigationThemeId = null, accentThemeId = null), ReaderRank.NEWCOMER,
		))
		assertFalse(ReaderJourneyCosmeticPolicy.canApply(
			base.copy(selectedBadgeId = "unknown-badge"), ReaderRank.LEGEND,
		))
		assertEquals(RankThemeId.FIRST_PAGE.stableId, base.selectedThemeId)
		assertEquals(null, base.navigationThemeId)
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

