package org.koitharu.kotatsu.alternatives.domain

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.config.MangaReaderProfileStore
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyChapterEntity
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity

@RunWith(AndroidJUnit4::class)
class LosslessMigrationTransferRegressionTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun sourceReplacementRekeysDurableStateWithoutTouchingPhysicalDownload() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
		val settings = AppSettings(context)
		val oldId = 981_001L
		val newId = 981_002L
		settings.setChapterReadOverrides(oldId, emptyMap())
		settings.setChapterReadOverrides(newId, emptyMap())
		try {
			val oldChapter = chapter(id = 981_101L, number = 7f, sourceName = "MIGRATION_OLD", url = "/old/chapter-7")
			val newChapter = chapter(id = 981_201L, number = 7f, sourceName = "MIGRATION_NEW", url = "/new/chapter-7")
			val oldManga = manga(oldId, "MIGRATION_OLD", "/old/title", oldChapter)
			val newManga = manga(newId, "MIGRATION_NEW", "/new/title", newChapter)
			persistManga(database, oldManga)
			persistManga(database, newManga)

			database.getBookmarksDao().insert(
				BookmarkEntity(
					mangaId = oldId,
					pageId = 981_301L,
					chapterId = oldChapter.id,
					page = 3,
					scroll = 0,
					imageUrl = "https://fixture.invalid/page.jpg",
					createdAt = 10L,
					percent = 0.3f,
					note = "bookmark-note",
				),
			)
			database.getHistoryDao().insert(
				HistoryEntity(
					mangaId = oldId,
					createdAt = 20L,
					updatedAt = 30L,
					chapterId = oldChapter.id,
					page = 4,
					scroll = 0.5f,
					percent = 0.7f,
					deletedAt = 0L,
					chaptersCount = 1,
				),
			)
			database.getStatsDao().upsert(
				StatsEntity(oldId, startedAt = 40L, duration = 50L, pages = 6, chapters = 1),
			)
			database.getTracksDao().upsert(
				TrackEntity(
					mangaId = oldId,
					lastChapterId = oldChapter.id,
					newChapters = 2,
					lastCheckTime = 60L,
					lastChapterDate = 70L,
					lastResult = TrackEntity.RESULT_HAS_UPDATE,
					lastError = null,
				),
			)
			database.getTrackLogsDao().insert(
				TrackLogEntity(
					id = 981_401L,
					mangaId = oldId,
					chapters = "Chapter 7",
					chapterIds = oldChapter.id.toString(),
					createdAt = 80L,
					isUnread = true,
				),
			)
			database.getChapterPersonalDao().upsert(
				ChapterPersonalEntity(
					mangaId = oldId,
					source = oldChapter.source.name,
					url = oldChapter.url,
					rating = 5,
					note = "chapter-note",
				),
			)
			database.getReaderJourneyDao().mergeChapterAward(
				ReaderJourneyChapterEntity(
					mangaId = oldId,
					chapterId = oldChapter.id,
					isNovel = false,
					readingUnits = 12,
					completionCount = 1,
					awardedXp = 13L,
					firstCompletedAt = 90L,
					lastCompletedAt = 90L,
				),
			)
			val physicalPath = "/storage/emulated/0/Miyorare/Downloads/fixture"
			database.getFavouriteDownloadIndexDao().upsert(
				FavouriteDownloadIndexEntity(oldId, FavouriteSpace.NORMAL.dbValue, physicalPath),
			)
			settings.setChapterReadOverrides(oldId, mapOf(oldChapter.id to true))

			val transfer = LosslessMigrationTransfer(
				database = database,
				settings = settings,
				notesRepository = MangaNotesRepository(context),
				readerProfileStore = MangaReaderProfileStore(context),
			)
			val plan = transfer.prepare(oldManga, newManga)
			val preparedOverrides = transfer.prepareReadOverrides(plan)
			database.withTransaction { transfer.applyRoom(plan) }
			transfer.finishReadOverrides(plan)

			assertTrue(preparedOverrides)
			assertTrue(database.getBookmarksDao().findAll(oldId).isEmpty())
			assertEquals(newChapter.id, database.getBookmarksDao().findAll(newId).single().chapterId)
			assertNull(database.getHistoryDao().find(oldId))
			assertEquals(newChapter.id, database.getHistoryDao().find(newId)?.chapterId)
			assertTrue(database.getStatsDao().findAll(oldId).isEmpty())
			assertEquals(50L, database.getStatsDao().findAll(newId).single().duration)
			assertNull(database.getTracksDao().find(oldId))
			assertEquals(newChapter.id, database.getTracksDao().find(newId)?.lastChapterId)
			assertTrue(database.getTrackLogsDao().findAllForManga(oldId).isEmpty())
			assertEquals(newChapter.id.toString(), database.getTrackLogsDao().findAllForManga(newId).single().chapterIds)
			assertTrue(database.getChapterPersonalDao().findAll(listOf(oldId)).isEmpty())
			val personal = database.getChapterPersonalDao().find(newId, newChapter.source.name, newChapter.url)
			assertEquals(5, personal?.rating)
			assertEquals("chapter-note", personal?.note)
			val journey = database.getReaderJourneyDao().getAllChapterAwards()
			assertFalse(journey.any { it.mangaId == oldId })
			assertTrue(journey.any { it.mangaId == newId && it.chapterId == newChapter.id && it.awardedXp == 13L })
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, oldId))
			assertEquals(
				physicalPath,
				database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, newId)?.path,
			)
			assertTrue(settings.getChapterReadOverrides(oldId).isEmpty())
			assertEquals(mapOf(newChapter.id to true), settings.getChapterReadOverrides(newId))
		} finally {
			settings.setChapterReadOverrides(oldId, emptyMap())
			settings.setChapterReadOverrides(newId, emptyMap())
			database.close()
		}
	}

	private suspend fun persistManga(database: MangaDatabase, manga: Manga) {
		val tags = manga.tags.toEntities()
		database.getTagsDao().upsert(tags)
		database.getMangaDao().upsert(manga.toEntity(), tags)
	}

	private fun manga(id: Long, sourceName: String, url: String, chapter: MangaChapter): Manga =
		SampleData.mangaDetails.copy(
			id = id,
			source = MissingMangaSource(sourceName),
			url = url,
			publicUrl = "https://fixture.invalid$url",
			chapters = listOf(chapter),
		)

	private fun chapter(id: Long, number: Float, sourceName: String, url: String) = MangaChapter(
		id = id,
		title = "Chapter $number",
		number = number,
		volume = 0,
		url = url,
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MissingMangaSource(sourceName),
	)
}
