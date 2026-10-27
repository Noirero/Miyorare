package org.koitharu.kotatsu.alternatives.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
import org.koitharu.kotatsu.core.prefs.ChapterReadOverridesMigrationStore
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

@RunWith(AndroidJUnit4::class)
class LosslessMigrationCopyRegressionTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun copyKeepsSourceAndClonesTransferableStateWithoutDuplicatingActivityOwnership() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
		val settings = AppSettings(context)
		val notes = MangaNotesRepository(context)
		val profiles = MangaReaderProfileStore(context)
		val oldId = 983_001L
		val newId = 983_002L
		settings.setChapterReadOverrides(oldId, emptyMap())
		settings.setChapterReadOverrides(newId, emptyMap())
		notes.set(oldId, null)
		notes.set(newId, null)
		profiles.clear(oldId)
		profiles.clear(newId)
		try {
			val oldChapter = chapter(id = 983_101L, number = 9f, sourceName = "COPY_OLD", url = "/old/chapter-9")
			val newChapter = chapter(id = 983_201L, number = 9f, sourceName = "COPY_NEW", url = "/new/chapter-9")
			val oldManga = manga(oldId, "COPY_OLD", "/old/title", oldChapter)
			val newManga = manga(newId, "COPY_NEW", "/new/title", newChapter)
			persistManga(database, oldManga)
			persistManga(database, newManga)

			database.getHistoryDao().insert(
				HistoryEntity(
					mangaId = oldId,
					createdAt = 10L,
					updatedAt = 20L,
					chapterId = oldChapter.id,
					page = 4,
					scroll = 0.5f,
					percent = 0.8f,
					deletedAt = 0L,
					chaptersCount = 1,
				),
			)
			database.getBookmarksDao().insert(
				BookmarkEntity(
					mangaId = oldId,
					pageId = 983_301L,
					chapterId = oldChapter.id,
					page = 3,
					scroll = 0,
					imageUrl = "https://fixture.invalid/copy-page.jpg",
					createdAt = 30L,
					percent = 0.3f,
					note = "bookmark-copy",
				),
			)
			database.getTracksDao().upsert(
				TrackEntity(
					mangaId = oldId,
					lastChapterId = oldChapter.id,
					newChapters = 1,
					lastCheckTime = 40L,
					lastChapterDate = 50L,
					lastResult = TrackEntity.RESULT_HAS_UPDATE,
					lastError = null,
				),
			)
			database.getChapterPersonalDao().upsert(
				ChapterPersonalEntity(
					mangaId = oldId,
					source = oldChapter.source.name,
					url = oldChapter.url,
					rating = 4,
					note = "copy-chapter-note",
				),
			)
			notes.set(oldId, "copy-manga-note")
			profiles.saveCurrent(oldId, settings)
			settings.setChapterReadOverrides(oldId, mapOf(oldChapter.id to true))

			// Activity and physical-ownership ledgers deliberately remain on the source for Copy.
			database.getStatsDao().upsert(StatsEntity(oldId, startedAt = 60L, duration = 70L, pages = 8, chapters = 1))
			database.getReaderJourneyDao().mergeChapterAward(
				ReaderJourneyChapterEntity(
					mangaId = oldId,
					chapterId = oldChapter.id,
					isNovel = false,
					readingUnits = 8,
					completionCount = 1,
					awardedXp = 11L,
					firstCompletedAt = 80L,
					lastCompletedAt = 80L,
				),
			)
			val physicalPath = "/storage/emulated/0/Miyorare/Downloads/copy-fixture"
			database.getFavouriteDownloadIndexDao().upsert(
				FavouriteDownloadIndexEntity(oldId, FavouriteSpace.NORMAL.dbValue, physicalPath),
			)

			val transfer = LosslessMigrationTransfer(database, settings, notes, profiles)
			val copy = LosslessMigrationCopyUseCase(
				database = database,
				mangaReaderProfileStore = profiles,
				mangaNotesRepository = notes,
				chapterReadOverridesMigrationStore = ChapterReadOverridesMigrationStore(context),
				losslessMigrationTransfer = transfer,
			)
			copy(oldManga, newManga)

			// Source remains fully usable.
			assertEquals(oldChapter.id, database.getHistoryDao().find(oldId)?.chapterId)
			assertEquals(oldChapter.id, database.getBookmarksDao().findAll(oldId).single().chapterId)
			assertEquals(oldChapter.id, database.getTracksDao().find(oldId)?.lastChapterId)
			assertEquals("copy-manga-note", notes.get(oldId))
			assertTrue(profiles.get(oldId) != null)
			assertEquals(mapOf(oldChapter.id to true), settings.getChapterReadOverrides(oldId))

			// Destination receives the transferable reading/library state with conservative chapter mapping.
			assertEquals(newChapter.id, database.getHistoryDao().find(newId)?.chapterId)
			assertEquals(newChapter.id, database.getBookmarksDao().findAll(newId).single().chapterId)
			assertEquals(newChapter.id, database.getTracksDao().find(newId)?.lastChapterId)
			val personal = database.getChapterPersonalDao().find(newId, newChapter.source.name, newChapter.url)
			assertEquals(4, personal?.rating)
			assertEquals("copy-chapter-note", personal?.note)
			assertEquals("copy-manga-note", notes.get(newId))
			assertTrue(profiles.get(newId) != null)
			assertEquals(mapOf(newChapter.id to true), settings.getChapterReadOverrides(newId))

			// Copy must not manufacture duplicate activity or claim the same physical download twice.
			assertEquals(70L, database.getStatsDao().findAll(oldId).single().duration)
			assertTrue(database.getStatsDao().findAll(newId).isEmpty())
			val journey = database.getReaderJourneyDao().getAllChapterAwards()
			assertTrue(journey.any { it.mangaId == oldId && it.chapterId == oldChapter.id })
			assertTrue(journey.none { it.mangaId == newId })
			assertEquals(physicalPath, database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, oldId)?.path)
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, newId))
		} finally {
			settings.setChapterReadOverrides(oldId, emptyMap())
			settings.setChapterReadOverrides(newId, emptyMap())
			notes.set(oldId, null)
			notes.set(newId, null)
			profiles.clear(oldId)
			profiles.clear(newId)
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
