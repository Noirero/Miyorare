package org.koitharu.kotatsu.history.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.migrations.Migration50To51
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.list.domain.ListSortOrder
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PrivateHistoryIsolationRuntimeTest {

	@get:Rule
	val hilt = HiltAndroidRule(this)

	@Inject lateinit var database: MangaDatabase
	@Inject lateinit var favouritesRepository: FavouritesRepository

	@Before
	fun prepare() = runBlocking {
		hilt.inject()
		database.clearAllTables()
	}

	@Test
	fun privateReadSpaceStaysOutOfNormalHistoryWithoutFavouriteMembership() = runBlocking {
		val manga = SampleData.mangaDetails
		database.getMangaDao().upsert(manga.toEntity())
		val dao = database.getHistoryDao()
		val privateHistory = HistoryEntity(
			mangaId = manga.id,
			createdAt = 1L,
			updatedAt = 2L,
			chapterId = SampleData.chapter.id,
			page = 0,
			scroll = 0f,
			percent = 0.5f,
			deletedAt = 0L,
			chaptersCount = checkNotNull(manga.chapters).size,
			space = FavouriteSpace.PRIVATE.dbValue,
		)
		dao.insert(privateHistory)

		assertTrue(dao.observeAll(ListSortOrder.NEWEST, emptySet(), 10).first().isEmpty())
		assertEquals(
			listOf(manga.id),
			dao.observeAllPrivate(ListSortOrder.NEWEST, emptySet(), 10).first().map { it.history.mangaId },
		)
		assertFalse(dao.findAllForSync().any { it.mangaId == manga.id })
		assertNotNull(dao.find(manga.id))

		// Generic writers that do not know the active space must not declassify a Private row.
		dao.upsert(privateHistory.copy(updatedAt = 3L, space = FavouriteSpace.NORMAL.dbValue))
		assertEquals(FavouriteSpace.PRIVATE.dbValue, dao.find(manga.id)?.space)
		assertTrue(dao.observeAll(ListSortOrder.NEWEST, emptySet(), 10).first().isEmpty())

		// A context-aware later read from Normal may reclaim the row when no Private membership exists.
		dao.updateSpace(manga.id, FavouriteSpace.NORMAL.dbValue)
		assertEquals(
			listOf(manga.id),
			dao.observeAll(ListSortOrder.NEWEST, emptySet(), 10).first().map { it.history.mangaId },
		)
	}

	@Test
	fun migration50To51DefaultsExistingHistoryToNormalSpace() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val name = "private-history-space-migration"
		context.deleteDatabase(name)
		val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
			.name(name)
			.callback(object : SupportSQLiteOpenHelper.Callback(50) {
				override fun onCreate(db: SupportSQLiteDatabase) {
					db.execSQL(
						"""
						CREATE TABLE history (
							manga_id INTEGER NOT NULL PRIMARY KEY,
							created_at INTEGER NOT NULL,
							updated_at INTEGER NOT NULL,
							chapter_id INTEGER NOT NULL,
							page INTEGER NOT NULL,
							scroll REAL NOT NULL,
							percent REAL NOT NULL,
							deleted_at INTEGER NOT NULL,
							chapters INTEGER NOT NULL
						)
						""".trimIndent(),
					)
					db.execSQL(
						"INSERT INTO history(manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters) " +
							"VALUES(1, 1, 2, 3, 0, 0, 0.5, 0, 10)",
					)
				}

				override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
			})
			.build()
		val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
		try {
			val sql = helper.writableDatabase
			Migration50To51().migrate(sql)
			sql.query("SELECT space FROM history WHERE manga_id = 1").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(FavouriteSpace.NORMAL.dbValue, cursor.getInt(0))
			}
			var hasSpaceIndex = false
			sql.query("PRAGMA index_list(`history`)").use { cursor ->
				val nameColumn = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) {
					if (cursor.getString(nameColumn) == "index_history_space") {
						hasSpaceIndex = true
						break
					}
				}
			}
			assertTrue(hasSpaceIndex)
		} finally {
			helper.close()
			context.deleteDatabase(name)
		}
	}

	@Test
	fun dualMembershipStaysPrivateInHistoryUntilPrivateMembershipIsRemoved() = runBlocking {
		val manga = SampleData.manga
		val normalCategory = favouritesRepository.createCategory(
			title = "Normal history regression",
			sortOrder = ListSortOrder.NEWEST,
			isTrackerEnabled = false,
			isNewChaptersDownloadEnabled = false,
			isVisibleOnShelf = true,
			space = FavouriteSpace.NORMAL,
		)
		val privateCategory = favouritesRepository.createCategory(
			title = "Private history regression",
			sortOrder = ListSortOrder.NEWEST,
			isTrackerEnabled = false,
			isNewChaptersDownloadEnabled = false,
			isVisibleOnShelf = true,
			space = FavouriteSpace.PRIVATE,
		)
		favouritesRepository.updateCategoryMemberships(
			mangas = listOf(manga),
			changes = mapOf(normalCategory.id to true),
			space = FavouriteSpace.NORMAL,
		)
		favouritesRepository.updateCategoryMemberships(
			mangas = listOf(manga),
			changes = mapOf(privateCategory.id to true),
			space = FavouriteSpace.PRIVATE,
		)

		val dao = database.getHistoryDao()
		dao.insert(
			HistoryEntity(
				mangaId = manga.id,
				createdAt = 1L,
				updatedAt = 2L,
				chapterId = 3L,
				page = 0,
				scroll = 0f,
				percent = 0.5f,
				deletedAt = 0L,
				chaptersCount = 10,
			),
		)

		assertTrue(dao.observeAll(ListSortOrder.NEWEST, emptySet(), 10).first().isEmpty())
		assertEquals(
			listOf(manga.id),
			dao.observeAllPrivate(ListSortOrder.NEWEST, emptySet(), 10).first().map { it.history.mangaId },
		)
		assertFalse(dao.findAllForSync().any { it.mangaId == manga.id })
		assertNotNull(dao.find(manga.id))

		favouritesRepository.updateCategoryMemberships(
			mangas = listOf(manga),
			changes = mapOf(privateCategory.id to false),
			space = FavouriteSpace.PRIVATE,
		)

		assertEquals(
			listOf(manga.id),
			dao.observeAll(ListSortOrder.NEWEST, emptySet(), 10).first().map { it.history.mangaId },
		)
	}
}
