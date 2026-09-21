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
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.local.data.LegacyChapterDownloadCompat
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
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
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Provider
import kotlin.system.measureTimeMillis

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
				assertEquals(chapterFile.toUri().toString(), linkedChapter.url)

				val pages = LocalMangaParser(chapterFile).getPages(linkedChapter)
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
