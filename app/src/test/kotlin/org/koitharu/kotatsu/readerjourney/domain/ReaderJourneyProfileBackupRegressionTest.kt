package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyProfileBackupRegressionTest {

	@Test
	fun `local Stats backup carries selection snapshot but never ownership booleans`() {
		val backup = source("kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt")
			.replace(Regex("\\s+"), "")
		val models = source("kotlin/org/koitharu/kotatsu/backup/local/data/model/BackupModels.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(backup.contains("output.writeReaderJourneyProfileSelection()"))
		assertTrue(backup.contains("READER_JOURNEY_PROFILE_ENTRY=\"reader_journey_profile\""))
		assertTrue(backup.contains("if(BackupSection.STATSinsections){result+=restoreReaderJourneyProfileSelection(input)}"))
		assertTrue(models.contains("classReaderJourneyProfileSelectionBackup("))
		assertTrue(models.contains("@SerialName(\"selected_title\")"))
		assertTrue(models.contains("@SerialName(\"cosmetic_loadout_v2\")"))
		assertFalse(models.contains("unlockedThemes"))
		assertFalse(models.contains("unlockedCosmetics"))
	}

	@Test
	fun `restore sanitizes cosmetic selection against rank rebuilt from Lifetime XP`() {
		val backup = source("kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt")
			.replace(Regex("\\s+"), "")
		val store = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderProfileStore.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(backup.contains("valjourney=database.getReaderJourneyDao().getProfile()"))
		assertTrue(backup.contains("ReaderJourneyRules.progress(journey?.totalXp?:0L).rank"))
		assertTrue(backup.contains("getAllAchievements().mapTo(HashSet()){it.achievementId}"))
		assertTrue(store.contains("ReaderJourneyCosmeticSnapshotCodec.decode(cosmeticSnapshot)"))
		assertTrue(store.contains("ReaderJourneyCosmeticPolicy.sanitizeForRank(it,currentRank)"))
		assertTrue(store.contains("selectedTitleId?.takeIf{itinunlockedAchievementIds}"))
	}

	@Test
	fun `invalid old cosmetic snapshot fails closed without deleting valid local selection`() {
		val store = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderProfileStore.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(store.contains("valdecoded=ReaderJourneyCosmeticSnapshotCodec.decode(cosmeticSnapshot)"))
		assertTrue(store.contains("?:current.cosmetics"))
	}

	@Test
	fun `display name and showcase remain device local and cloud sync is not expanded`() {
		val model = source("kotlin/org/koitharu/kotatsu/backup/local/data/model/BackupModels.kt")
		val sync = source("kotlin/org/koitharu/kotatsu/sync/domain/GoogleDriveSyncRepository.kt")
		val syncModels = source("kotlin/org/koitharu/kotatsu/sync/data/model/SyncModels.kt")

		assertFalse(model.contains("@SerialName(\"display_name\")"))
		assertFalse(model.contains("@SerialName(\"showcase\")"))
		assertFalse(sync.contains("ReaderJourneyProfileSelectionBackup"))
		assertFalse(sync.contains("reader_journey_profile"))
		assertFalse(syncModels.contains("ReaderJourneyProfileSelectionBackup"))
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
