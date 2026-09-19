package org.koitharu.kotatsu.core.db

import androidx.room.Room
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
import org.koitharu.kotatsu.core.os.AppShortcutManager
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
	}

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun fetchedChaptersSurviveDatabaseReopen() = runTest {
		val details = SampleData.mangaDetails
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
		}
	}

	@Test
	fun lightweightMetadataWriteDoesNotDropPersistedChapters() = runTest {
		val details = SampleData.mangaDetails
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
	fun emptySuccessfulRefreshDoesNotEraseExistingChapterSnapshot() = runTest {
		val details = SampleData.mangaDetails
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
	}
}
