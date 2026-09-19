package org.koitharu.kotatsu.core.db

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.migrations.Migration45To46
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.local.data.LegacyChapterDownloadCompat
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaLinkResolver
import javax.inject.Provider

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
	fun chapterGcResetsInitializationForRemovedSnapshot() = runTest {
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
