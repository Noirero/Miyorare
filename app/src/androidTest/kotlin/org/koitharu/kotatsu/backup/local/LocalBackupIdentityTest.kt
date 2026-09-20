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
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
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

	@Before
	fun setUp() {
		hiltRule.inject()
		database.clearAllTables()
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

	private fun category(title: String, sortKey: Int) = FavouriteCategoryEntity(
		categoryId = 0,
		createdAt = sortKey.toLong(),
		sortKey = sortKey,
		title = title,
		order = "NEWEST",
		track = true,
		downloadNewChapters = false,
		isVisibleInLibrary = true,
		deletedAt = 0L,
		space = FavouriteSpace.NORMAL.dbValue,
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
