package org.koitharu.kotatsu.backup.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
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

			val result = ZipInputStream(file.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = setOf(BackupSection.CATEGORIES, BackupSection.FAVOURITES),
					progress = null,
				)
			}
			assertFalse("Native backup restore reported a failure", result.isFailure)

			val restoredCategories = database.getFavouriteCategoriesDao().findAll().associateBy { it.title }
			val restoredA = checkNotNull(restoredCategories["Category A"])
			val restoredB = checkNotNull(restoredCategories["Category B"])

			val categoryAMangaIds = database.getFavouritesDao()
				.findAll(restoredA.categoryId.toLong())
				.mapTo(linkedSetOf()) { it.manga.id }
			val categoryBMangaIds = database.getFavouritesDao()
				.findAll(restoredB.categoryId.toLong())
				.mapTo(linkedSetOf()) { it.manga.id }

			assertEquals(linkedSetOf(12_345L), categoryAMangaIds)
			assertEquals(linkedSetOf(67_890L), categoryBMangaIds)
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
