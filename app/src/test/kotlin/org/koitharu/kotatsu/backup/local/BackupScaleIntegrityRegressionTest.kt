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
		val stats = source("org/koitharu/kotatsu/stats/data/StatsDao.kt")
		val scrobbling = source("org/koitharu/kotatsu/scrobbling/common/data/ScrobblingDao.kt")
		val sources = source("org/koitharu/kotatsu/core/db/dao/MangaSourcesDao.kt")
		val preferences = source("org/koitharu/kotatsu/core/db/dao/PreferencesDao.kt")
		val backup = source("org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt")

		assertTrue(favourites.contains("findFirstForBackup(window)"))
		assertTrue(favourites.contains("findAllForBackup(mangaId,categoryId,window)"))
		assertTrue(privateFavourites.contains("findFirstForBackup(window)"))
		assertTrue(privateFavourites.contains("findAllForBackup(mangaId,categoryId,window)"))
		assertTrue(history.contains("findFirstForBackup(window)"))
		assertTrue(history.contains("findAllForBackup(it,window)"))
		assertTrue(bookmarks.contains("findFirstMangaIdsForBackup(window)"))
		assertTrue(bookmarks.contains("findMangaIdsForBackup(it,window)"))
		assertTrue(bookmarks.contains("findAllForBackup(mangaIds)"))
		assertTrue(manga.contains("WHEREmanga_id>:afterMangaId"))
		assertTrue(manga.contains("abstractsuspendfunfindFirstForBackup(limit:Int):List<MangaWithTags>"))
		assertFalse(manga.contains("findAllBySourceForBackup("))
		assertTrue(stats.contains("findAllForBackup(startedAt,mangaId,window)"))
		assertTrue(scrobbling.contains("findAllForBackup(scrobbler,id,mangaId,window)"))
		assertTrue(sources.contains("findEnabledAfter(it,window)"))
		assertTrue(preferences.contains("findAllForBackup(afterMangaId:Long,limit:Int)"))
		assertTrue(backup.contains("prefsDao.findAllForBackup(it,BACKUP_DB_BATCH_SIZE)"))
		assertTrue(backup.contains("prefsDao.findFirstForBackup(BACKUP_DB_BATCH_SIZE)"))
		assertTrue(backup.contains("privateconstvalBACKUP_DB_BATCH_SIZE=256"))
		assertTrue(backup.contains("privateconstvalRESTORE_DB_BATCH_SIZE=256"))
		assertTrue(backup.contains("output.setLevel(Deflater.BEST_SPEED)"))

		val favouritesDump = favourites.substringAfter("fundump():Flow<FavouriteManga>").substringBefore("/**INSERT**/")
		val privateDump = privateFavourites.substringAfter("fundump():Flow<PrivateFavouriteManga>").substringBefore("@Insert")
		val historyDump = history.substringAfter("fundump():Flow<HistoryWithManga>").substringBefore("@Insert")
		val bookmarksDump = bookmarks.substringAfter("fundump():Flow<Pair<MangaWithTags,List<BookmarkEntity>>>")
		val statsDump = stats.substringAfter("fundumpEnabled():Flow<StatsEntity>")
		val scrobblingDump = scrobbling.substringAfter("fundumpEnabled():Flow<ScrobblingEntity>")
		val sourcesDump = sources.substringAfter("fundumpEnabled():Flow<MangaSourceEntity>")
		assertFalse(favouritesDump.contains("OFFSET"))
		assertFalse(privateDump.contains("OFFSET"))
		assertFalse(historyDump.contains("OFFSET"))
		assertFalse(bookmarksDump.contains("OFFSET"))
		assertFalse(statsDump.contains("OFFSET"))
		assertFalse(scrobblingDump.contains("OFFSET"))
		assertFalse(sourcesDump.contains("OFFSET"))
	}

	@Test
	fun `private backup and restore stay streaming`() {
		val backup = source("org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt")
		val restore = backup
			.substringAfter("privatesuspendfunrestorePrivateFavourites(input:InputStream):CompositeResult")
			.substringBefore("privatesuspendfunrestorePrivateFavouriteBatch")

		assertTrue(backup.contains("output.writePrivateFavourites()"))
		assertTrue(backup.contains("writeJsonArrayPayload(data=database.getPrivateFavouritesDao().dump().map(::PrivateFavouriteItemBackup)"))
		assertTrue(backup.contains("writeJsonArrayPayload(data=libraryGroupBackupCodec.dump(FavouriteSpace.PRIVATE)"))
		assertTrue(restore.contains("JsonReader(InputStreamReader(input,Charsets.UTF_8))"))
		assertTrue(restore.contains("reader.readJsonArrayBatches(serializer<PrivateFavouriteItemBackup>())"))
		assertTrue(restore.contains("libraryGroupBackupCodec.restore("))
		assertFalse(backup.contains("dumpPrivateFavourites()"))
		assertFalse(restore.contains("readBytes()"))
		assertFalse(restore.contains("decodeFromStream<PrivateFavouritesBackup>"))
	}

	@Test
	fun `Mihon and feed paths stay bounded for very large libraries`() {
		val exporter = source("org/koitharu/kotatsu/backup/MihonBackupExporter.kt")
		val manager = source("org/koitharu/kotatsu/backup/MihonBackupManager.kt")
		val local = source("org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt")
		val mangaDao = source("org/koitharu/kotatsu/core/db/dao/MangaDao.kt")
		val tracksDao = source("org/koitharu/kotatsu/tracker/data/TracksDao.kt")
		val logsDao = source("org/koitharu/kotatsu/core/db/dao/TrackLogsDao.kt")

		assertTrue(exporter.contains("findFirstForMihonExport(EXPORT_DB_BATCH_SIZE)"))
		assertTrue(exporter.contains("getChaptersDao().findAll(ids)"))
		assertTrue(exporter.contains("MihonBackupWire.writeMessage("))
		assertTrue(exporter.contains("GZIPOutputStream(BufferedOutputStream("))
		assertFalse(exporter.contains("ProtoBuf.encodeToByteArray(MihonBackup.serializer(),backup)"))
		assertFalse(exporter.contains("valrecords=HashMap<Long,Record>()"))
		assertFalse(exporter.contains("ArrayList<MihonBackupManga>"))

		assertTrue(manager.contains("scanBackup(uri,options)"))
		assertTrue(manager.contains("restoreMangaStream("))
		assertTrue(manager.contains("RESTORE_MANGA_BATCH_SIZE=64"))
		assertTrue(manager.contains("RESTORE_CHAPTER_BATCH_LIMIT=4096"))
		assertFalse(manager.contains("readByteArray()"))
		assertFalse(manager.contains("decodeFromByteArray(MihonBackup.serializer()"))
		assertFalse(manager.contains("backup.backupManga.mapIndexed"))

		assertTrue(local.contains("writeJsonArrayPayload(dumpFeedTracks(),serializer())"))
		assertTrue(local.contains("writeJsonArrayPayload(dumpFeedLogs(),serializer())"))
		assertTrue(local.contains("JsonReader(InputStreamReader(input,Charsets.UTF_8))"))
		assertFalse(local.contains("privatesuspendfundumpFeed():FeedBackup"))
		assertTrue(mangaDao.contains("findFirstForMihonExport(limit:Int)"))
		assertTrue(tracksDao.contains("findFirstForBackup(limit:Int)"))
		assertTrue(logsDao.contains("findFirstForBackup(limit:Int)"))
	}

	@Test
	fun `reader-only profiles are included and cover memory is bounded`() {
		val preferences = source("org/koitharu/kotatsu/core/db/dao/PreferencesDao.kt")
		val backup = source("org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt")
		val cover = source("org/koitharu/kotatsu/backup/local/domain/CustomCoverCodec.kt")
		val dumpPrefs = backup
			.substringAfter("privatefundumpMangaPrefs():Flow<MangaPrefsBackup>")
			.substringBefore("privatesuspendfunrestoreFeed")
		val restorePrefs = backup
			.substringAfter("privatesuspendfunrestoreMangaPrefs(")
			.substringBefore("privatesuspendfunrestoreMangaPrefsBatch")

		assertTrue(preferences.contains("findFirstForBackup(limit:Int):List<MangaPrefsEntity>"))
		assertTrue(preferences.contains("findAllForBackup(afterMangaId:Long,limit:Int):List<MangaPrefsEntity>"))
		assertTrue(dumpPrefs.contains("prefsDao.findFirstForBackup(BACKUP_DB_BATCH_SIZE)"))
		assertTrue(dumpPrefs.contains("prefsDao.findAllForBackup(it,BACKUP_DB_BATCH_SIZE)"))
		assertFalse(dumpPrefs.contains("getOverrides()"))
		assertTrue(restorePrefs.contains("if(item.prefs.coverData!=null)"))
		assertTrue(restorePrefs.contains("restoreMangaPrefsBatch(listOf(item),restoredMangaIds)"))
		assertTrue(cover.contains("MAX_COVER_BYTES=8*1024*1024"))
		assertTrue(cover.contains("readBytesLimited(MAX_COVER_BYTES)"))
		assertTrue(cover.contains("coverData.length<=MAX_BASE64_CHARS"))
		assertFalse(cover.contains(".readBytes()"))
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
	fun `feed backup preserves exact chapter ids`() {
		val syncModels = source("org/koitharu/kotatsu/sync/data/model/SyncModels.kt")

		assertTrue(syncModels.contains("@SerialName(\"chapter_ids\")valchapterIds:String=\"\""))
		assertTrue(syncModels.contains("chapterIds=entity.chapterIds"))
		assertTrue(syncModels.contains("chapterIds=chapterIds"))
	}

	@Test
	fun `periodic backup buffers IO and cleans partial targets`() {
		val worker = source("org/koitharu/kotatsu/backup/local/ui/periodical/PeriodicalBackupWorker.kt")
		val storage = source("org/koitharu/kotatsu/backup/local/domain/ExternalBackupStorage.kt")

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
