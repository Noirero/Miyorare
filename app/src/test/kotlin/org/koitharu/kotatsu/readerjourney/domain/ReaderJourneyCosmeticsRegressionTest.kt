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
		assertTrue(screen.contains("ReaderJourneyCosmetics.unlockedRanks(currentRank)"))
		assertFalse(screen.contains("ReaderRank.entries.forEach{rank->onSelect(rank)}"))
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
