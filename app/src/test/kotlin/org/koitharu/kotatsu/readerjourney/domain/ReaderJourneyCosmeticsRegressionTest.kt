package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyCosmeticsRegressionTest {

	@Test
	fun `rank cosmetics stay deterministic and privacy safe`() {
		val journey = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourney.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(journey.contains("objectReaderJourneyCosmetics"))
		assertTrue(journey.contains("ReaderJourneyCosmeticSlot.entries.map"))
		assertFalse(journey.contains("kotlin.random"))
		assertFalse(journey.contains("mangaTitle"))
		assertFalse(journey.contains("sourceId"))
		assertFalse(journey.contains("genre"))
	}

	@Test
	fun `profile cosmetics use one atomic snapshot and preserve legacy migration`() {
		val store = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderProfileStore.kt")
			.replace(Regex("\\s+"), "")
		val codec = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCosmeticSnapshotCodec.kt")
			.replace(Regex("\\s+"), "")
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val exclusive = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")
		val viewModel = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsViewModel.kt")
			.replace(Regex("\\s+"), "")
		val policy = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCosmeticPolicy.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(store.contains("funupdateCosmetics(loadout:ReaderJourneyCosmeticLoadout)"))
		assertTrue(store.contains("cosmetics=_profile.value.cosmetics"))
		assertTrue(store.contains("KEY_COSMETIC_LOADOUT_V2"))
		assertTrue(store.contains("putString(KEY_COSMETIC_LOADOUT_V2"))
		assertTrue(store.contains(".commit()"))
		assertTrue(store.contains("migrateLegacyCosmeticsIfNeeded()"))
		assertTrue(store.contains("KEY_COSMETIC_FRAME"))
		assertTrue(store.contains("KEY_COSMETIC_GLOW"))
		assertTrue(store.contains("KEY_COSMETIC_BACKGROUND"))
		assertTrue(store.contains("KEY_COSMETIC_PROGRESS"))
		assertTrue(codec.contains("SCHEMA_VERSION"))
		assertTrue(codec.contains("RankThemeId.fromStableId"))
		assertTrue(screen.contains("ReaderCosmeticsEditorSheet("))
		assertTrue(screen.contains("ReaderJourneyThemeCard("))
		assertTrue(screen.contains("ReaderJourneyExclusiveCollection("))
		assertTrue(screen.contains("ReaderJourneyExclusiveCustomizerDialog("))
		assertTrue(exclusive.contains("ReaderJourneyRewardAccess.cosmeticAccessRank(currentRank)"))
		assertTrue(exclusive.contains("ReaderJourneyCosmeticPolicy.collection(accessRank)"))
		assertTrue(exclusive.contains("onApply(ReaderJourneyCosmeticPolicy.sanitizeForRank(draft,accessRank))"))
		assertTrue(exclusive.contains("KEY_RANK_THEME_ENABLED"))
		assertTrue(exclusive.contains("selectedThemeId=selected.stableId"))
		assertTrue(exclusive.contains("navigationThemeId=selected?.stableId"))
		assertTrue(exclusive.contains("accentThemeId=selected?.stableId"))
		assertTrue(exclusive.contains("glowThemeId=selected?.stableId"))
		assertTrue(exclusive.contains("selectedFrameId=spec?.frameId"))
		assertTrue(exclusive.contains("selectedNameplateId=spec?.nameplateId"))
		assertTrue(exclusive.contains("selectedWallpaperId=spec?.wallpaperId"))
		assertTrue(exclusive.contains("selectedProgressStyleId=spec?.progressId"))
		assertTrue(exclusive.contains("ReaderJourneyCosmeticPolicy.equipFullSet("))
		assertFalse(exclusive.contains("ReaderRank.entries.forEach{rank->onSelect(rank)}"))
		assertTrue(viewModel.contains("ReaderJourneyRewardAccess.cosmeticAccessRank(currentRank)"))
		assertTrue(viewModel.contains("ReaderJourneyCosmeticPolicy.sanitizeForRank(loadout,cosmeticAccessRank)"))
		assertTrue(policy.contains("valsanitized=loadout.copy("))
		assertTrue(policy.contains("selectedThemeId=selectedTheme?.stableId"))
		assertTrue(policy.contains("navigationThemeId=sanitizeThemeSource(loadout.navigationThemeId)"))
		assertTrue(policy.contains("accentThemeId=sanitizeThemeSource(loadout.accentThemeId)"))
		assertTrue(policy.contains("glowThemeId=sanitizeThemeSource(loadout.glowThemeId)"))
		assertTrue(policy.contains("selectedBadgeId=loadout.selectedBadgeId?.takeIf"))
		assertTrue(policy.contains("selectedWallpaperId=loadout.selectedWallpaperId?.takeIf"))
		assertTrue(policy.contains("selectedFrameId=loadout.selectedFrameId?.takeIf"))
		assertTrue(policy.contains("selectedNameplateId=loadout.selectedNameplateId?.takeIf"))
		assertTrue(policy.contains("selectedReaderCardId=loadout.selectedReaderCardId?.takeIf"))
		assertTrue(policy.contains("selectedProgressStyleId=loadout.selectedProgressStyleId?.takeIf"))
		assertTrue(policy.contains("favoriteThemeIds=loadout.favoriteThemeIds.filterTo"))
		assertFalse(viewModel.contains("ReaderJourneyCosmeticLoadout("))
	}

	@Test
	fun `rank theme foundation uses stable ids and one resolver`() {
		val theme = source("kotlin/org/koitharu/kotatsu/readerjourney/theme/RankTheme.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(theme.contains("ARCHIVIST_NEON_ARCHIVE"))
		assertTrue(theme.contains("objectRankThemeSourceResolver"))
		assertTrue(theme.contains("objectRankThemeRegistry"))
		assertTrue(theme.contains("LIGHT,DARK,OLED"))
		assertTrue(theme.contains("errorColor"))
		assertTrue(theme.contains("destructiveColor"))
		assertFalse(theme.contains("rank.ordinal"))
	}

	@Test
	fun `celebration is emitted only for a real level transition`() {
		val collector = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCollector.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(collector.contains("if(after.level>before.level){"))
		assertTrue(collector.contains("onJourneyProgressed.call("))
		assertTrue(collector.contains("ReaderJourneyCosmetics.newlyUnlocked(before.rank,after.rank)"))
	}

	@Test
	fun `celebration can be disabled and full animation respects system animation setting`() {
		val settings = source("kotlin/org/koitharu/kotatsu/core/prefs/ReaderJourneyCelebrationMode.kt")
			.replace(Regex("\\s+"), "")
		val reader = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(settings.contains("OFF,SUBTLE,FULL"))
		assertTrue(reader.contains("if(mode==ReaderJourneyCelebrationMode.OFF)return"))
		assertTrue(reader.contains("if(mode==ReaderJourneyCelebrationMode.FULL)"))
		assertTrue(reader.contains("if(event.isRankUp)"))
		assertTrue(reader.contains("if(isAnimationsEnabled)"))
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
