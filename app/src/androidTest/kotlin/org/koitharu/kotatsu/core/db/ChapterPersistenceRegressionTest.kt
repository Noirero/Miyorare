package org.koitharu.kotatsu.core.db

import android.os.SystemClock
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.preference.PreferenceManager
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.migrations.Migration45To46
import org.koitharu.kotatsu.core.db.migrations.Migration46To47
import org.koitharu.kotatsu.core.db.migrations.Migration48To49
import org.koitharu.kotatsu.core.db.migrations.Migration50To51
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.local.data.LegacyChapterDownloadCompat
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyChapterEntity
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementRepository
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementId
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyAchievementEntity
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyWeeklyTaskId
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyProgressionRepository
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyXpEventEntity
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteDownloadOwnershipIndex
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaLinkResolver
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Provider
import kotlin.system.measureTimeMillis
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.details.ui.mapChapters

/**
 * Regression coverage for the cold-start chapter path.
 *
 * These tests deliberately use a file-backed Room database and close/reopen it so a passing
 * result cannot be explained by MemoryContentCache, DetailsNavigationCache, or a still-live
 * Room instance. This is the closest deterministic instrumentation analogue to Android killing
 * and recreating the Miyorare process.
 */
@RunWith(AndroidJUnit4::class)
class ChapterPersistenceRegressionTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Before
	fun setUp() {
		context.deleteDatabase(DB_NAME)
		context.deleteDatabase(MIGRATION_DB_NAME)
	}

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
		context.deleteDatabase(MIGRATION_DB_NAME)
	}

	@Test
	fun schema50UpgradesTo51WithoutLosingHistoryAndReopens() = runTest {
		createSchema50Fixture()
		val migrated = Room.databaseBuilder(context, MangaDatabase::class.java, MIGRATION_DB_NAME)
			.addMigrations(*getDatabaseMigrations(context)).build()
		val expected = compatibilityHistory().copy(legacyResumeUpdatedAt = 200L)
		try {
			assertEquals(expected, migrated.getHistoryDao().find(901L))
			assertNotNull(migrated.getMangaDao().find(901L))
			assertEquals(DATABASE_VERSION, migrated.openHelper.writableDatabase.version)
		} finally {
			migrated.close()
		}
		val reopened = Room.databaseBuilder(context, MangaDatabase::class.java, MIGRATION_DB_NAME)
			.addMigrations(*getDatabaseMigrations(context)).build()
		try {
			assertEquals(expected, reopened.getHistoryDao().find(901L))
		} finally {
			reopened.close()
		}
	}

	@Test
	fun alreadyInstalledSchema51OpensAndPreservesLocalHistoryMarkers() = runTest {
		createSchema50Fixture()
		// Reproduce the already-shipped v51 structure using its original migration, independently
		// of the new Room-generated create SQL. No Room identity row remains in this fixture,
		// so opening it must validate every table/index rather than trusting a cached identity.
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(MIGRATION_DB_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(51) {
					override fun onCreate(db: SupportSQLiteDatabase) = error("Schema 50 fixture must exist")
					override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
						assertEquals(50, oldVersion)
						assertEquals(51, newVersion)
						Migration50To51().migrate(db)
					}
				})
				.build(),
		)
		try {
			helper.writableDatabase.execSQL(
				"UPDATE history SET last_reader_activity_at = 300, legacy_resume_updated_at = 200 WHERE manga_id = 901",
			)
		} finally {
			helper.close()
		}
		val expected = compatibilityHistory().copy(lastReaderActivityAt = 300L, legacyResumeUpdatedAt = 200L)
		val reopened = Room.databaseBuilder(context, MangaDatabase::class.java, MIGRATION_DB_NAME)
			.addMigrations(*getDatabaseMigrations(context)).build()
		try {
			val dao = reopened.getHistoryDao()
			assertEquals(expected, dao.find(901L))
			// Both native progress restore and cloud replacement must retain the existing markers.
			dao.upsert(compatibilityHistory().copy(page = 5, updatedAt = 400L))
			assertEquals(expected.copy(page = 5, updatedAt = 400L), dao.find(901L))
			dao.upsertForSync(compatibilityHistory().copy(page = 6, updatedAt = 500L, deletedAt = 600L))
			assertEquals(expected.copy(page = 6, updatedAt = 500L, deletedAt = 600L), dao.findIncludingDeleted(901L))
			assertEquals(DATABASE_VERSION, reopened.openHelper.writableDatabase.version)
		} finally {
			reopened.close()
		}
	}

	@Test
	fun schema51AddsEmptyPersonalMetadataAndPreservesExistingData() = runTest {
		val current = Room.databaseBuilder(context, MangaDatabase::class.java, MIGRATION_DB_NAME).build()
		try {
			current.getMangaDao().upsert(SampleData.mangaDetails.copy(id = 901L).toEntity())
			current.getHistoryDao().upsert(compatibilityHistory())
		} finally { current.close() }
		android.database.sqlite.SQLiteDatabase.openDatabase(
			context.getDatabasePath(MIGRATION_DB_NAME).absolutePath, null,
			android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
		).use { old ->
			old.execSQL("DROP TABLE chapter_personal")
			old.execSQL("DROP TABLE room_master_table")
			old.version = 51
		}
		val migrated = Room.databaseBuilder(context, MangaDatabase::class.java, MIGRATION_DB_NAME)
			.addMigrations(*getDatabaseMigrations(context)).build()
		try {
			assertEquals(compatibilityHistory(), migrated.getHistoryDao().find(901L))
			assertNotNull(migrated.getMangaDao().find(901L))
			assertTrue(migrated.getChapterPersonalDao().findAll(listOf(901L)).isEmpty())
			val repository = org.koitharu.kotatsu.details.data.ChapterPersonalRepository(migrated)
			val manga = SampleData.mangaDetails.copy(id = 901L)
			val key = org.koitharu.kotatsu.details.data.ChapterPersonalKey.of(SampleData.chapter)
			repository.set(manga, key, 5, "Peak chapter")
			assertEquals(5, repository.get(manga.id, key).rating)
		} finally { migrated.close() }
		val reopened = Room.databaseBuilder(context, MangaDatabase::class.java, MIGRATION_DB_NAME).build()
		try {
			assertEquals(DATABASE_VERSION, reopened.openHelper.writableDatabase.version)
			assertEquals("Peak chapter", reopened.getChapterPersonalDao().findAll(listOf(901L)).single().note)
		} finally { reopened.close() }
	}

	@Test
	fun personalMetadataSurvivesReplacementGcAndReopenThenEditsAndClearsWithoutDuplicates() = runTest {
		val manga = SampleData.mangaDetails.copy(id = 902L)
		val a = org.koitharu.kotatsu.details.data.ChapterPersonalKey.of(SampleData.chapter)
		val b = a.copy(url = a.url + "-other")
		val otherSource = a.copy(source = "OTHER_SOURCE")
		withDatabase { db ->
			val repository = org.koitharu.kotatsu.details.data.ChapterPersonalRepository(db)
			repository.set(manga, a, 5, "Peak chapter")
			db.getChaptersDao().replaceAll(manga.id, checkNotNull(manga.chapters).reversed().withIndex().toEntities(manga.id))
			db.getChaptersDao().gc(listOf(manga.id))
			db.getMangaDao().cleanup(emptySet())
			assertNotNull(db.getMangaDao().find(manga.id))
			assertTrue(db.getChaptersDao().findAll(manga.id).isEmpty())
			assertTrue(repository.get(manga.id, b).isEmpty)
			assertTrue(repository.get(manga.id, otherSource).isEmpty)
			assertTrue(repository.get(903L, a).isEmpty)
			repository.set(manga.copy(id = 903L), a, 1, "Other manga")
			assertEquals(5, repository.get(manga.id, a).rating)
		}
		withDatabase { db ->
			val repository = org.koitharu.kotatsu.details.data.ChapterPersonalRepository(db)
			assertEquals(org.koitharu.kotatsu.details.data.ChapterPersonalMetadata(5, "Peak chapter"), repository.get(manga.id, a))
			repository.set(manga, a, 4, "Edited")
			assertEquals(org.koitharu.kotatsu.details.data.ChapterPersonalMetadata(4, "Edited"), repository.get(manga.id, a))
			assertEquals(1, db.getChapterPersonalDao().findAll(listOf(manga.id)).size)
			repository.set(manga, a, 4, "   ")
			assertEquals(org.koitharu.kotatsu.details.data.ChapterPersonalMetadata(4), repository.get(manga.id, a))
			repository.set(manga, a, null, "Note only")
			assertEquals(org.koitharu.kotatsu.details.data.ChapterPersonalMetadata(note = "Note only"), repository.get(manga.id, a))
			repository.set(manga, a, null, "")
			assertTrue(repository.get(manga.id, a).isEmpty)
			assertTrue(db.getChapterPersonalDao().findAll(listOf(manga.id)).isEmpty())
			assertTrue(repository.get(manga.id, b).isEmpty)
		}
	}

	@Test
	fun chapterPersonalIdentityUsesSourceLocatorWhenDownloadedObjectReplacesRemote() {
		val remote = SampleData.chapter
		val local = remote.copy(source = LocalMangaSource, url = "file:///download/chapter.cbz")
		val source = SampleData.mangaDetails.copy(chapters = listOf(remote))
		val details = org.koitharu.kotatsu.details.data.MangaDetails(source).copy(
			localManga = LocalManga(source.copy(source = LocalMangaSource, url = "file:///download/manga", chapters = listOf(local))),
		)
		val rows = details.mapChapters(0, 0, remote.branch, emptyList(), false, false)
		assertEquals(1, rows.size)
		assertEquals(local, rows.single().chapter)
		assertEquals(org.koitharu.kotatsu.details.data.ChapterPersonalKey.of(remote), rows.single().personalKey)
	}

	@Test
	fun libraryIndicatorFitsNarrowCardsAndClearsRecycledPresentation() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			val themed = android.view.ContextThemeWrapper(context, org.koitharu.kotatsu.R.style.Theme_Kotatsu)
			val parser = themed.resources.getLayout(org.koitharu.kotatsu.R.layout.item_manga_grid)
			try {
				while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
					if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name.endsWith("IconsView")) break
				}
				val view = org.koitharu.kotatsu.core.ui.widgets.IconsView(themed, android.util.Xml.asAttributeSet(parser))
				val size = themed.resources.getDimensionPixelSize(org.koitharu.kotatsu.R.dimen.library_indicator_icon_size)
				view.addIcon(org.koitharu.kotatsu.R.drawable.ic_heart, size)
				view.addLabel(themed.getString(org.koitharu.kotatsu.R.string.in_library))
				for (widthDp in listOf(90, 140)) {
					val width = (widthDp * themed.resources.displayMetrics.density).toInt()
					view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.AT_MOST),
						android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
					assertTrue(view.measuredWidth <= width)
				}
				assertEquals(size, view.getChildAt(0).layoutParams.width)
				assertTrue(view.getChildAt(1) is android.widget.TextView)
				view.clearIcons()
				assertEquals(0, view.iconsCount)
				view.addIcon(org.koitharu.kotatsu.R.drawable.ic_storage)
				assertEquals(1, view.iconsCount)
				assertEquals(android.view.View.GONE, view.getChildAt(1).visibility)
				assertTrue(view.getChildAt(0).layoutParams.width < size)
			} finally { parser.close() }
		}
	}

	private fun compatibilityHistory() = HistoryEntity(901L, 100L, 200L, 1L, 4, 0.25f, 0.5f, 0L, 3)

	private suspend fun createSchema50Fixture() {
		val current = Room.databaseBuilder(context, MangaDatabase::class.java, MIGRATION_DB_NAME).build()
		try {
			current.getMangaDao().upsert(SampleData.mangaDetails.copy(id = 901L).toEntity(), emptyList())
			current.getHistoryDao().upsert(compatibilityHistory())
		} finally {
			current.close()
		}
		android.database.sqlite.SQLiteDatabase.openDatabase(
			context.getDatabasePath(MIGRATION_DB_NAME).absolutePath, null,
			android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
		).use { legacy ->
			legacy.beginTransaction()
			try {
				legacy.execSQL("""
					CREATE TABLE history_v50 (
						manga_id INTEGER NOT NULL PRIMARY KEY, created_at INTEGER NOT NULL,
						updated_at INTEGER NOT NULL, chapter_id INTEGER NOT NULL, page INTEGER NOT NULL,
						scroll REAL NOT NULL, percent REAL NOT NULL, deleted_at INTEGER NOT NULL, chapters INTEGER NOT NULL,
						FOREIGN KEY(manga_id) REFERENCES manga(manga_id) ON UPDATE NO ACTION ON DELETE CASCADE
					)
				""".trimIndent())
				legacy.execSQL("INSERT INTO history_v50 SELECT manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters FROM history")
				legacy.execSQL("DROP TABLE history")
				legacy.execSQL("ALTER TABLE history_v50 RENAME TO history")
				legacy.execSQL("DROP TABLE chapter_personal")
				legacy.execSQL("DROP TABLE room_master_table")
				legacy.version = 50
				legacy.setTransactionSuccessful()
			} finally {
				legacy.endTransaction()
			}
		}
	}

	@Test
	fun migration45To46BackfillsOnlyExistingChapterSnapshots() {
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(MIGRATION_DB_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(45) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						db.execSQL("CREATE TABLE manga (manga_id INTEGER NOT NULL PRIMARY KEY)")
						db.execSQL("CREATE TABLE chapters (manga_id INTEGER NOT NULL)")
						db.execSQL("INSERT INTO manga(manga_id) VALUES (1), (2)")
						db.execSQL("INSERT INTO chapters(manga_id) VALUES (1)")
					}

					override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
				})
				.build(),
		)
		try {
			val db = helper.writableDatabase
			Migration45To46().migrate(db)

			val initialized = LinkedHashMap<Long, Int>()
			db.query("SELECT manga_id, chapters_initialized FROM manga ORDER BY manga_id").use { cursor ->
				while (cursor.moveToNext()) {
					initialized[cursor.getLong(0)] = cursor.getInt(1)
				}
			}
			assertEquals(mapOf(1L to 1, 2L to 0), initialized)
		} finally {
			helper.close()
		}
	}


	@Test
	fun migration46To47StartsReaderJourneyWithoutBackfillingHistory() {
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(MIGRATION_DB_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(46) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						db.execSQL("CREATE TABLE history (manga_id INTEGER NOT NULL, percent REAL NOT NULL)")
						db.execSQL("INSERT INTO history(manga_id, percent) VALUES (123, 1.0)")
					}

					override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
				})
				.build(),
		)
		try {
			val db = helper.writableDatabase
			Migration46To47().migrate(db)

			val chapterAwards = db.query("SELECT COUNT(*) FROM reader_journey_chapters").use { cursor ->
				check(cursor.moveToFirst())
				cursor.getLong(0)
			}
			val profiles = db.query("SELECT COUNT(*) FROM reader_journey_profile").use { cursor ->
				check(cursor.moveToFirst())
				cursor.getLong(0)
			}
			assertEquals(0L, chapterAwards)
			assertEquals(0L, profiles)
		} finally {
			helper.close()
		}
	}

	@Test
	fun migration48To49PreservesReaderJourneyXpAndAddsProgressionLedgers() {
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(MIGRATION_DB_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(48) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						db.execSQL(
							"""
							CREATE TABLE reader_journey_profile (
								id INTEGER NOT NULL PRIMARY KEY,
								total_xp INTEGER NOT NULL,
								completed_chapters INTEGER NOT NULL,
								manga_chapters INTEGER NOT NULL,
								novel_chapters INTEGER NOT NULL,
								updated_at INTEGER NOT NULL
							)
							""".trimIndent(),
						)
						db.execSQL(
							"""
							CREATE TABLE reader_journey_chapters (
								manga_id INTEGER NOT NULL,
								chapter_id INTEGER NOT NULL,
								is_novel INTEGER NOT NULL,
								reading_units INTEGER NOT NULL,
								completion_count INTEGER NOT NULL,
								awarded_xp INTEGER NOT NULL,
								first_completed_at INTEGER NOT NULL,
								last_completed_at INTEGER NOT NULL,
								PRIMARY KEY(manga_id, chapter_id)
							)
							""".trimIndent(),
						)
						db.execSQL(
							"INSERT INTO reader_journey_profile VALUES (0, 96101, 1234, 1000, 234, 999)",
						)
						db.execSQL(
							"INSERT INTO reader_journey_chapters VALUES (1, 1, 0, 0, 1, 96000, 100, 100)",
						)
					}

					override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
				})
				.build(),
		)
		try {
			val db = helper.writableDatabase
			Migration48To49().migrate(db)

			db.query("SELECT total_xp, xp_floor FROM reader_journey_profile WHERE id = 0").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(96_101L, cursor.getLong(0))
				assertEquals(101L, cursor.getLong(1))
			}
			db.query("SELECT COUNT(*) FROM reader_journey_xp_events").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0L, cursor.getLong(0))
			}
			db.query("SELECT COUNT(*) FROM reader_journey_weekly_state").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0L, cursor.getLong(0))
			}
		} finally {
			helper.close()
		}
	}

	@Test
	fun weeklyThreeTasksAwardsCompletionBonusExactlyOnce() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			val repository = ReaderJourneyProgressionRepository(database)
			val monday = Instant.parse("2026-09-21T00:00:00Z").toEpochMilli()
			dao.awardCompletion(
				mangaId = 88L,
				chapterId = 1L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = monday - 24L * 60L * 60L * 1000L,
			)
			val currentWeek = listOf(
				monday + 1_000L,
				monday + 2_000L,
				monday + 24L * 60L * 60L * 1000L + 1_000L,
				monday + 24L * 60L * 60L * 1000L + 2_000L,
			)
			currentWeek.forEachIndexed { index, at ->
				dao.awardCompletion(
					mangaId = 88L,
					chapterId = 2L + index,
					isNovel = false,
					readingUnits = 0,
					baseXp = 10,
					completedAt = at,
				)
			}

			val at = currentWeek.last() + 1_000L
			repository.reconcile(at)
			val first = repository.snapshot(at).weekly
			assertEquals(3, first.completedTaskCount)
			assertTrue(first.completionBonusAwarded)
			assertEquals(50, dao.getXpEvent("weekly-bonus:2026-09-21")?.xp)
			val totalAfterFirst = dao.getProfile()?.totalXp

			repository.reconcile(at)
			assertEquals(totalAfterFirst, dao.getProfile()?.totalXp)
			assertEquals(50, dao.getXpEvent("weekly-bonus:2026-09-21")?.xp)

			val fifthAt = at + 1_000L
			dao.awardCompletion(
				mangaId = 88L,
				chapterId = 6L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = fifthAt,
			)
			repository.reconcile(fifthAt)
			assertEquals(35, dao.getXpEvent("weekly:2026-09-21:slot:4")?.xp)
			assertEquals(50, dao.getXpEvent("weekly-bonus:2026-09-21")?.xp)
		} finally {
			database.close()
		}
	}

	@Test
	fun weeklyGraceUsesCompletionTimestampAcrossResetBoundary() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			val repository = ReaderJourneyProgressionRepository(database)
			val reset = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
			val before = listOf(
				reset - 3_000L,
				reset - 2_000L,
				reset - 1_000L,
			)
			before.forEachIndexed { index, at ->
				dao.awardCompletion(
					mangaId = 77L,
					chapterId = 1L + index,
					isNovel = false,
					readingUnits = 0,
					baseXp = 10,
					completedAt = at,
				)
			}
			dao.awardCompletion(
				mangaId = 77L,
				chapterId = 4L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = reset + 1_000L,
			)

			repository.reconcile(reset + 60L * 60L * 1000L)

			assertEquals(25, dao.getXpEvent("weekly:2026-09-21:slot:0")?.xp)
			assertNull(dao.getXpEvent("weekly:2026-09-21:slot:3"))
		} finally {
			database.close()
		}
	}

	@Test
	fun firstVerifiedCompletionAfterResetAutomaticallyReconcilesPreviousWeek() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			val repository = ReaderJourneyProgressionRepository(database)
			val reset = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
			val previousWeek = listOf(
				reset - 2L * 24L * 60L * 60L * 1000L,
				reset - 2L * 24L * 60L * 60L * 1000L + 1_000L,
				reset - 24L * 60L * 60L * 1000L,
			)
			previousWeek.forEachIndexed { index, at ->
				dao.awardCompletion(
					mangaId = 501L,
					chapterId = 1L + index,
					isNovel = false,
					readingUnits = 0,
					baseXp = 10,
					completedAt = at,
				)
			}
			assertNull(dao.getXpEvent("weekly-bonus:2026-09-21"))

			val currentAt = reset + 1_000L
			val currentAward = dao.awardCompletion(
				mangaId = 501L,
				chapterId = 4L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = currentAt,
			)
			repository.onVerifiedCompletion(
				award = currentAward,
				mangaId = 501L,
				chapterId = 4L,
				completedAt = currentAt,
			)

			assertEquals(50, dao.getXpEvent("weekly-bonus:2026-09-21")?.xp)
			assertEquals(currentAt, dao.getXpEvent("weekly-bonus:2026-09-21")?.occurredAt)
			assertTrue(
				dao.getXpEventsAt(currentAt).any {
					it.eventKey == "weekly-bonus:2026-09-21" && it.source == "WEEKLY_BONUS"
				},
			)
			val totalAfter = dao.getProfile()?.totalXp
			repository.reconcile(currentAt)
			assertEquals(totalAfter, dao.getProfile()?.totalXp)
		} finally {
			database.close()
		}
	}

	@Test
	fun rereadXpStopsAfterThreeRewardedRereads() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			val first = dao.awardCompletion(
				mangaId = 650L,
				chapterId = 1L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = 1_000L,
			)
			assertEquals(10, first.xp)

			val rereadXp = (1..5).map { index ->
				dao.awardCompletion(
					mangaId = 650L,
					chapterId = 1L,
					isNovel = false,
					readingUnits = 0,
					baseXp = 10,
					completedAt = 1_000L + index,
				).xp
			}
			assertEquals(listOf(1, 1, 1, 0, 0), rereadXp)
			assertEquals(4, dao.getAllChapterAwards().single().completionCount)
			assertEquals(13L, dao.getProfile()?.totalXp)
		} finally {
			database.close()
		}
	}

	@Test
	fun verifiedRereadDaysCountAsActiveDaysWithoutFarmingChapterTasks() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			val repository = ReaderJourneyProgressionRepository(database)
			val monday = Instant.parse("2026-09-21T00:00:00Z").toEpochMilli()

			val first = dao.awardCompletion(
				mangaId = 700L,
				chapterId = 1L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = monday + 1_000L,
			)
			repository.onVerifiedCompletion(first, 700L, 1L, monday + 1_000L)

			val rereadDay2At = monday + 24L * 60L * 60L * 1000L + 1_000L
			val rereadDay2 = dao.awardCompletion(
				mangaId = 700L,
				chapterId = 1L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = rereadDay2At,
			)
			repository.onVerifiedCompletion(rereadDay2, 700L, 1L, rereadDay2At)
			assertEquals(20, dao.getXpEvent("weekly:2026-09-21:slot:1")?.xp)
			assertNull(dao.getXpEvent("weekly:2026-09-21:slot:0"))

			val rereadDay3At = monday + 2L * 24L * 60L * 60L * 1000L + 1_000L
			val rereadDay3 = dao.awardCompletion(
				mangaId = 700L,
				chapterId = 1L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = rereadDay3At,
			)
			repository.onVerifiedCompletion(rereadDay3, 700L, 1L, rereadDay3At)

			assertEquals(30, dao.getXpEvent("active-days:2026-09-21")?.xp)
			assertNull(dao.getXpEvent("weekly:2026-09-21:slot:0"))
		} finally {
			database.close()
		}
	}

	@Test
	fun restedAndWelcomeBackCapsHoldAcrossMultipleVerifiedCompletions() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			val repository = ReaderJourneyProgressionRepository(database)
			val firstAt = 1_000L
			dao.awardCompletion(
				mangaId = 10L,
				chapterId = 1L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = firstAt,
			)

			val comebackAt = firstAt + 8L * 24L * 60L * 60L * 1000L
			repeat(6) { index ->
				val at = comebackAt + index * 1_000L
				val award = dao.awardCompletion(
					mangaId = 10L,
					chapterId = 2L + index,
					isNovel = false,
					readingUnits = 0,
					baseXp = 10,
					completedAt = at,
				)
				repository.onVerifiedCompletion(
					award = award,
					mangaId = 10L,
					chapterId = 2L + index,
					completedAt = at,
				)
			}

			assertEquals(5, dao.countXpEventsByKeyPrefix("rested:" + firstAt + ":slot:"))
			assertEquals(3, dao.countXpEventsByKeyPrefix("welcome:" + firstAt + ":slot:"))
		} finally {
			database.close()
		}
	}

	@Test
	fun rereadComebackPreservesRestedAndWelcomeEligibilityForNextFirstCompletion() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			val repository = ReaderJourneyProgressionRepository(database)
			val firstAt = 1_000L
			val first = dao.awardCompletion(
				mangaId = 11L,
				chapterId = 1L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = firstAt,
			)
			repository.onVerifiedCompletion(first, 11L, 1L, firstAt)

			val rereadAt = firstAt + 8L * 24L * 60L * 60L * 1000L
			val reread = dao.awardCompletion(
				mangaId = 11L,
				chapterId = 1L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = rereadAt,
			)
			repository.onVerifiedCompletion(reread, 11L, 1L, rereadAt)

			assertNotNull(dao.getXpEvent("rested-window:" + firstAt))
			assertNotNull(dao.getXpEvent("welcome-window:" + firstAt))
			assertEquals(0, dao.countXpEventsByKeyPrefix("rested:" + firstAt + ":slot:"))
			assertEquals(0, dao.countXpEventsByKeyPrefix("welcome:" + firstAt + ":slot:"))

			val nextAt = rereadAt + 1_000L
			val next = dao.awardCompletion(
				mangaId = 11L,
				chapterId = 2L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = nextAt,
			)
			repository.onVerifiedCompletion(next, 11L, 2L, nextAt)

			assertEquals(1, dao.countXpEventsByKeyPrefix("rested:" + firstAt + ":slot:"))
			assertEquals(1, dao.countXpEventsByKeyPrefix("welcome:" + firstAt + ":slot:"))
		} finally {
			database.close()
		}
	}

	@Test
	fun achievementBackfillWaitsWhileReaderJourneyProgressionIsDisabled() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			dao.mergeAchievement(
				ReaderJourneyAchievementEntity(
					achievementId = ReaderAchievementId.FIRST_CHAPTER.name,
					unlockedAt = 100L,
				),
			)
			val repository = ReaderAchievementRepository(database)

			val disabled = repository.refreshWithResult(
				unlockedAt = 200L,
				allowUnlock = false,
				allowXpAward = false,
			)
			assertTrue(disabled.xpAwards.isEmpty())
			assertNull(dao.getXpEvent("achievement:FIRST_CHAPTER"))
			assertEquals(0L, dao.getProfile()?.totalXp ?: 0L)

			val enabled = repository.refreshWithResult(
				unlockedAt = 300L,
				allowUnlock = false,
				allowXpAward = true,
			)
			assertEquals(25, enabled.xpAwards.single().xp)
			assertEquals(25L, dao.getProfile()?.totalXp)
		} finally {
			database.close()
		}
	}

	@Test
	fun existingAchievementBackfillAwardsXpExactlyOnce() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			dao.mergeAchievement(
				ReaderJourneyAchievementEntity(
					achievementId = ReaderAchievementId.FIRST_CHAPTER.name,
					unlockedAt = 100L,
				),
			)
			val repository = ReaderAchievementRepository(database)

			val first = repository.refreshWithResult(unlockedAt = 200L, allowUnlock = false)
			assertEquals(25, first.xpAwards.single().xp)
			assertEquals(ReaderAchievementId.FIRST_CHAPTER.name, first.xpAwards.single().context)
			assertEquals(200L, dao.getXpEvent("achievement:FIRST_CHAPTER")?.occurredAt)
			assertEquals(100L, dao.getAllAchievements().single().unlockedAt)
			assertEquals(25L, dao.getProfile()?.totalXp)

			val second = repository.refreshWithResult(unlockedAt = 300L, allowUnlock = false)
			assertTrue(second.xpAwards.isEmpty())
			assertEquals(25L, dao.getProfile()?.totalXp)
		} finally {
			database.close()
		}
	}

	@Test
	fun weeklyRerollLedgerEnforcesGlobalTwoRerollCapAfterSync() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			val at = LocalDate.of(2026, 9, 23)
				.atStartOfDay(ZoneId.systemDefault())
				.toInstant()
				.toEpochMilli()
			dao.insertXpEvent(
				ReaderJourneyXpEventEntity(
					eventKey = "weekly-reroll:2026-09-21:slot:0",
					source = "WEEKLY_REROLL",
					xp = 0,
					occurredAt = at - 2_000L,
					context = ReaderJourneyWeeklyTaskId.READ_3_DAYS.name,
					profileDelta = false,
				),
			)
			dao.insertXpEvent(
				ReaderJourneyXpEventEntity(
					eventKey = "weekly-reroll:2026-09-21:slot:1",
					source = "WEEKLY_REROLL",
					xp = 0,
					occurredAt = at - 1_000L,
					context = ReaderJourneyWeeklyTaskId.READ_4_MANGA.name,
					profileDelta = false,
				),
			)

			val repository = ReaderJourneyProgressionRepository(database)
			val snapshot = repository.snapshot(at).weekly
			assertEquals(0, snapshot.rerollsRemaining)
			assertEquals(ReaderJourneyWeeklyTaskId.READ_3_DAYS, snapshot.tasks[0].id)
			assertEquals(ReaderJourneyWeeklyTaskId.READ_4_MANGA, snapshot.tasks[1].id)
			assertTrue(!repository.rerollWeeklyTask(ReaderJourneyWeeklyTaskId.READ_2_TITLES, at))
		} finally {
			database.close()
		}
	}

	@Test
	fun canonicalComebackMergeKeepsLifetimeXpWhileDemotingForkedBonuses() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			dao.insertXpEvent(
				ReaderJourneyXpEventEntity(
					eventKey = "rested-window:1000",
					source = "RESTED_WINDOW",
					xp = 0,
					occurredAt = 90L,
					context = "1000",
					profileDelta = false,
				),
			)
			dao.insertXpEvent(
				ReaderJourneyXpEventEntity(
					eventKey = "rested-window:2000",
					source = "RESTED_WINDOW",
					xp = 0,
					occurredAt = 190L,
					context = "2000",
					profileDelta = false,
				),
			)
			repeat(5) { slot ->
				dao.awardBonusEvent(
					ReaderJourneyXpEventEntity(
						eventKey = "rested:1000:slot:" + slot,
						source = "RESTED",
						xp = 3,
						occurredAt = 100L + slot,
						mangaId = 1L,
						chapterId = 10L + slot,
						context = "1000",
						profileDelta = true,
					),
				)
				dao.awardBonusEvent(
					ReaderJourneyXpEventEntity(
						eventKey = "rested:2000:slot:" + slot,
						source = "RESTED",
						xp = 3,
						occurredAt = 200L + slot,
						mangaId = 2L,
						chapterId = 20L + slot,
						context = "2000",
						profileDelta = true,
					),
				)
			}
			assertEquals(30L, dao.getProfile()?.totalXp)

			dao.demoteComebackBonusEvents()
			dao.clearComebackWindowEvents()
			dao.mergeXpEvent(
				ReaderJourneyXpEventEntity(
					eventKey = "rested-window:1000",
					source = "RESTED_WINDOW",
					xp = 0,
					occurredAt = 90L,
					context = "1000",
					profileDelta = false,
				),
			)
			repeat(5) { slot ->
				dao.mergeXpEvent(
					ReaderJourneyXpEventEntity(
						eventKey = "rested:1000:slot:" + slot,
						source = "RESTED",
						xp = 3,
						occurredAt = 100L + slot,
						mangaId = 1L,
						chapterId = 10L + slot,
						context = "1000",
						profileDelta = true,
					),
				)
			}
			dao.reconcileXpFloor(30L)
			dao.rebuildProfileFromLedger()

			assertEquals(30L, dao.getProfile()?.totalXp)
			assertEquals(15L, dao.getProfile()?.xpFloorAdjustment)
			assertEquals(5, dao.getRecentXpEvents(20).count { it.source == "RESTED" })
			assertEquals("1000", dao.latestXpEventBySource("RESTED_WINDOW")?.context)
		} finally {
			database.close()
		}
	}

	@Test
	fun readerJourneyXpFloorPreservesNewXpAndShrinksAsLedgerCatchesUp() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val dao = database.getReaderJourneyDao()
			dao.reconcileXpFloor(1_000L)
			assertEquals(1_000L, dao.getProfile()?.totalXp)

			dao.awardCompletion(
				mangaId = 1L,
				chapterId = 1L,
				isNovel = false,
				readingUnits = 0,
				baseXp = 10,
				completedAt = 100L,
			)
			dao.rebuildProfileFromLedger()
			assertEquals(1_010L, dao.getProfile()?.totalXp)
			assertEquals(1_000L, dao.getProfile()?.xpFloorAdjustment)

			dao.mergeChapterAward(
				ReaderJourneyChapterEntity(
					mangaId = 2L,
					chapterId = 2L,
					isNovel = false,
					readingUnits = 0,
					completionCount = 1,
					awardedXp = 500L,
					firstCompletedAt = 50L,
					lastCompletedAt = 50L,
				),
			)
			dao.reconcileXpFloor(1_010L)
			dao.rebuildProfileFromLedger()

			assertEquals(1_010L, dao.getProfile()?.totalXp)
			assertEquals(500L, dao.getProfile()?.xpFloorAdjustment)
		} finally {
			database.close()
		}
	}

	@Test
	fun fetchedChaptersSurviveDatabaseReopen() = runTest {
		val details = remoteDetails()
		val expectedChapters = requireNotNull(details.chapters)
		assertTrue(expectedChapters.isNotEmpty())

		withDatabase { database ->
			val repository = createRepository(database)
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)

			assertEquals(expectedChapters.size, database.getChaptersDao().count(details.id))
			assertTrue(repository.getDetailsUpdatedAt(details.id) > 0L)
			assertTrue(repository.isChaptersInitialized(details.id))
		}

		withDatabase { database ->
			val repository = createRepository(database)
			val restored = repository.findMangaById(details.id, withChapters = true)

			assertNotNull(restored)
			assertEquals(
				expectedChapters.map { it.id },
				requireNotNull(restored?.chapters).map { it.id },
			)
			assertEquals(expectedChapters.size, database.getChaptersDao().count(details.id))
			assertTrue(repository.getDetailsUpdatedAt(details.id) > 0L)
			assertTrue(repository.isChaptersInitialized(details.id))
		}
	}

	@Test
	fun lightweightMetadataWriteDoesNotDropPersistedChapters() = runTest {
		val details = remoteDetails()
		val expectedChapters = requireNotNull(details.chapters)

		withDatabase { database ->
			val repository = createRepository(database)
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)

			repository.storeManga(
				manga = details.copy(chapters = null),
				replaceExisting = true,
				stripAppliedOverride = false,
			)

			assertEquals(expectedChapters.size, database.getChaptersDao().count(details.id))
		}

		withDatabase { database ->
			val restored = createRepository(database).findMangaById(details.id, withChapters = true)
			assertEquals(
				expectedChapters.map { it.id },
				requireNotNull(restored?.chapters).map { it.id },
			)
		}
	}

	@Test
	fun ordinaryMangaUpsertPreservesChapterCacheMetadata() = runTest {
		val details = remoteDetails()

		withDatabase { database ->
			val repository = createRepository(database)
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			val updatedAt = repository.getDetailsUpdatedAt(details.id)
			assertTrue(updatedAt > 0L)
			assertTrue(repository.isChaptersInitialized(details.id))

			database.getMangaDao().upsert(details.copy(chapters = null).toEntity())

			assertEquals(updatedAt, repository.getDetailsUpdatedAt(details.id))
			assertTrue(repository.isChaptersInitialized(details.id))
			assertTrue(database.getChaptersDao().count(details.id) > 0)
		}
	}

	@Test
	fun successfulZeroChapterDetailsRemainInitializedAfterReopen() = runTest {
		val details = remoteDetails().copy(chapters = emptyList())

		withDatabase { database ->
			val repository = createRepository(database)
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			assertEquals(0, database.getChaptersDao().count(details.id))
			assertTrue(repository.getDetailsUpdatedAt(details.id) > 0L)
			assertTrue(repository.isChaptersInitialized(details.id))
		}

		withDatabase { database ->
			val repository = createRepository(database)
			assertEquals(0, database.getChaptersDao().count(details.id))
			assertTrue(repository.getDetailsUpdatedAt(details.id) > 0L)
			assertTrue(repository.isChaptersInitialized(details.id))
			assertNotNull(repository.findMangaById(details.id, withChapters = true))
		}
	}

	@Test
	fun recentExtensionDetailsSurviveRoutineChapterGcAndReopen() = runTest {
		val details = remoteDetails()
		val expectedChapters = requireNotNull(details.chapters)

		withDatabase { database ->
			val repository = createRepository(database)
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			assertTrue(repository.isChaptersInitialized(details.id))
			assertTrue(repository.getDetailsUpdatedAt(details.id) > 0L)

			// No History/Favourite pin exists. Routine GC must still keep a recently viewed
			// Extension Details snapshot so reopening can stay Room-first.
			database.getChaptersDao().gc()

			assertEquals(expectedChapters.size, database.getChaptersDao().count(details.id))
			assertTrue(repository.isChaptersInitialized(details.id))
		}

		withDatabase { database ->
			val repository = createRepository(database)
			val lightweightListItem = details.copy(chapters = null)
			val intent = MangaIntent(
				SavedStateHandle(
					mapOf(AppRouter.KEY_MANGA to ParcelableManga(lightweightListItem)),
				),
			)
			val restored = repository.resolveIntent(intent, withChapters = true)
			assertEquals(
				expectedChapters.map { it.id },
				requireNotNull(restored?.chapters).map { it.id },
			)
			assertTrue(repository.isChaptersInitialized(details.id))
		}
	}

	@Test
	fun targetedChapterGcResetsInitializationForRemovedSnapshot() = runTest {
		val details = remoteDetails()

		withDatabase { database ->
			val repository = createRepository(database)
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			assertTrue(repository.isChaptersInitialized(details.id))
			assertTrue(database.getChaptersDao().count(details.id) > 0)

			// Targeted GC is used when an exact title loses History/Favourite ownership and must
			// preserve the old purge semantics, including Private isolation.
			database.getChaptersDao().gc(setOf(details.id))

			assertEquals(0, database.getChaptersDao().count(details.id))
			assertTrue(repository.getDetailsUpdatedAt(details.id) > 0L)
			assertTrue(!repository.isChaptersInitialized(details.id))
		}

		withDatabase { database ->
			val repository = createRepository(database)
			assertEquals(0, database.getChaptersDao().count(details.id))
			assertTrue(!repository.isChaptersInitialized(details.id))
		}
	}

	@Test
	fun emptySuccessfulRefreshDoesNotEraseExistingChapterSnapshot() = runTest {
		val details = remoteDetails()
		val expectedChapters = requireNotNull(details.chapters)

		withDatabase { database ->
			val repository = createRepository(database)
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			val firstUpdatedAt = repository.getDetailsUpdatedAt(details.id)
			assertTrue(firstUpdatedAt > 0L)

			repository.storeManga(
				manga = details.copy(chapters = emptyList()),
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)

			assertEquals(expectedChapters.size, database.getChaptersDao().count(details.id))
			assertEquals(firstUpdatedAt, repository.getDetailsUpdatedAt(details.id))
		}

		withDatabase { database ->
			val restored = createRepository(database).findMangaById(details.id, withChapters = true)
			assertEquals(
				expectedChapters.map { it.id },
				requireNotNull(restored?.chapters).map { it.id },
			)
		}
	}



	@Test
	fun lightweightFavouritesIntentRestoresPersistedChaptersAfterReopen() = runTest {
		val details = remoteDetails()
		val expectedChapters = requireNotNull(details.chapters)

		withDatabase { database ->
			createRepository(database).storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			assertEquals(expectedChapters.size, database.getChaptersDao().count(details.id))
		}

		withDatabase { database ->
			val lightweight = details.copy(chapters = null)
			val intent = MangaIntent(
				SavedStateHandle(
					mapOf(AppRouter.KEY_MANGA to ParcelableManga(lightweight)),
				),
			)
			val restored = createRepository(database).resolveIntent(intent, withChapters = true)

			assertNotNull(restored)
			assertEquals(
				expectedChapters.map { it.id },
				requireNotNull(restored?.chapters).map { it.id },
			)
		}
	}


	@Test
	fun sidecarFreeCbzIsRekeyedToRemoteChapterAndKeepsLocalUrl() {
		val seed = remoteDetails()
		val remoteChapter = requireNotNull(seed.chapters).first().copy(
			title = "Chapter 1",
			scanlator = "Team",
		)
		val remote = seed.copy(chapters = listOf(remoteChapter))
		val localUrl = "file:///tmp/Manga/Team_Chapter%201.cbz"
		val localChapter = remoteChapter.copy(
			id = remoteChapter.id + 999L,
			url = localUrl,
			source = LocalMangaSource,
		)
		val local = LocalManga(
			manga = remote.copy(
				id = remote.id + 777L,
				url = "file:///tmp/Manga",
				publicUrl = "file:///tmp/Manga",
				source = LocalMangaSource,
				chapters = listOf(localChapter),
			),
			file = java.io.File("/tmp/Manga"),
		)

		val linked = LegacyChapterDownloadCompat.linkToRemote(remote, local)
		val linkedChapter = requireNotNull(linked.manga.chapters).single()

		assertEquals(remote.id, linked.manga.id)
		assertEquals(remoteChapter.id, linkedChapter.id)
		assertEquals(localUrl, linkedChapter.url)
		assertEquals(LocalMangaSource, linkedChapter.source)
	}

	@Test
	fun flatSidecarFreeCbzPagesAreDiscoveredInNaturalOrder() = runTest {
		val cbz = File(context.cacheDir, "local-reader-regression.cbz")
		try {
			ZipOutputStream(FileOutputStream(cbz)).use { zip ->
				for (name in listOf("10.webp", "2.webp", "1.webp")) {
					zip.putNextEntry(ZipEntry(name))
					zip.write(byteArrayOf(1, 2, 3))
					zip.closeEntry()
				}
			}
			val seed = remoteDetails()
			val chapter = requireNotNull(seed.chapters).first().copy(
				url = cbz.toUri().toString(),
				source = LocalMangaSource,
			)
			val pages = LocalMangaParser(cbz).getPages(chapter)

			assertEquals(3, pages.size)
			assertEquals(
				listOf("1.webp", "2.webp", "10.webp"),
				pages.map { page -> page.url.toUri().fragment },
			)
			assertTrue(pages.all { it.source == LocalMangaSource })
		} finally {
			cbz.delete()
		}
	}

	@Test
	fun deeplyNestedLocalFoldersDoNotConsumeCoroutineStack() = runTest {
		val root = File(context.cacheDir, "deep-local-cover-regression")
		root.deleteRecursively()
		try {
			var current = root
			assertTrue(current.mkdirs())
			repeat(256) { index ->
				current = File(current, "d$index")
				assertTrue(current.mkdir())
			}

			val parsed = LocalMangaParser(root).getManga(withDetails = false)
			assertEquals(root.toUri().toString(), parsed.manga.url)
		} finally {
			root.deleteRecursively()
		}
	}

	@Test
	fun perMangaChapterRoomFlowEmitsCommittedReplacement() = runBlocking {
		val details = remoteDetails()
		val originalChapters = requireNotNull(details.chapters)
		withDatabase { database ->
			val repository = createRepository(database)
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)

			val emissions = Channel<List<org.koitharu.kotatsu.parsers.model.MangaChapter>>(Channel.UNLIMITED)
			val collector = launch {
				repository.observeChapters(details.id).collect { emissions.send(it) }
			}
			try {
				val initial = withTimeout(5_000L) { emissions.receive() }
				assertEquals(originalChapters.map { it.id }, initial.map { it.id })

				val replacement = details.copy(
					chapters = originalChapters.mapIndexed { index, chapter ->
						if (index == 0) chapter.copy(title = "Stage 4 Room Flow") else chapter
					},
				)
				repository.storeManga(
					manga = replacement,
					replaceExisting = true,
					stripAppliedOverride = false,
					detailsFetched = true,
				)

				val updated = withTimeout(5_000L) { emissions.receive() }
				assertEquals("Stage 4 Room Flow", updated.first().title)
				assertEquals(originalChapters.map { it.id }, updated.map { it.id })
			} finally {
				collector.cancel()
				emissions.close()
			}
		}
	}

	@Test
	fun downloadOwnershipSurvivesDatabaseReopenForNormalAndPrivate() = runTest {
		val details = remoteDetails()
		withDatabase { database ->
			createRepository(database).storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			val dao = database.getFavouriteDownloadIndexDao()
			dao.upsert(
				listOf(
					FavouriteDownloadIndexEntity(
						mangaId = details.id,
						space = FavouriteSpace.NORMAL.dbValue,
						path = "/storage/normal/downloads/title",
					),
					FavouriteDownloadIndexEntity(
						mangaId = details.id,
						space = FavouriteSpace.PRIVATE.dbValue,
						path = "/storage/private/downloads/title",
					),
				),
			)
		}

		withDatabase { database ->
			val dao = database.getFavouriteDownloadIndexDao()
			assertEquals(
				"/storage/normal/downloads/title",
				dao.findEntry(FavouriteSpace.NORMAL.dbValue, details.id)?.path,
			)
			assertEquals(
				"/storage/private/downloads/title",
				dao.findEntry(FavouriteSpace.PRIVATE.dbValue, details.id)?.path,
			)
		}
	}



	@Test
	fun legacySidecarFreeDownloadInOldRootOpensOfflineWithoutLocalInventory() = runTest {
		val root = File(context.cacheDir, "legacy-offline-root")
		root.deleteRecursively()
		try {
			val seed = remoteDetails()
			val remoteChapter = requireNotNull(seed.chapters).first().copy(
				title = "Chapter 1",
				scanlator = "Team",
			)
			val remote = seed.copy(
				title = "Legacy Offline Title",
				altTitles = emptySet(),
				chapters = listOf(remoteChapter),
			)
			val mangaDir = File(root, "Legacy Offline Title")
			assertTrue(mangaDir.mkdirs())
			val chapterFile = File(mangaDir, "Team_Chapter 1.cbz")
			ZipOutputStream(FileOutputStream(chapterFile)).use { zip ->
				for (name in listOf("1.webp", "2.webp")) {
					zip.putNextEntry(ZipEntry(name))
					zip.write(byteArrayOf(1, 2, 3, 4))
					zip.closeEntry()
				}
			}

			val output = LocalMangaOutput.get(root, remote)
			assertNotNull("legacy root must be discovered deterministically", output)
			try {
				assertEquals(mangaDir.canonicalPath, requireNotNull(output).rootFile.canonicalPath)
				val parsed = LocalMangaParser(mangaDir).getManga(withDetails = true)
				val linked = LegacyChapterDownloadCompat.linkToRemote(remote, parsed)
				val linkedChapter = requireNotNull(linked.manga.chapters).single()
				assertEquals(remoteChapter.id, linkedChapter.id)
				assertEquals(LocalMangaSource, linkedChapter.source)
				assertEquals(
					mangaDir.toUri().buildUpon().fragment(chapterFile.name).build().toString(),
					linkedChapter.url,
				)

				// Reader resolves the directory#artifact URL without requiring the Local shelf to have
				// been opened first. This proves the linked legacy chapter remains directly readable.
				val pages = LocalMangaParser(mangaDir).getPages(linkedChapter)
				assertEquals(2, pages.size)
				assertTrue(pages.all { it.source == LocalMangaSource })
			} finally {
				output?.close()
			}
		} finally {
			root.deleteRecursively()
		}
	}

	@Test
	fun sameTitleNormalAndPrivateOwnershipStaysPathScopedWhenOneCopyIsDeleted() = runTest {
		val normalRoot = File(context.cacheDir, "acceptance-normal-root")
		val privateRoot = File(context.cacheDir, "acceptance-private-root")
		normalRoot.deleteRecursively()
		privateRoot.deleteRecursively()
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		try {
			val normalFile = File(normalRoot, "downloads/Same Title").apply { assertTrue(mkdirs()) }
			val privateFile = File(privateRoot, "downloads/Same Title").apply { assertTrue(mkdirs()) }
			prefs.edit()
				.putString(AppSettings.KEY_LOCAL_STORAGE, normalRoot.path)
				.putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, privateRoot.path)
				.commit()

			val details = remoteDetails().copy(title = "Same Title")
			withDatabase { database ->
				// The ownership table intentionally has an FK to manga. Production Favourites/Details
				// already owns this Room row before a download can be indexed, so the acceptance fixture
				// must establish the same invariant before testing cross-space path isolation.
				createRepository(database).storeManga(
					manga = details,
					replaceExisting = true,
					stripAppliedOverride = false,
					detailsFetched = true,
				)
				val destinationStore = DownloadDestinationStore(context, AppSettings(context))
				val ownership = FavouriteDownloadOwnershipIndex(database, destinationStore)
				val dao = database.getFavouriteDownloadIndexDao()

				ownership.emit(LocalManga(details, normalFile))
				assertNotNull(dao.findEntry(FavouriteSpace.NORMAL.dbValue, details.id))
				assertNull(dao.findEntry(FavouriteSpace.PRIVATE.dbValue, details.id))

				ownership.emit(LocalManga(details, privateFile))
				assertEquals(normalFile.canonicalPath, dao.findEntry(FavouriteSpace.NORMAL.dbValue, details.id)?.path)
				assertEquals(privateFile.canonicalPath, dao.findEntry(FavouriteSpace.PRIVATE.dbValue, details.id)?.path)

				ownership.removePath(privateFile)
				assertNull(dao.findEntry(FavouriteSpace.PRIVATE.dbValue, details.id))
				assertEquals(normalFile.canonicalPath, dao.findEntry(FavouriteSpace.NORMAL.dbValue, details.id)?.path)
				assertTrue(normalFile.exists())
			}
		} finally {
			prefs.edit()
				.remove(AppSettings.KEY_LOCAL_STORAGE)
				.remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT)
				.commit()
			normalRoot.deleteRecursively()
			privateRoot.deleteRecursively()
		}
	}

	@Test
	fun twoThousandChapterSnapshotStaysResponsiveAcrossColdReopenAndConcurrentRoomRefresh() = runBlocking {
		val seed = remoteDetails()
		val template = requireNotNull(seed.chapters).first()
		val chapters = List(2_000) { index ->
			template.copy(
				id = 20_000_000L + index,
				title = "Chapter ${index + 1}",
				number = (index + 1).toFloat(),
				url = template.url + "?acceptance=" + index,
				branch = if (index % 2 == 0) "main" else "alt",
			)
		}
		val details = seed.copy(chapters = chapters)

		var initialStoreMs = 0L
		withDatabase { database ->
			val repository = createRepository(database)
			val started = SystemClock.elapsedRealtime()
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			initialStoreMs = SystemClock.elapsedRealtime() - started
			assertEquals(2_000, database.getChaptersDao().count(details.id))
		}

		var firstEmissionMs = 0L
		var refreshMs = 0L
		withDatabase { database ->
			val repository = createRepository(database)
			val started = SystemClock.elapsedRealtime()
			val first = withTimeout(10_000L) {
				repository.observeChapters(details.id).first { it.size == 2_000 }
			}
			firstEmissionMs = SystemClock.elapsedRealtime() - started
			assertEquals(2_000, first.size)

			val byId = first.associateBy { it.id }
			assertEquals("main", byId.getValue(20_000_000L).branch)
			assertEquals("alt", byId.getValue(20_000_001L).branch)
			assertEquals("Chapter 2000", byId.getValue(20_001_999L).title)

			val refreshed = details.copy(
				chapters = chapters.mapIndexed { index, chapter ->
					if (index == 999) chapter.copy(title = "Concurrent Room refresh") else chapter
				},
			)
			val refreshStarted = SystemClock.elapsedRealtime()
			repository.storeManga(
				manga = refreshed,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			val updated = withTimeout(10_000L) {
				repository.observeChapters(details.id).first { list ->
					list.size == 2_000 && list.any { it.id == 20_000_999L && it.title == "Concurrent Room refresh" }
				}
			}
			refreshMs = SystemClock.elapsedRealtime() - refreshStarted
			assertEquals(2_000, updated.size)
			assertEquals(
				"Concurrent Room refresh",
				updated.first { it.id == 20_000_999L }.title,
			)
		}

		println(
			"P1_DETAILS_2000 initial_store_ms=$initialStoreMs " +
				"cold_first_emission_ms=$firstEmissionMs concurrent_refresh_ms=$refreshMs",
		)
		assertTrue("2k chapter initial persistence must not freeze", initialStoreMs < 15_000L)
		assertTrue("2k chapter cold Room-first emission must stay responsive", firstEmissionMs < 10_000L)
		assertTrue("2k chapter concurrent refresh must stay responsive", refreshMs < 15_000L)
	}


	@Test
	fun threeThousandChapterSnapshotReopensRoomFirstWithoutFreeze() = runBlocking {
		val seed = remoteDetails()
		val base = requireNotNull(seed.chapters).first()
		val chapters = List(3_000) { index ->
			base.copy(
				id = 500_000L + index,
				title = "Chapter ${index + 1}",
				number = (index + 1).toFloat(),
				branch = if (index % 2 == 0) "main" else "alt",
			)
		}
		val details = seed.copy(chapters = chapters)

		withDatabase { database ->
			createRepository(database).storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
			assertEquals(3_000, database.getChaptersDao().count(details.id))
		}

		withDatabase { database ->
			val repository = createRepository(database)
			var restored: org.koitharu.kotatsu.parsers.model.Manga? = null
			val elapsed = measureTimeMillis {
				restored = repository.findMangaById(details.id, withChapters = true)
			}
			val restoredChapters = requireNotNull(restored?.chapters)
			assertEquals(3_000, restoredChapters.size)
			assertEquals(chapters.first().id, restoredChapters.first().id)
			assertEquals(chapters.last().id, restoredChapters.last().id)
			assertEquals(chapters.first().branch, restoredChapters.first().branch)
			assertEquals(chapters.last().branch, restoredChapters.last().branch)
			assertTrue("3,000-chapter Room-first reopen took ${elapsed}ms", elapsed < 5_000L)
		}
	}

	@Test
	fun threeThousandChapterRoomFlowPublishesConcurrentReplacementWithoutEmptyRegression() = runBlocking {
		val seed = remoteDetails()
		val base = requireNotNull(seed.chapters).first()
		val chapters = List(3_000) { index ->
			base.copy(
				id = 600_000L + index,
				title = "Chapter ${index + 1}",
				number = (index + 1).toFloat(),
				branch = if (index % 3 == 0) "branch-a" else "branch-b",
			)
		}
		val details = seed.copy(chapters = chapters)

		withDatabase { database ->
			val repository = createRepository(database)
			repository.storeManga(
				manga = details,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)

			val emissions = Channel<List<org.koitharu.kotatsu.parsers.model.MangaChapter>>(Channel.UNLIMITED)
			val collector = launch {
				repository.observeChapters(details.id).collect { emissions.send(it) }
			}
			try {
				val first = withTimeout(5_000L) { emissions.receive() }
				assertEquals(3_000, first.size)
				assertTrue(first.isNotEmpty())

				val replacement = details.copy(
					chapters = chapters.mapIndexed { index, chapter ->
						if (index == 1_500) chapter.copy(title = "Concurrent Room replacement") else chapter
					},
				)
				repository.storeManga(
					manga = replacement,
					replaceExisting = true,
					stripAppliedOverride = false,
					detailsFetched = true,
				)

				val second = withTimeout(5_000L) { emissions.receive() }
				assertEquals(3_000, second.size)
				assertTrue(second.isNotEmpty())
				assertEquals("Concurrent Room replacement", second[1_500].title)
				assertEquals(chapters[1_500].id, second[1_500].id)
				assertEquals(chapters[1_500].branch, second[1_500].branch)
			} finally {
				collector.cancel()
				emissions.close()
			}
		}
	}

	private fun remoteDetails() = SampleData.mangaDetails.let { fixture ->
		val remoteSource = org.koitharu.kotatsu.core.model.MangaSource("MIHON_424242")
		fixture.copy(
			source = remoteSource,
			chapters = requireNotNull(fixture.chapters).map { chapter ->
				chapter.copy(source = remoteSource)
			},
		)
	}

	private suspend fun <T> withDatabase(block: suspend (MangaDatabase) -> T): T {
		val database = openDatabase()
		return try {
			block(database)
		} finally {
			database.close()
		}
	}

	private fun openDatabase(): MangaDatabase = Room
		.databaseBuilder(context, MangaDatabase::class.java, DB_NAME)
		.build()

	private fun createRepository(database: MangaDatabase) = MangaDataRepository(
		db = database,
		resolverProvider = unusedProvider<MangaLinkResolver>("MangaLinkResolver"),
		appShortcutManagerProvider = unusedProvider<AppShortcutManager>("AppShortcutManager"),
	)

	private fun <T> unusedProvider(name: String): Provider<T> = object : Provider<T> {
		override fun get(): T = error("$name is not used by chapter persistence regression tests")
	}

	private companion object {
		const val DB_NAME = "chapter-persistence-regression.db"
		const val MIGRATION_DB_NAME = "chapter-migration-regression.db"
	}
}
