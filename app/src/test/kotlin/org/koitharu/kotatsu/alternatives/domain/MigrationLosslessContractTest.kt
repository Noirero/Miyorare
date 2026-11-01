package org.koitharu.kotatsu.alternatives.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationLosslessContractTest {

	@Test
	fun `broken source autofix opts into lossless migration without changing legacy default`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val migrate = source("org/koitharu/kotatsu/alternatives/domain/MigrateUseCase.kt")

		assertTrue(autoFix.contains("lossless=true"))
		assertTrue(migrate.contains("lossless:Boolean=false"))
		assertTrue(migrate.contains("losslessMigrationTransfer.applyRoom(losslessPlan)"))
		assertTrue(migrate.contains("valchapterIds=mapChapterIds(oldDetails,newDetails)"))
	}

	@Test
	fun `lossless transfer covers durable migration state discovered by P1 audit`() {
		val transfer = source("org/koitharu/kotatsu/alternatives/domain/LosslessMigrationTransfer.kt")
		val migrate = source("org/koitharu/kotatsu/alternatives/domain/MigrateUseCase.kt")
		val localIndex = source("org/koitharu/kotatsu/local/data/index/LocalMangaIndex.kt")

		for (required in listOf(
			"getChapterPersonalDao()",
			"getStatsDao()",
			"getLibraryGroupsDao()",
			"getReaderJourneyDao()",
			"getTrackLogsDao()",
			"getFavouriteDownloadIndexDao()",
			"getChapterReadOverrides(oldManga.id)",
			"migrateScrobblingRows(oldId,newId)",
			"mappedChapterId(plan.chapterTargets",
		)) {
			assertTrue("Missing lossless migration authority: $required", transfer.contains(required))
		}
		assertTrue(migrate.contains("prepareDownloadIdentityMove(oldDetails.id,newDetails.id,"))
		assertTrue(migrate.contains("chapterTargets=losslessPlan.chapterTargets.mapValues{it.value.id}"))
		assertTrue(migrate.contains("rollbackPreparedDownloadIdentityMove(newDetails.id,preparedDownloadIdentity)"))
		assertTrue(migrate.contains("finishPreparedDownloadIdentityMove(oldDetails.id,preparedDownloadIdentity)"))
		assertTrue(localIndex.contains("findPath(oldRemoteMangaId)"))
		assertTrue(localIndex.contains("Migrationtargetalreadypointstoadifferentdownloadedcontainer"))
	}

	@Test
	fun `ambiguous chapter identity and destination conflicts fail instead of dropping source state`() {
		val transfer = source("org/koitharu/kotatsu/alternatives/domain/LosslessMigrationTransfer.kt")

		assertTrue(transfer.contains("targetCounts[it.id]==1"))
		assertTrue(transfer.contains("throwLosslessMigrationConflictException(message)"))
		assertTrue(transfer.contains("Destinationmangaalreadyhasdifferentreadinghistory"))
		assertTrue(transfer.contains("Per-chapternotesconflict"))
		assertTrue(transfer.contains("if(target!=null&&!samePath(target.path,source.path)){"))
	}

	@Test
	fun `download alias conflict does not block committed user state or misassign paths`() {
		val migrate = source("org/koitharu/kotatsu/alternatives/domain/MigrateUseCase.kt")
		val transfer = source("org/koitharu/kotatsu/alternatives/domain/LosslessMigrationTransfer.kt")

		assertTrue(migrate.contains("runCatchingCancellable{localMangaIndex.prepareDownloadIdentityMove("))
		assertTrue(migrate.contains("}.onFailure{it.printStackTraceDebug()}.getOrNull()"))
		val ownership = transfer.substringAfter("privatesuspendfunmigrateDownloadOwnership")
			.substringBefore("privatesuspendfunmigrateScrobblingRows")
		assertTrue(ownership.contains("if(target!=null&&!samePath(target.path,source.path)){"))
		assertTrue(ownership.contains("continue"))
		assertTrue(ownership.indexOf("continue") < ownership.indexOf("dao.delete(source.space,oldId)"))
	}


	@Test
	fun `source replacement never performs physical download deletion or local index rekey`() {
		val transfer = source("org/koitharu/kotatsu/alternatives/domain/LosslessMigrationTransfer.kt")
		val localIndex = source("org/koitharu/kotatsu/local/data/index/LocalMangaIndex.kt")

		assertFalse(transfer.contains("File.delete"))
		assertFalse(transfer.contains("deleteAwait"))
		assertFalse(transfer.contains("getLocalMangaIndexDao().upsert"))
		assertTrue(transfer.contains("local_indexisintentionallynotre-keyedhere"))
		assertFalse(localIndex.substringAfter("prepareDownloadIdentityMove").substringBefore("suspendfunput").contains("dao.upsert"))
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
