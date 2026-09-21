package org.koitharu.kotatsu.backup.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import org.koitharu.kotatsu.history.data.HistoryEntity
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

	@Before
	fun setUp() {
		hiltRule.inject()
		database.clearAllTables()
		privateSecurity.includePrivateInBackup = false
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
			assertEquals(601, database.getFavouritesDao().findAll(restoredNormal.categoryId.toLong()).size)
			assertEquals(1, database.getChaptersDao().findAll(primary.id).size)
			assertEquals(1, database.getBookmarksDao().findAll(primary.id).size)
			assertEquals(1, database.getPrivateFavouritesDao().findCategoriesIds(privateManga.id).size)
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
