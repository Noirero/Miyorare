package org.koitharu.kotatsu.alternatives.domain

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationNonRoomDurabilityContractTest {

	@Test
	fun `read overrides cross a synchronous durability barrier before Room commit`() {
		val migration = source("org/koitharu/kotatsu/alternatives/domain/MigrateUseCase.kt")
		val store = source("org/koitharu/kotatsu/core/prefs/ChapterReadOverridesMigrationStore.kt")

		val prepare = migration.indexOf("preparedReadOverrides=losslessPlan?.let(losslessMigrationTransfer::prepareReadOverrides)?:false")
		val barrier = migration.indexOf("chapterReadOverridesMigrationStore.persistCurrent(newDetails.id)", prepare)
		val room = migration.indexOf("database.withTransaction{")
		assertTrue(prepare >= 0 && barrier > prepare && room > barrier)
		assertTrue(store.contains("editor.commit()"))
	}

	@Test
	fun `read override rollback and post-commit cleanup are also durable`() {
		val migration = source("org/koitharu/kotatsu/alternatives/domain/MigrateUseCase.kt")

		val rollback = migration.indexOf("losslessMigrationTransfer.rollbackReadOverrides(losslessPlan,preparedReadOverrides)")
		val rollbackBarrier = migration.indexOf("chapterReadOverridesMigrationStore.persistCurrent(newDetails.id)", rollback)
		assertTrue(rollback >= 0 && rollbackBarrier > rollback)

		val finish = migration.indexOf("losslessMigrationTransfer.finishReadOverrides(it)")
		val finishBarrier = migration.indexOf("chapterReadOverridesMigrationStore.persistCurrent(oldDetails.id)", finish)
		assertTrue(finish >= 0 && finishBarrier > finish)
	}

	@Test
	fun `other non-Room migration stores keep synchronous prepare and cleanup persistence`() {
		val notes = source("org/koitharu/kotatsu/details/data/MangaNotesRepository.kt")
		val profile = source("org/koitharu/kotatsu/reader/ui/config/MangaReaderProfileStore.kt")
		val localIndex = source("org/koitharu/kotatsu/local/data/index/LocalMangaIndex.kt")

		assertTrue(notes.substringAfter("funprepareMove").substringBefore("funfinishPreparedMove").contains(".commit()"))
		assertTrue(notes.substringAfter("funfinishPreparedMove").substringBefore("funrollbackPreparedMove").contains(".commit()"))
		assertTrue(notes.substringAfter("funrollbackPreparedMove").contains(".commit()"))

		assertTrue(profile.substringAfter("funprepareMove").substringBefore("funfinishPreparedMove").contains(".commit()"))
		assertTrue(profile.substringAfter("funfinishPreparedMove").substringBefore("funrollbackPreparedMove").contains(".commit()"))
		assertTrue(profile.substringAfter("funrollbackPreparedMove").contains(".commit()"))

		assertTrue(
			localIndex.substringAfter("prepareDownloadIdentityMove").substringBefore("rollbackPreparedDownloadIdentityMove")
				.contains(".commit()"),
		)
		assertTrue(
			localIndex.substringAfter("rollbackPreparedDownloadIdentityMove").substringBefore("finishPreparedDownloadIdentityMove")
				.contains(".commit()"),
		)
		assertTrue(
			localIndex.substringAfter("finishPreparedDownloadIdentityMove").substringBefore("suspendfunput")
				.contains(".commit()"),
		)
	}

	private fun source(relativePath: String): String {
		var dir = File(System.getProperty("user.dir"))
		repeat(8) {
			val candidate = File(dir, "src/main/kotlin/$relativePath")
			if (candidate.isFile) return candidate.readText().filterNot(Char::isWhitespace)
			dir = dir.parentFile ?: return@repeat
		}
		error("Cannot locate source file: $relativePath")
	}
}
