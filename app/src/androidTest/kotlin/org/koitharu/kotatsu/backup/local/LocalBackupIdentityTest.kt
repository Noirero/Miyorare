package org.koitharu.kotatsu.backup.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.history.domain.HistoryUpdateUseCase
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyAchievementEntity
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyWeeklyStateEntity
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyXpEventEntity
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LocalBackupIdentityTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var repository: LocalBackupRepository

	@Inject
	lateinit var database: MangaDatabase

	@Inject
	lateinit var privateSecurity: PrivateFavouritesSecurityStore

	@Inject
	lateinit var historyRepository: HistoryRepository

	@Inject
	lateinit var appSettings: AppSettings

	@Inject
	lateinit var trackingRepository: org.koitharu.kotatsu.tracker.domain.TrackingRepository

	private var previousIncognito = false

	@After
	fun restoreIncognito() {
		appSettings.isIncognitoModeEnabled = previousIncognito
	}

	@Before
	fun setUp() {
		hiltRule.inject()
		previousIncognito = appSettings.isIncognitoModeEnabled
		appSettings.isIncognitoModeEnabled = false
		database.clearAllTables()
		privateSecurity.includePrivateInBackup = false
	}


	// These repository/database regressions run in the existing Android Runtime suite.
	// Fixtures contain chapters and no favourites/accounts: no live source/tracker requests are needed.
	@Test
	fun actualReaderActivityResumesLatestMangaWithoutFavouriteMembership() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(a)
		saveReader(b)
		assertEquals(b.id, historyRepository.getLastReadOrNull()?.id)
		assertEquals(b.id, historyRepository.observeLastRead().first()?.id)
		assertTrue(database.getFavouritesDao().findCategoriesIds(a.id).isEmpty())
		assertTrue(database.getFavouritesDao().findCategoriesIds(b.id).isEmpty())
	}

	@Test
	fun trackingCreatesProgressWithoutStealingReaderIdentity() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(a)
		assertTrue(historyRepository.advanceFromTracking(b, checkNotNull(b.chapters), 1))
		assertEquals(b.chapters!![1].id, database.getHistoryDao().find(b.id)?.chapterId)
		assertEquals(0L, database.getHistoryDao().find(b.id)?.lastReaderActivityAt)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
	}

	@Test
	fun trackingAdvancesExistingProgressAndPreservesItsReaderClock() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(b)
		val bClock = database.getHistoryDao().find(b.id)!!.lastReaderActivityAt
		saveReader(a)
		assertTrue(historyRepository.advanceFromTracking(b, checkNotNull(b.chapters), 2))
		assertEquals(b.chapters!![2].id, database.getHistoryDao().find(b.id)?.chapterId)
		assertEquals(bClock, database.getHistoryDao().find(b.id)?.lastReaderActivityAt)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
	}

	@Test
	fun trackingOnlyHistoryHasExplicitEmptyResumeState() = runTest {
		val b = resumeManga(902L)
		assertTrue(historyRepository.advanceFromTracking(b, checkNotNull(b.chapters), 1))
		assertNull(historyRepository.getLastReadOrNull())
		assertNull(historyRepository.observeLastRead().first())
		assertEquals(b.id, historyRepository.getLastOrNull()?.id) // History still sees progress.
	}

	@Test
	fun feedMarkReadAndUndoRestoreProgressWithoutStealingResume() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(a)
		setManualProgress(b, 0)
		val previous = database.getHistoryDao().find(b.id)!!
		database.getTrackLogsDao().insert(org.koitharu.kotatsu.tracker.data.TrackLogEntity(
			mangaId = b.id, chapters = "Feed chapter", chapterIds = b.chapters!![2].id.toString(),
			createdAt = 1L, isUnread = true,
		))
		val logsUndo = trackingRepository.markLogsRead(b.id)
		setManualProgress(b, 2)
		assertTrue(database.getTrackLogsDao().findUnreadByManga(b.id).isEmpty())
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		database.getHistoryDao().undoFeedProgress(b.id, previous)
		logsUndo.reverse()
		assertEquals(1, database.getTrackLogsDao().findUnreadByManga(b.id).size)
		assertEquals(previous, database.getHistoryDao().find(b.id))
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		val c = resumeManga(903L)
		setManualProgress(c, 2)
		database.getHistoryDao().undoFeedProgress(c.id, null)
		assertNull(database.getHistoryDao().find(c.id))
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
	}

	@Test
	fun feedUndoDoesNotEraseSubsequentReaderActivity() = runTest {
		val b = resumeManga(902L)
		setManualProgress(b, 2)
		saveReader(b)
		database.getHistoryDao().undoFeedProgress(b.id, null)
		assertEquals(b.id, historyRepository.getLastReadOrNull()?.id)
		val previous = database.getHistoryDao().find(b.id)!!
		setManualProgress(b, 2)
		saveReader(b)
		val afterReading = database.getHistoryDao().find(b.id)!!
		database.getHistoryDao().undoFeedProgress(b.id, previous)
		assertEquals(afterReading, database.getHistoryDao().find(b.id))
	}

	@Test
	fun markCompletedKeepsCompletedProgressWithoutClaimingReaderActivity() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(a)
		historyRepository.addOrUpdate(b, b.chapters!!.last().id, 9, 0, 1f, force = true)
		assertEquals(1f, database.getHistoryDao().find(b.id)!!.percent, 0f)
		assertEquals(9, database.getHistoryDao().find(b.id)?.page)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
	}

	@Test
	fun markCurrentKeepsSelectedPositionUntilReaderActuallySaves() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(a)
		setManualProgress(b, 1)
		assertEquals(b.chapters!![1].id, database.getHistoryDao().find(b.id)?.chapterId)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		saveReader(b, 1)
		assertEquals(b.id, historyRepository.getLastReadOrNull()?.id)
	}

	@Test
	fun readerCanPromoteMangaPreviouslyChangedByTrackingOrFeed() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		val c = resumeManga(903L)
		saveReader(a)
		assertTrue(historyRepository.advanceFromTracking(b, checkNotNull(b.chapters), 1))
		setManualProgress(c, 2)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		saveReader(b)
		assertEquals(b.id, historyRepository.getLastReadOrNull()?.id)
		saveReader(c)
		assertEquals(c.id, historyRepository.getLastReadOrNull()?.id)
	}

	@Test
	fun deletedAndPrivateResumeEntriesRespectExistingIsolation() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(a)
		saveReader(b)
		val categoryId = database.getFavouriteCategoriesDao().insert(category("Private resume", 1, FavouriteSpace.PRIVATE))
		database.getPrivateFavouritesDao().upsert(PrivateFavouriteEntity(b.id, categoryId, 0, false, 1L, 0L))
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		org.koitharu.kotatsu.favourites.vault.PrivateFavouritesIsolation.setDisabled(database, true)
		assertEquals(b.id, historyRepository.getLastReadOrNull()?.id)
		org.koitharu.kotatsu.favourites.vault.PrivateFavouritesIsolation.setDisabled(database, false)
		historyRepository.delete(a)
		assertNull(historyRepository.getLastReadOrNull())
	}

	@Test
	fun incognitoReaderAndTrackingDoNotCreateResumeEvidence() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(a)
		appSettings.isIncognitoModeEnabled = true
		saveReader(b)
		assertFalse(historyRepository.advanceFromTracking(b, checkNotNull(b.chapters), 1))
		assertNull(database.getHistoryDao().find(b.id))
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		// Existing explicit manual force policy still allows progress; it never claims a Reader save.
		setManualProgress(b, 1)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
	}

	@Test
	fun restoreAndCloudProgressPreserveDeviceLocalResumeMarkers() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(a)
		val original = database.getHistoryDao().find(a.id)!!
		val dao = database.getHistoryDao()
		// Native/Mihon import uses progress-only upsert; cloud sync uses upsertForSync.
		dao.upsert(original.copy(updatedAt = original.updatedAt + 100, lastReaderActivityAt = 0L))
		dao.upsertForSync(original.copy(updatedAt = original.updatedAt + 200, lastReaderActivityAt = 0L))
		assertEquals(original.lastReaderActivityAt, dao.find(a.id)?.lastReaderActivityAt)
		database.getMangaDao().upsert(b.toEntity(), emptyList())
		dao.upsertForSync(original.copy(mangaId = b.id, updatedAt = Long.MAX_VALUE))
		assertEquals(0L, dao.find(b.id)?.lastReaderActivityAt)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
	}


	@Test
	fun mangaIdentityMigrationPreservesEvidenceWithoutMintingNewReadingTime() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(b)
		saveReader(a)
		val source = database.getHistoryDao().find(a.id)!!
		database.getHistoryDao().upsertForMangaMigration(source.copy(mangaId = b.id))
		database.getHistoryDao().delete(a.id)
		assertEquals(source.lastReaderActivityAt, database.getHistoryDao().find(b.id)?.lastReaderActivityAt)
		assertEquals(b.id, historyRepository.getLastReadOrNull()?.id)
	}

	@Test
	fun portableBackupRestoresProgressWithoutClaimingNewDeviceReading() = runTest {
		val a = resumeManga(901L)
		saveReader(a)
		val savedClock = database.getHistoryDao().find(a.id)!!.lastReaderActivityAt
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("resume_backup_", ".zip", context.cacheDir)
		try {
			ZipOutputStream(file.outputStream()).use { repository.createBackup(it, progress = null) }
			ZipInputStream(file.inputStream()).use {
				assertTrue(repository.restoreBackup(it, setOf(BackupSection.HISTORY), progress = null).isAllSuccess)
			}
			assertEquals(savedClock, database.getHistoryDao().find(a.id)?.lastReaderActivityAt)
			database.clearAllTables()
			ZipInputStream(file.inputStream()).use {
				assertTrue(repository.restoreBackup(it, setOf(BackupSection.HISTORY), progress = null).isAllSuccess)
			}
			assertEquals(0L, database.getHistoryDao().find(a.id)?.lastReaderActivityAt)
			assertNull(historyRepository.getLastReadOrNull())
			assertEquals(a.id, historyRepository.getLastOrNull()?.id)
		} finally {
			file.delete()
		}
	}


	@Test
	fun readerSavesHaveDeterministicOrderAcrossClockTiesAndRollback() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		setManualProgress(a, 0)
		setManualProgress(b, 0)
		val dao = database.getHistoryDao()
		dao.recordReaderActivity(a.id, 100L)
		dao.recordReaderActivity(b.id, 100L)
		assertEquals(101L, dao.find(b.id)?.lastReaderActivityAt)
		assertEquals(b.id, historyRepository.getLastReadOrNull()?.id)
		dao.recordReaderActivity(a.id, 90L)
		assertEquals(102L, dao.find(a.id)?.lastReaderActivityAt)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
	}


	@Test
	fun nonReaderResurrectionDoesNotReviveDeletedReaderIdentity() = runTest {
		val a = resumeManga(901L)
		val b = resumeManga(902L)
		saveReader(a)
		saveReader(b)
		val oldReaderHistory = database.getHistoryDao().find(b.id)!!
		historyRepository.delete(b)
		assertFalse(historyRepository.advanceFromTracking(b, checkNotNull(b.chapters), 2))
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		setManualProgress(b, 2)
		assertEquals(0L, database.getHistoryDao().find(b.id)?.lastReaderActivityAt)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		saveReader(b)
		historyRepository.delete(b)
		database.getHistoryDao().upsertForSync(oldReaderHistory)
		assertEquals(0L, database.getHistoryDao().find(b.id)?.lastReaderActivityAt)
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		saveReader(b)
		assertEquals(b.id, historyRepository.getLastReadOrNull()?.id)
		val deletedUndo = historyRepository.delete(setOf(b.id))
		assertEquals(a.id, historyRepository.getLastReadOrNull()?.id)
		deletedUndo.reverse() // explicit history deletion undo still restores the original reading entry
		assertEquals(b.id, historyRepository.getLastReadOrNull()?.id)
	}

	private fun resumeManga(id: Long): Manga = SampleData.mangaDetails.copy(id = id, isNsfw = false, source = org.koitharu.kotatsu.parsers.model.MangaSource("TEST_CONTINUE_READING"))

	private suspend fun saveReader(manga: Manga, index: Int = 0) {
		HistoryUpdateUseCase(historyRepository)(manga, ReaderState(manga.chapters!![index].id, 1, 0), 0.1f)
	}

	private suspend fun setManualProgress(manga: Manga, index: Int) {
		historyRepository.addOrUpdate(manga, manga.chapters!![index].id, 0, 0, (index + 1f) / manga.chapters!!.size, force = true)
	}

	@Test
	fun nativeBackupRestoreKeepsExactCategoryMangaMembership() = runTest {
		val categoriesDao = database.getFavouriteCategoriesDao()
		val categoryA = categoriesDao.insert(category(title = "Category A", sortKey = 1))
		val categoryB = categoriesDao.insert(category(title = "Category B", sortKey = 2))

		val manga12345 = manga(id = 12_345L, title = "Manga 12345", url = "/manga/12345")
		val manga67890 = manga(id = 67_890L, title = "Manga 67890", url = "/manga/67890")
		database.getMangaDao().upsert(manga12345, emptyList())
		database.getMangaDao().upsert(manga67890, emptyList())

		database.getFavouritesDao().upsert(
			FavouriteEntity(
				mangaId = manga12345.id,
				categoryId = categoryA,
				sortKey = 10,
				isPinned = false,
				createdAt = 1_000L,
				deletedAt = 0L,
			),
		)
		database.getFavouritesDao().upsert(
			FavouriteEntity(
				mangaId = manga67890.id,
				categoryId = categoryB,
				sortKey = 20,
				isPinned = true,
				createdAt = 2_000L,
				deletedAt = 0L,
			),
		)

		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("local_backup_identity_", ".zip", context.cacheDir)
		try {
			ZipOutputStream(file.outputStream()).use { output ->
				repository.createBackup(output, progress = null)
			}

			database.clearAllTables()

			// Simulate a real restore target where the source backup's category id is already occupied
			// by an unrelated category/manga. Restore must remap the category id, never the manga id.
			val conflictingTargetCategory = category(title = "Existing target category", sortKey = 99)
				.copy(categoryId = categoryA.toInt())
			database.getFavouriteCategoriesDao().insert(conflictingTargetCategory)
			val unrelatedManga = manga(id = 99_999L, title = "Manga ABCDE", url = "/manga/abcde")
			database.getMangaDao().upsert(unrelatedManga, emptyList())
			database.getFavouritesDao().upsert(
				FavouriteEntity(
					mangaId = unrelatedManga.id,
					categoryId = categoryA,
					sortKey = 999,
					isPinned = false,
					createdAt = 999L,
					deletedAt = 0L,
				),
			)

			val result = ZipInputStream(file.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = setOf(BackupSection.CATEGORIES, BackupSection.FAVOURITES),
					progress = null,
				)
			}
			assertTrue("Native backup restore reported failures: ${result.failures}", result.isAllSuccess)

			val restoredCategories = database.getFavouriteCategoriesDao().findAll().associateBy { it.title }
			val restoredA = checkNotNull(restoredCategories["Category A"])
			val restoredB = checkNotNull(restoredCategories["Category B"])
			val unrelatedCategory = checkNotNull(restoredCategories["Existing target category"])

			val categoryAMangaIds = database.getFavouritesDao()
				.findAll(restoredA.categoryId.toLong())
				.mapTo(linkedSetOf()) { it.manga.id }
			val categoryBMangaIds = database.getFavouritesDao()
				.findAll(restoredB.categoryId.toLong())
				.mapTo(linkedSetOf()) { it.manga.id }
			val unrelatedMangaIds = database.getFavouritesDao()
				.findAll(unrelatedCategory.categoryId.toLong())
				.mapTo(linkedSetOf()) { it.manga.id }

			assertEquals(linkedSetOf(12_345L), categoryAMangaIds)
			assertEquals(linkedSetOf(67_890L), categoryBMangaIds)
			assertEquals(linkedSetOf(99_999L), unrelatedMangaIds)
		} finally {
			file.delete()
		}
	}


	@Test
	fun nativeBackupRoundTripPreservesReaderJourneyProgressionWithoutDuplicateXp() = runTest {
		val dao = database.getReaderJourneyDao()
		dao.awardCompletion(
			mangaId = 900_001L,
			chapterId = 900_101L,
			isNovel = false,
			readingUnits = 0,
			baseXp = 10,
			completedAt = 1_000L,
		)
		dao.awardBonusEvent(
			ReaderJourneyXpEventEntity(
				eventKey = "weekly-bonus:2026-09-21",
				source = "WEEKLY_BONUS",
				xp = 50,
				occurredAt = 2_000L,
				context = "2026-09-21",
				profileDelta = true,
			),
		)
		dao.upsertWeeklyState(
			ReaderJourneyWeeklyStateEntity(
				weekKey = "2026-09-21",
				taskIds = "READ_3_CHAPTERS,READ_2_DAYS,READ_2_TITLES,READ_1_NOVEL,READ_5_CHAPTERS,TRY_NEW_TITLE",
				rerollsUsed = 1,
				updatedAt = 2_000L,
			),
		)
		dao.mergeAchievement(
			ReaderJourneyAchievementEntity(
				achievementId = "FIRST_CHAPTER",
				unlockedAt = 1_000L,
			),
		)
		dao.reconcileXpFloor(100L)
		dao.rebuildProfileFromLedger()
		assertEquals(100L, dao.getProfile()?.totalXp)

		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("local_backup_reader_journey_", ".zip", context.cacheDir)
		try {
			ZipOutputStream(file.outputStream()).use { output ->
				repository.createBackup(output, progress = null)
			}

			database.clearAllTables()
			val firstRestore = ZipInputStream(file.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = setOf(BackupSection.STATS),
					progress = null,
				)
			}
			assertTrue("Reader Journey restore reported failures: ${firstRestore.failures}", firstRestore.isAllSuccess)

			val restored = database.getReaderJourneyDao()
			assertEquals(100L, restored.getProfile()?.totalXp)
			assertEquals(10L, restored.getAllChapterAwards().single().awardedXp)
			assertEquals(50, restored.getXpEvent("weekly-bonus:2026-09-21")?.xp)
			assertEquals(1, restored.getWeeklyState("2026-09-21")?.rerollsUsed)
			assertEquals("FIRST_CHAPTER", restored.getAllAchievements().single().achievementId)

			val secondRestore = ZipInputStream(file.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = setOf(BackupSection.STATS),
					progress = null,
				)
			}
			assertTrue("Second Reader Journey restore reported failures: ${secondRestore.failures}", secondRestore.isAllSuccess)
			assertEquals(100L, restored.getProfile()?.totalXp)
			assertEquals(1, restored.getAllXpEvents().count { it.eventKey == "weekly-bonus:2026-09-21" })
			assertEquals(1, restored.getAllChapterAwards().size)
			assertEquals(1, restored.getAllAchievements().size)
		} finally {
			file.delete()
		}
	}

	@Test
	fun nativeBackupRestoreKeepsReaderOnlyProfileWithoutMetadataOverrides() = runTest {
		val manga = manga(id = 42_424L, title = "Reader Profile Only", url = "/manga/reader-profile")
		database.getMangaDao().upsert(manga, emptyList())
		val expected = MangaPrefsEntity(
			mangaId = manga.id,
			mode = 4,
			cfBrightness = 0.25f,
			cfContrast = -0.15f,
			cfInvert = true,
			cfGrayscale = true,
			cfBookEffect = true,
			titleOverride = null,
			coverUrlOverride = null,
			contentRatingOverride = null,
			authorOverride = null,
			artistOverride = null,
			descriptionOverride = null,
			mergeScanlators = false,
		)
		database.getPreferencesDao().upsert(expected)

		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("local_backup_reader_profile_", ".zip", context.cacheDir)
		try {
			ZipOutputStream(file.outputStream()).use { output ->
				repository.createBackup(output, progress = null)
			}
			database.clearAllTables()

			val result = ZipInputStream(file.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = setOf(BackupSection.MANGA_PREFS),
					progress = null,
				)
			}
			assertTrue("Reader profile restore reported failures: ${result.failures}", result.isAllSuccess)

			val restored = checkNotNull(database.getPreferencesDao().find(manga.id))
			assertEquals(expected.mode, restored.mode)
			assertEquals(expected.cfBrightness, restored.cfBrightness)
			assertEquals(expected.cfContrast, restored.cfContrast)
			assertEquals(expected.cfInvert, restored.cfInvert)
			assertEquals(expected.cfGrayscale, restored.cfGrayscale)
			assertEquals(expected.cfBookEffect, restored.cfBookEffect)
			assertEquals(null, restored.titleOverride)
			assertEquals(null, restored.coverUrlOverride)
			assertFalse(restored.mergeScanlators)
		} finally {
			file.delete()
		}
	}


	@Test
	fun nativeBackupRoundTripPreservesLargeLibraryHistoryChaptersBookmarksAndPrivatePolicy() = runTest {
		val categoriesDao = database.getFavouriteCategoriesDao()
		val normalCategoryId = categoriesDao.insert(category("Acceptance Normal", 1))
		val privateCategoryId = categoriesDao.insert(
			category("Acceptance Private", 2, FavouriteSpace.PRIVATE),
		)

		val primary = manga(id = 700_001L, title = "Acceptance Primary", url = "/manga/acceptance-primary")
		database.getMangaDao().upsert(primary, emptyList())
		val chapter = ChapterEntity(
			chapterId = 710_001L,
			mangaId = primary.id,
			title = "Chapter 1",
			number = 1f,
			volume = 1,
			url = "/chapter/1",
			scanlator = "Acceptance",
			uploadDate = 123_456L,
			branch = "main",
			source = primary.source,
			index = 0,
		)
		database.getChaptersDao().replaceAll(primary.id, listOf(chapter))
		database.getFavouritesDao().upsert(
			FavouriteEntity(
				mangaId = primary.id,
				categoryId = normalCategoryId,
				sortKey = 1,
				isPinned = true,
				createdAt = 10L,
				deletedAt = 0L,
			),
		)
		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = primary.id,
				createdAt = 100L,
				updatedAt = 200L,
				chapterId = chapter.chapterId,
				page = 7,
				scroll = 0.5f,
				percent = 0.25f,
				deletedAt = 0L,
				chaptersCount = 1,
			),
		)
		database.getBookmarksDao().upsert(
			listOf(
				BookmarkEntity(
					mangaId = primary.id,
					pageId = 720_001L,
					chapterId = chapter.chapterId,
					page = 7,
					scroll = 33,
					imageUrl = "https://fixture.example/page/7",
					createdAt = 300L,
					percent = 0.25f,
					note = "keep-me",
				),
			),
		)

		// Cross the 256-row keyset window more than twice so this is a real multi-batch backup,
		// not merely a source-code assertion about pagination.
		repeat(600) { index ->
			val item = manga(
				id = 701_000L + index,
				title = "Scale $index",
				url = "/manga/scale-$index",
			)
			database.getMangaDao().upsert(item, emptyList())
			database.getFavouritesDao().insert(
				FavouriteEntity(
					mangaId = item.id,
					categoryId = normalCategoryId,
					sortKey = index + 10,
					isPinned = false,
					createdAt = 1_000L + index,
					deletedAt = 0L,
				),
			)
		}

		val privateManga = manga(
			id = 799_001L,
			title = "Acceptance Private Manga",
			url = "/manga/acceptance-private",
		)
		database.getMangaDao().upsert(privateManga, emptyList())
		database.getPrivateFavouritesDao().upsert(
			PrivateFavouriteEntity(
				mangaId = privateManga.id,
				categoryId = privateCategoryId,
				sortKey = 5,
				isPinned = false,
				createdAt = 500L,
				deletedAt = 0L,
			),
		)

		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val offFile = File.createTempFile("local_backup_private_off_", ".zip", context.cacheDir)
		val backupFile = File.createTempFile("local_backup_acceptance_", ".zip", context.cacheDir)
		try {
			privateSecurity.includePrivateInBackup = false
			ZipOutputStream(offFile.outputStream()).use { repository.createBackup(it, progress = null) }
			val offEntries = ZipInputStream(offFile.inputStream()).use { input ->
				buildSet {
					var entry = input.nextEntry
					while (entry != null) {
						add(entry.name)
						input.closeEntry()
						entry = input.nextEntry
					}
				}
			}
			assertFalse(
				"Private payload must not leak into a routine backup when opt-in is disabled",
				LocalBackupRepository.PRIVATE_FAVOURITES_ENTRY in offEntries,
			)

			privateSecurity.includePrivateInBackup = true
			ZipOutputStream(backupFile.outputStream()).use { repository.createBackup(it, progress = null) }
			val onEntries = ZipInputStream(backupFile.inputStream()).use { input ->
				buildSet {
					var entry = input.nextEntry
					while (entry != null) {
						add(entry.name)
						input.closeEntry()
						entry = input.nextEntry
					}
				}
			}
			assertTrue(LocalBackupRepository.PRIVATE_FAVOURITES_ENTRY in onEntries)

			database.clearAllTables()
			val sections = setOf(
				BackupSection.CATEGORIES,
				BackupSection.FAVOURITES,
				BackupSection.HISTORY,
				BackupSection.CHAPTERS,
				BackupSection.BOOKMARKS,
			)
			val restored = ZipInputStream(backupFile.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = sections,
					progress = null,
					restorePrivateFavourites = true,
				)
			}
			assertTrue("Full native restore reported failures: ${restored.failures}", restored.isAllSuccess)

			val restoredNormal = checkNotNull(
				database.getFavouriteCategoriesDao().findAll().singleOrNull { it.title == "Acceptance Normal" },
			)
			assertEquals(
				601,
				database.getFavouritesDao().findAll(restoredNormal.categoryId.toLong()).size,
			)
			val restoredHistory = checkNotNull(database.getHistoryDao().find(primary.id))
			assertEquals(chapter.chapterId, restoredHistory.chapterId)
			assertEquals(7, restoredHistory.page)
			assertEquals(0.25f, restoredHistory.percent)
			val restoredChapters = database.getChaptersDao().findAll(primary.id)
			assertEquals(1, restoredChapters.size)
			assertEquals(chapter.chapterId, restoredChapters.single().chapterId)
			val restoredBookmarks = database.getBookmarksDao().findAll(primary.id)
			assertEquals(1, restoredBookmarks.size)
			assertEquals("keep-me", restoredBookmarks.single().note)

			val restoredPrivateCategory = checkNotNull(
				database.getFavouriteCategoriesDao()
					.findAllInSpace(FavouriteSpace.PRIVATE.dbValue)
					.singleOrNull { it.title == "Acceptance Private" },
			)
			assertEquals(
				listOf(restoredPrivateCategory.categoryId.toLong()),
				database.getPrivateFavouritesDao().findCategoriesIds(privateManga.id),
			)
			assertTrue(database.getPrivateFavouritesDao().isPrivateOnly(privateManga.id))

			// Retry the same restore to prove idempotence: rows must not multiply.
			val retry = ZipInputStream(backupFile.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = sections,
					progress = null,
					restorePrivateFavourites = true,
				)
			}
			assertTrue("Retry restore reported failures: ${retry.failures}", retry.isAllSuccess)
			assertEquals(
				1,
				database.getFavouriteCategoriesDao().findAll().count { it.title == "Acceptance Normal" },
			)
			assertEquals(
				1,
				database.getFavouriteCategoriesDao()
					.findAllInSpace(FavouriteSpace.PRIVATE.dbValue)
					.count { it.title == "Acceptance Private" },
			)
			assertEquals(601, database.getFavouritesDao().findAll(restoredNormal.categoryId.toLong()).size)
			assertEquals(1, database.getChaptersDao().findAll(primary.id).size)
			assertEquals(1, database.getBookmarksDao().findAll(primary.id).size)
			assertEquals(
				listOf(restoredPrivateCategory.categoryId.toLong()),
				database.getPrivateFavouritesDao().findCategoriesIds(privateManga.id),
			)
		} finally {
			privateSecurity.includePrivateInBackup = false
			offFile.delete()
			backupFile.delete()
		}
	}

	@Test
	fun malformedNativeBackupNeverReportsSuccess() = runTest {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("local_backup_malformed_", ".zip", context.cacheDir)
		try {
			ZipOutputStream(file.outputStream()).use { output ->
				output.putNextEntry(java.util.zip.ZipEntry(BackupSection.HISTORY.entryName))
				output.write("[{\"broken\":".toByteArray())
				output.closeEntry()
			}
			val outcome = runCatching {
				ZipInputStream(file.inputStream()).use { input ->
					repository.restoreBackup(
						input = input,
						sections = setOf(BackupSection.HISTORY),
						progress = null,
					)
				}
			}
			assertTrue(
				"Malformed backup must fail or return a CompositeResult with failures",
				outcome.isFailure || outcome.getOrThrow().isAllSuccess.not(),
			)
		} finally {
			file.delete()
		}
	}

	private fun category(title: String, sortKey: Int, space: FavouriteSpace = FavouriteSpace.NORMAL) = FavouriteCategoryEntity(
		categoryId = 0,
		createdAt = sortKey.toLong(),
		sortKey = sortKey,
		title = title,
		order = "NEWEST",
		track = true,
		downloadNewChapters = false,
		isVisibleInLibrary = true,
		deletedAt = 0L,
		space = space.dbValue,
	)

	private fun manga(id: Long, title: String, url: String) = MangaEntity(
		id = id,
		title = title,
		altTitles = null,
		url = url,
		publicUrl = "https://fixture.example$url",
		rating = -1f,
		isNsfw = false,
		contentRating = null,
		coverUrl = "",
		largeCoverUrl = null,
		state = null,
		authors = null,
		description = null,
		source = "MIHON_123",
		sourceTitle = "Fixture Source",
	)
}
