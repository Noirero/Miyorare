package org.koitharu.kotatsu.backup.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupScaleIntegrityRegressionTest {

	@Test
	fun `large backup walks keysets instead of growing offsets`() {
		val favourites = source("org/koitharu/kotatsu/favourites/data/FavouritesDao.kt")
		val privateFavourites = source("org/koitharu/kotatsu/favourites/data/PrivateFavouritesDao.kt")
		val history = source("org/koitharu/kotatsu/history/data/HistoryDao.kt")
		val bookmarks = source("org/koitharu/kotatsu/bookmarks/data/BookmarksDao.kt")
		val manga = source("org/koitharu/kotatsu/core/db/dao/MangaDao.kt")
		val backup = source("org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt")

		assertTrue(favourites.contains("findAllForBackup(afterMangaId,afterCategoryId,window)"))
		assertTrue(privateFavourites.contains("findAllForBackup(afterMangaId,afterCategoryId,window)"))
		assertTrue(history.contains("findFirstForBackup(window)"))
		assertTrue(history.contains("findAllForBackup(it,window)"))
		assertTrue(bookmarks.contains("findFirstMangaIdsForBackup(window)"))
		assertTrue(bookmarks.contains("findMangaIdsForBackup(it,window)"))
		assertTrue(bookmarks.contains("findAllForBackup(mangaIds)"))
		assertTrue(manga.contains("WHEREmanga_id>:afterMangaId"))
		assertTrue(manga.contains("abstractsuspendfunfindFirstForBackup(limit:Int):List<MangaWithTags>"))
		assertTrue(manga.contains("EXISTS(SELECT1FROMchaptersWHEREchapters.manga_id=manga.manga_id)"))
		assertTrue(backup.contains("findAllForBackup(it,BACKUP_DB_BATCH_SIZE)"))
		assertTrue(backup.contains("findFirstForBackup(BACKUP_DB_BATCH_SIZE)"))
		assertTrue(backup.contains("privateconstvalBACKUP_DB_BATCH_SIZE=256"))
		assertTrue(backup.contains("privateconstvalRESTORE_DB_BATCH_SIZE=256"))
		assertTrue(backup.contains("varafterMangaId:Long?=null"))
		assertTrue(backup.contains("findByIds(pendingManga.keys)"))
		assertTrue(backup.contains("output.setLevel(Deflater.BEST_SPEED)"))

		val favouritesDump = favourites.substringAfter("fundump():Flow<FavouriteManga>").substringBefore("/**INSERT**/")
		val privateDump = privateFavourites.substringAfter("fundump():Flow<PrivateFavouriteManga>").substringBefore("@Insert")
		val historyDump = history.substringAfter("fundump():Flow<HistoryWithManga>").substringBefore("@Insert")
		val bookmarksDump = bookmarks.substringAfter("fundump():Flow<Pair<MangaWithTags,List<BookmarkEntity>>>")
		assertFalse(favouritesDump.contains("OFFSET"))
		assertFalse(privateDump.contains("OFFSET"))
		assertFalse(historyDump.contains("OFFSET"))
		assertFalse(bookmarksDump.contains("OFFSET"))
	}

	@Test
	fun `restore fails closed on duplicated manga id mismatches`() {
		val backup = source("org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt")

		assertTrue(backup.contains("requireMangaReference(\"HISTORY\",manga.id,item.mangaId)"))
		assertTrue(backup.contains("requireMangaReference(\"FAVOURITES\",manga.id,item.mangaId)"))
		assertTrue(backup.contains("requireMangaReference(\"BOOKMARKS\",manga.id,bookmark.mangaId)"))
		assertTrue(backup.contains("requireMangaReference(\"CHAPTERS\",manga.id,chapter.mangaId)"))
		assertTrue(backup.contains("requireMangaReference(\"FEED_TRACK\",manga.id,item.mangaId)"))
		assertTrue(backup.contains("requireMangaReference(\"FEED_LOG\",manga.id,item.mangaId)"))
		assertTrue(backup.contains("requireMangaReference(\"MANGA_PREFS\",item.manga.id,item.prefs.mangaId)"))
		assertTrue(backup.contains("requireMangaReference(\"PRIVATE_FAVOURITES\",item.manga.id,item.mangaId)"))
		assertTrue(backup.contains("existingSource==null||existingSource==manga.source"))
		assertTrue(backup.contains("FavouritesrestorerequiresCategoriessocategory-to-mangaidentitycanberemappedsafely"))
	}

	@Test
	fun `migration rekeys private group feed preference and download state`() {
		val dao = source("org/koitharu/kotatsu/core/db/dao/MangaDao.kt")
		val migrator = source("org/koitharu/kotatsu/kotatsumigration/domain/KotatsuMangaMigrator.kt")

		assertTrue(dao.contains("UNIONSELECTmanga_idFROMprivate_favouritesWHEREdeleted_at=0"))
		assertTrue(dao.contains("UNIONSELECTmanga_idFROMtrack_logs"))
		assertTrue(dao.contains("UNIONSELECTmanga_idFROMlocal_index"))
		assertTrue(dao.contains("UNIONSELECTmanga_idFROMfavourite_download_index"))
		assertTrue(dao.contains("SELECTmanga_idASid,sourceASsourceNameFROMmanga"))
		assertTrue(migrator.contains("getPrivateFavouritesDao()"))
		assertTrue(migrator.contains("getLibraryGroupsDao()"))
		assertTrue(migrator.contains("getPreferencesDao()"))
		assertTrue(migrator.contains("findAllForManga(oldId)"))
		assertTrue(migrator.contains("getLocalMangaIndexDao()"))
		assertTrue(migrator.contains("getFavouriteDownloadIndexDao()"))
		assertTrue(migrator.contains("Migrationtargetidentitycollision"))
		assertTrue(migrator.contains("Migrationtargetmangaalreadybelongstoadifferentlibrarygroup"))
	}

	@Test
	fun `Mihon export batches chapter reads and periodic backup buffers IO`() {
		val exporter = source("org/koitharu/kotatsu/backup/MihonBackupExporter.kt")
		val worker = source("org/koitharu/kotatsu/backup/local/ui/periodical/PeriodicalBackupWorker.kt")
		val storage = source("org/koitharu/kotatsu/backup/local/domain/ExternalBackupStorage.kt")

		assertTrue(exporter.contains("prepared.chunked(EXPORT_DB_BATCH_SIZE)"))
		assertTrue(exporter.contains("findAll(batch.map{it.record.manga.id})"))
		assertTrue(exporter.contains("privateconstvalEXPORT_DB_BATCH_SIZE=256"))
		assertFalse(exporter.contains("getChaptersDao().findAll(manga.id)"))
		assertTrue(worker.contains("BufferedOutputStream(tempFile.outputStream(),64*1024)"))
		assertTrue(storage.contains("openOutputStream(out.uri,\"wt\")).sink().buffer()"))
		assertTrue(storage.contains("runCatching{out.delete()}"))
	}

	private fun source(relativePath: String): String {
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
			)
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}
}
