package org.koitharu.kotatsu.local.domain

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.details.data.ChapterProgressEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.DownloadedContentClassifier
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceDiscovery
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceLifecycleObserver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Real Android Details lookup using flat CBZs from both ExHentai download paths. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExhentaiTitleDownloadCompatibilityTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var resolver: DownloadedMangaResolver
	@Inject lateinit var detailsLoader: DetailsLoadUseCase
	@Inject lateinit var destinationStore: DownloadDestinationStore
	@Inject lateinit var classifier: DownloadedContentClassifier
	@Inject lateinit var presence: ExhentaiPresenceDiscovery
	@Inject lateinit var localRepository: LocalMangaRepository
	@Inject lateinit var database: MangaDatabase
	@Inject lateinit var deletion: DeleteLocalMangaUseCase
	@Inject lateinit var data: MangaDataRepository
	@Inject lateinit var settings: AppSettings

	private lateinit var root: File
	private lateinit var prefs: SharedPreferences
	private lateinit var previousPrefs: Map<String, *>

	@Before fun setUp() {
		hilt.inject()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		root = File(context.cacheDir, "exhentai-title-${System.nanoTime()}").canonicalFile
		check(root.mkdirs())
		check(File(root, "private").mkdirs())
		prefs = PreferenceManager.getDefaultSharedPreferences(context)
		previousPrefs = prefs.all.toMap()
		prefs.edit(commit = true) {
			putString(AppSettings.KEY_LOCAL_STORAGE, root.path)
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, File(root, "private").path)
			remove("normal_download_legacy_roots")
			remove("private_download_legacy_roots")
		}
	}

	@After fun tearDown() {
		if (::previousPrefs.isInitialized) {
			prefs.edit(commit = true) {
				clear()
				for ((key, value) in previousPrefs) when (value) {
					is String -> putString(key, value)
					is Int -> putInt(key, value)
					is Long -> putLong(key, value)
					is Boolean -> putBoolean(key, value)
					is Float -> putFloat(key, value)
					is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
				}
			}
		}
		if (::root.isInitialized) root.deleteRecursively()
	}

	@Test fun gekkoFirstIsReadableInMainWithoutDownloadingAgain() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val before = chapter.readBytes()
		val online = gallery(990001, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val found = resolver.findSavedManga(online, FavouriteSpace.NORMAL)
		assertNotNull(found)
		assertEquals(chapter.canonicalFile, found!!.file.canonicalFile)
		assertTrue(downloaded(online, found))
		assertArrayEquals(before, chapter.readBytes())
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
	}

	@Test fun mainFirstIsReadableInGekkoEvenWhenReceiptGalleryDiffers() = runBlocking {
		val comment = ExhentaiChapterOwnership.scopedComment(
			990002, ExhentaiChapterOwnership.Receipt(FavouriteSpace.NORMAL, UUID.randomUUID()), false,
		)
		val chapter = archive("Legacy Gallery", "Chapter.cbz", comment = comment)
		val before = chapter.readBytes()
		val online = gallery(990003, GEKKO_SOURCE)
		val found = resolver.findSavedManga(online, FavouriteSpace.NORMAL)
		assertNotNull(found)
		assertEquals(chapter.canonicalFile, found!!.file.canonicalFile)
		assertTrue(downloaded(online, found))
		assertArrayEquals(before, chapter.readBytes())
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
	}

	@Test fun legacyUnderscoreSuffixIsReadableWithoutRenaming() = runBlocking {
		val chapter = archive("Legacy Gallery_1", "Chapter.cbz")
		val online = gallery(990004, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		assertEquals(chapter.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.NORMAL)?.file?.canonicalFile)
		assertTrue(File(chapter.parentFile, "Chapter.cbz").exists())
	}

	@Test fun twoSameTitleCopiesFailClosedRatherThanPickingTheFirst() = runBlocking {
		archive("Legacy Gallery", "Chapter.cbz")
		archive("Legacy Gallery_1", "Chapter.cbz")
		assertNull(resolver.findSavedManga(gallery(990005, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME),
			FavouriteSpace.NORMAL))
		assertNull(resolver.findSavedManga(gallery(990006, GEKKO_SOURCE),
			FavouriteSpace.NORMAL))
	}

	@Test fun privateCopyNeverAppearsInNormalDetails() = runBlocking {
		val privateChapter = archive("Legacy Gallery", "Chapter.cbz", private = true)
		val online = gallery(990007, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL))
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true))
		assertEquals(privateChapter.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.PRIVATE)?.file?.canonicalFile)
	}

	@Test fun titleOnlyMatchCannotDeleteWithoutTheSourcesOwnRow() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val gekko = gallery(990010, GEKKO_SOURCE)
		val saved = resolver.findSavedManga(gekko, FavouriteSpace.NORMAL)
		assertNotNull(saved)
		val rejection = runCatching {
			deletion.requireSourceOwnedExhentaiDeletion(gekko, FavouriteSpace.NORMAL, saved!!.file)
		}
		assertTrue(rejection.isFailure)
		assertTrue(chapter.exists())
	}

	@Test fun adoptedMainReceiptCannotBeDeletedByGekkoEvenWithItsOwnIndex() = runBlocking {
		val comment = ExhentaiChapterOwnership.scopedComment(
			990011, ExhentaiChapterOwnership.Receipt(FavouriteSpace.NORMAL, UUID.randomUUID()), false,
		)
		val chapter = archive("Legacy Gallery", "Chapter.cbz", comment = comment)
		val gekko = gallery(990012, GEKKO_SOURCE)
		data.storeManga(gekko, replaceExisting = true, detailsFetched = true)
		database.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(gekko.id, FavouriteSpace.NORMAL.dbValue, chapter.parentFile.canonicalPath),
		)
		try {
			val failure = runCatching {
				deletion.requireSourceOwnedExhentaiDeletion(gekko, FavouriteSpace.NORMAL, chapter.parentFile)
			}
			assertTrue(failure.isFailure)
			assertTrue(chapter.exists())
		} finally {
			database.getFavouriteDownloadIndexDao().delete(FavouriteSpace.NORMAL.dbValue, gekko.id)
		}
	}

	@Test fun activeDestinationStopsReadingOldFilesAndRestoresThemWhenSelectedAgain() = runBlocking {
		val old = archive("Legacy Gallery", "Chapter.cbz")
		val online = gallery(990027, GEKKO_SOURCE)
		assertEquals(old.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)?.file?.canonicalFile)
		val next = File(root, "new-normal")
		destinationStore.setRoot(FavouriteSpace.NORMAL, next)
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true))
		val newCopy = archive("Legacy Gallery", "Chapter.cbz", destination = next)
		assertEquals(newCopy.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)?.file?.canonicalFile)
		destinationStore.setRoot(FavouriteSpace.NORMAL, root)
		assertEquals(old.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)?.file?.canonicalFile)
		assertTrue(old.exists())
		assertTrue(newCopy.exists())
	}

	@Test fun nestedActivePrivateDestinationDoesNotLeakIntoNormal() = runBlocking {
		val privateChapter = archive("Legacy Gallery", "Chapter.cbz", private = true)
		val online = gallery(990028, GEKKO_SOURCE)
		assertFalse(destinationStore.isVisibleInActiveExhentaiDestination(FavouriteSpace.NORMAL, privateChapter))
		assertTrue(destinationStore.isVisibleInActiveExhentaiDestination(FavouriteSpace.PRIVATE, privateChapter))
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true))
		assertEquals(privateChapter.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.PRIVATE, forExhentaiDetails = true)?.file?.canonicalFile)
	}

	@Test fun equalActiveDestinationsAllowReadOnlySharing() = runBlocking {
		prefs.edit(commit = true) {
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path)
		}
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val online = gallery(990029, GEKKO_SOURCE)
		for (space in FavouriteSpace.entries) {
			assertTrue(destinationStore.isVisibleInActiveExhentaiDestination(space, chapter))
			assertEquals(chapter.canonicalFile,
				resolver.findSavedManga(online, space, forExhentaiDetails = true)?.file?.canonicalFile)
		}
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.PRIVATE.dbValue, online.id))
	}

	@Test fun movingPrivateAwayFromOldSharedRootDoesNotReadPrivateHistory() = runBlocking {
		prefs.edit(commit = true) {
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path)
		}
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val online = gallery(990030, GEKKO_SOURCE)
		assertEquals(chapter.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.PRIVATE, forExhentaiDetails = true)?.file?.canonicalFile)
		destinationStore.setRoot(FavouriteSpace.PRIVATE, File(root, "private"))
		assertTrue(destinationStore.readableRoots(FavouriteSpace.PRIVATE).any { it.canonicalFile == root })
		assertNull(resolver.findSavedManga(online, FavouriteSpace.PRIVATE, forExhentaiDetails = true))
		assertEquals(chapter.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)?.file?.canonicalFile)
	}

	@Test fun detailsReadsIndexedCbzWithForeignGalleryAndComicInfoWithoutChangingOwnership() = runBlocking {
		val foreignIndex = """{"id":888100,"title":"Other gallery","url":"https://exhentai.org/g/888100/token/",
			"source":"TSUKI:MIYORARE:miyorare-global:EXHENTAI","tags":[],
			"chapters":{"888101":{"url":"chapter","entries":"[0-9]+","number":1}}}"""
		val chapter = archive(
			"Legacy Gallery", "Chapter.cbz",
			comment = ExhentaiChapterOwnership.comment(888100),
			index = foreignIndex,
			comicInfo = "<ComicInfo><Web>https://exhentai.org/g/888100/token/</Web></ComicInfo>",
		)
		val original = chapter.readBytes()
		for (source in listOf(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, GEKKO_SOURCE)) {
			val online = gallery(990016, source)
			assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true))
			val choices = resolver.getExhentaiReadChoices(online, FavouriteSpace.NORMAL)
			assertEquals(1, choices.size)
			assertEquals(888100L, choices.single().galleryId)
			val selected = requireNotNull(resolver.selectExhentaiReadChoice(online, FavouriteSpace.NORMAL, choices.single()))
			val found = resolver.resolveExhentaiSessionChoice(online, FavouriteSpace.NORMAL, selected)
			assertNotNull(found)
			assertEquals(chapter.canonicalFile, found!!.file.canonicalFile)
			// A foreign optional index must not become the identity of this read-only local copy.
			assertTrue(found.manga.id != 888100L)
			assertTrue(downloaded(online, found))
			val pages = localRepository.getPages(found.manga.chapters!!.single())
			assertEquals(1, pages.size)
			assertEquals(URI_SCHEME_ZIP, android.net.Uri.parse(pages.single().url).scheme)
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
		}
		assertArrayEquals(original, chapter.readBytes())
	}

	@Test fun embeddedInternalMangaIdIsNotMistakenForGalleryId() = runBlocking {
		val online = gallery(990035, GEKKO_SOURCE)
		val index = """{"id":123456789,"url":"https://exhentai.org/g/990035/token/",
			"source":"TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"}"""
		val chapter = archive("Legacy Gallery", "Chapter.cbz", index = index)
		val found = resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)
		assertEquals(chapter.canonicalFile, found?.file?.canonicalFile)
		assertTrue(resolver.getExhentaiReadChoices(online, FavouriteSpace.NORMAL).isEmpty())
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
	}

	@Test fun detailsReadsMalformedOptionalIndexAsFlatChapter() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz", index = "{malformed json")
		val before = chapter.readBytes()
		for (source in listOf(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, GEKKO_SOURCE)) {
			val online = gallery(990026, source)
			val found = resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)
			assertEquals(chapter.canonicalFile, found?.file?.canonicalFile)
			assertTrue(downloaded(online, found!!))
			assertEquals(1, localRepository.getPages(found.manga.chapters!!.single()).size)
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
		}
		assertArrayEquals(before, chapter.readBytes())
	}

	@Test fun exactGalleryIdStillRequiresChoiceOnEveryRead() = runBlocking {
		val legacy = archive("Legacy Gallery", "Chapter.cbz")
		val receipt = ExhentaiChapterOwnership.scopedComment(
			990017, ExhentaiChapterOwnership.Receipt(FavouriteSpace.NORMAL, UUID.randomUUID()), false,
		)
		val exact = archive("Legacy Gallery_2", "Chapter.cbz", comment = receipt)
		for (source in listOf(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, GEKKO_SOURCE)) {
			val online = gallery(990017, source)
			val space = FavouriteSpace.NORMAL
			assertNull(resolver.findSavedManga(online, space, forExhentaiDetails = true))
			val options = resolver.getExhentaiReadChoices(online, space)
			assertEquals(setOf(legacy.canonicalPath, exact.canonicalPath), options.map { it.path }.toSet())
			val session = requireNotNull(resolver.selectExhentaiReadChoice(online, space, options.single { it.path == legacy.canonicalPath }))
			assertEquals(legacy.canonicalFile, resolver.resolveExhentaiSessionChoice(online, space, session)?.file?.canonicalFile)
			assertNull(resolver.findSavedManga(online, space, forExhentaiDetails = true))
			assertEquals(options, resolver.getExhentaiReadChoices(online, space))
			assertNull(resolver.resolveExhentaiSessionChoice(online.copy(id = online.id + 1), space, session))
			assertNull(resolver.resolveExhentaiSessionChoice(online, FavouriteSpace.PRIVATE, session))
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, online.id))
		}
		assertTrue(legacy.isFile)
		assertTrue(exact.isFile)
	}

	@Test fun readerRechecksAmbiguityBeforePublishingAnOnlineChapter() = runBlocking {
		val first = archive("Legacy Gallery", "Chapter.cbz")
		val online = gallery(990082, GEKKO_SOURCE)
		data.storeManga(online, replaceExisting = true, detailsFetched = true)
		val intent = MangaIntent(SavedStateHandle(mapOf(AppRouter.KEY_MANGA to ParcelableManga(online))))
		val space = FavouriteSpace.NORMAL
		var requests = 0
		var added: File? = null
		val details = detailsLoader(
			intent = intent, force = false, favouriteSpace = space,
			preferLocalBeforeInitialSnapshot = true,
			chooseExhentaiFile = { manga, requestedSpace, choices ->
				requests++
				if (requests == 1) {
					assertTrue(choices.isEmpty())
					added = archive("Legacy Gallery_1", "Chapter.cbz")
					null
				} else {
					val second = requireNotNull(added)
					assertEquals(setOf(first.canonicalPath, second.canonicalPath), choices.map { it.path }.toSet())
					resolver.selectExhentaiReadChoice(manga, requestedSpace,
						choices.single { it.path == second.canonicalPath })
				}
			},
		).first()
		assertEquals(2, requests)
		assertEquals(requireNotNull(added).canonicalFile, details.local?.file?.canonicalFile)
		assertEquals(online.id, details.sourceManga.id)
		assertEquals(online.source.name, details.sourceManga.source.name)
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, online.id))
	}

	@Test fun readerLocalFirstSnapshotUsesOnlyTheChosenCbzAndNewSessionsChooseAgain() = runBlocking {
		val first = archive("Legacy Gallery", "Chapter.cbz")
		val second = archive("Legacy Gallery_1", "Chapter.cbz")
		val online = gallery(990081, GEKKO_SOURCE)
		data.storeManga(online, replaceExisting = true, detailsFetched = true)
		val intent = MangaIntent(SavedStateHandle(mapOf(AppRouter.KEY_MANGA to ParcelableManga(online))))
		val space = FavouriteSpace.NORMAL
		var requests = 0
		for (file in listOf(first, second)) {
			val details = detailsLoader(
				intent = intent, force = false, favouriteSpace = space,
				preferLocalBeforeInitialSnapshot = true,
				chooseExhentaiFile = { manga, requestedSpace, choices ->
					requests++
					assertEquals(online.id, manga.id)
					assertEquals(online.source.name, manga.source.name)
					assertEquals(space, requestedSpace)
					assertEquals(setOf(first.canonicalPath, second.canonicalPath), choices.map { it.path }.toSet())
					resolver.selectExhentaiReadChoice(manga, requestedSpace, choices.single { it.path == file.canonicalPath })
				},
			).first()
			assertEquals(file.canonicalFile, details.local?.file?.canonicalFile)
			assertEquals(online.id, details.sourceManga.id)
			assertEquals(online.source.name, details.sourceManga.source.name)
			assertEquals(online.chapters!!.single().id, details.allChapters.single().id)
		}
		assertEquals(2, requests)
		val cancelled = try {
			detailsLoader(
				intent = intent, force = false, favouriteSpace = space,
				preferLocalBeforeInitialSnapshot = true,
				chooseExhentaiFile = { _, _, _ -> null },
			).first()
			false
		} catch (_: CancellationException) {
			true
		}
		assertTrue("Cancellation must prevent any chapter snapshot from being published", cancelled)
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, online.id))
	}

	@Test fun detailsReadsUnmarkedLegacyCbzWithInvalidOptionalXmlInBothSources() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz", comicInfo = "<bad-xml")
		val before = chapter.readBytes()
		for (source in listOf(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, GEKKO_SOURCE)) {
			val online = gallery(990019, source)
			val found = resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)
			assertNotNull(found)
			assertEquals(chapter.canonicalFile, found!!.file.canonicalFile)
			assertTrue(downloaded(online, found))
			assertEquals(1, localRepository.getPages(found.manga.chapters!!.single()).size)
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
		}
		assertArrayEquals(before, chapter.readBytes())
	}

	@Test fun detailsIgnoresEmptyDuplicateFolderAndReadsOnlyLegacyCbz() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val emptyDuplicate = File(root, "downloads/ExHentai (OTHER)/Legacy Gallery_1")
		check(emptyDuplicate.mkdirs())
		val online = gallery(990023, GEKKO_SOURCE)
		assertEquals(chapter.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)?.file?.canonicalFile)
		assertTrue(emptyDuplicate.isDirectory)
		assertTrue(emptyDuplicate.listFiles().isNullOrEmpty())
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
	}

	@Test fun detailsRejectsUnreadableCbzDespiteMatchingTitle() = runBlocking {
		val dir = File(root, "downloads/ExHentai (OTHER)/Legacy Gallery")
		check(dir.mkdirs())
		val badArchive = File(dir, "Chapter.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
		val online = gallery(990022, GEKKO_SOURCE)
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true))
		assertEquals(3L, badArchive.length())
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
	}

	@Test fun detailsCanReadForeignSpaceReceiptWithoutGrantingDeletionRights() = runBlocking {
		val privateReceipt = ExhentaiChapterOwnership.scopedComment(
			990020, ExhentaiChapterOwnership.Receipt(FavouriteSpace.PRIVATE, UUID.randomUUID()), false,
		)
		val chapter = archive("Legacy Gallery", "Chapter.cbz", comment = privateReceipt)
		val online = gallery(990020, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL))
		val found = resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)
		assertEquals(chapter.canonicalFile, found?.file?.canonicalFile)
		assertEquals(1, localRepository.getPages(found!!.manga.chapters!!.single()).size)
		assertTrue(chapter.isFile)
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
	}

	@Test fun detailsCanReadHistoricalSharedRootWithoutWritingOwnership() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val before = chapter.readBytes()
		val online = gallery(990021, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		prefs.edit(commit = true) { remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT) }
		assertEquals(root.canonicalFile, destinationStore.effectiveRoot(FavouriteSpace.PRIVATE)?.canonicalFile)
		destinationStore.setRoot(FavouriteSpace.PRIVATE, File(root, "private"))
		assertFalse(destinationStore.rootsOverlap())
		assertTrue(destinationStore.readableRoots(FavouriteSpace.PRIVATE).any {
			it.canonicalFile == root.canonicalFile
		})
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL))
		assertEquals(chapter.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)?.file?.canonicalFile)
		assertArrayEquals(before, chapter.readBytes())
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
	}

	@Test fun sharedRootIsReadOnlyInDetailsAndStrictForOtherCallers() = runBlocking {
		prefs.edit(commit = true) {
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path)
		}
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val online = gallery(990008, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL))
		val found = resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)
		assertEquals(chapter.canonicalFile, found?.file?.canonicalFile)
		assertTrue(downloaded(online, found!!))
		assertEquals(1, localRepository.getPages(found.manga.chapters!!.single()).size)
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
		val ownerId = 990009L // Gekko's existing task owns the folder, not Main.
		data.storeManga(gallery(ownerId, GEKKO_SOURCE), replaceExisting = true, detailsFetched = true)
		database.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(ownerId, FavouriteSpace.NORMAL.dbValue, chapter.parentFile.canonicalPath),
		)
		try {
			assertNotNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL))
			assertNull(resolver.findSavedManga(online, FavouriteSpace.PRIVATE))
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
		} finally {
			database.getFavouriteDownloadIndexDao().delete(FavouriteSpace.NORMAL.dbValue, ownerId)
		}
	}

	@Test fun detailsReadsUserLegacyFolderAfterNewReceiptCopyIsRemoved() = runBlocking {
		val title = "[Jinsei Yokosuberi. (Alexi Laiho)] Amaama Succubus Maid Lise-san"
		val legacy = archive("Amaama Succubus Maid Lise-san", "Chapter.cbz")
		val receipt = ExhentaiChapterOwnership.scopedComment(
			3480905, ExhentaiChapterOwnership.Receipt(FavouriteSpace.NORMAL, UUID.randomUUID()), false,
		)
		val newer = archive(title, "Chapter.cbz", comment = receipt)
		val legacyBytes = legacy.readBytes()
		for (source in listOf(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, GEKKO_SOURCE)) {
			val online = gallery(3480905, source).copy(title = title)
			assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true))
			assertEquals(setOf(newer.canonicalPath, legacy.canonicalPath),
				resolver.getExhentaiReadChoices(online, FavouriteSpace.NORMAL).map { it.path }.toSet())
		}
		assertTrue(newer.delete())
		for (source in listOf(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, GEKKO_SOURCE)) {
			val online = gallery(3480905, source).copy(title = title)
			val found = resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)
			assertEquals(legacy.canonicalFile, found?.file?.canonicalFile)
			assertTrue(downloaded(online, found!!))
			assertEquals(1, localRepository.getPages(found.manga.chapters!!.single()).size)
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
		}
		assertArrayEquals(legacyBytes, legacy.readBytes())
	}

	@Test fun unreadableDuplicateStillRequiresExplicitChoiceAndCannotBeOpened() = runBlocking {
		val badDir = File(root, "downloads/ExHentai (OTHER)/Legacy Gallery")
		check(badDir.mkdirs())
		val bad = File(badDir, "Chapter.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
		val good = archive("Legacy Gallery_1", "Chapter.cbz")
		for (source in listOf(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, GEKKO_SOURCE)) {
			val online = gallery(990024, source)
			val space = FavouriteSpace.NORMAL
			assertNull(resolver.findSavedManga(online, space, forExhentaiDetails = true))
			val choices = resolver.getExhentaiReadChoices(online, space)
			assertEquals(2, choices.size)
			val invalid = choices.single { it.path == bad.canonicalPath }
			assertFalse(invalid.readable)
			assertNull(resolver.selectExhentaiReadChoice(online, space, invalid))
			val selected = requireNotNull(resolver.selectExhentaiReadChoice(online, space, choices.single { it.path == good.canonicalPath }))
			val found = requireNotNull(resolver.resolveExhentaiSessionChoice(online, space, selected))
			assertEquals(good.canonicalFile, found.file.canonicalFile)
			assertEquals(1, localRepository.getPages(found.manga.chapters!!.single()).size)
		}
		assertEquals(3L, bad.length())
	}

	@Test fun mainGekkoDownloadedIndexUsesOnlyActiveDestinationNotHistoricalRoots() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val online = gallery(990034, GEKKO_SOURCE)
		data.storeManga(online, replaceExisting = true, detailsFetched = true)
		database.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(
			online.id, FavouriteSpace.NORMAL.dbValue, chapter.canonicalPath,
		))
		try {
			assertTrue(online.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(online.id)))
			assertTrue(sqlDownloadedCondition(online.id))
			destinationStore.setRoot(FavouriteSpace.NORMAL, File(root, "new-normal"))
			assertTrue(destinationStore.readableRoots(FavouriteSpace.NORMAL).any { it.canonicalFile == root })
			assertFalse(online.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(online.id)))
			assertFalse(online.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL))
			assertFalse(sqlDownloadedCondition(online.id))
		} finally {
			database.getFavouriteDownloadIndexDao().delete(FavouriteSpace.NORMAL.dbValue, online.id)
		}
	}

	@Test fun manualCorruptCbzPresenceAppearsInMainAndGekkoWithoutOwnership() = runBlocking {
		val manual = File(root, "downloads/ExHentai (OTHER)/Legacy Gallery/Chapter.cbz")
		check(manual.parentFile!!.mkdirs() || manual.parentFile!!.isDirectory)
		manual.writeText("not a readable CBZ")
		val main = gallery(990060, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val gekko = gallery(990061, GEKKO_SOURCE)
		val unrelated = gallery(990062, "TSUKI:OTHER:other:EXHENTAI")
		for (manga in listOf(main, gekko, unrelated)) {
			data.storeManga(manga, replaceExisting = true, detailsFetched = true)
		}

		presence.run(full = true)
		val ids = classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(main.id, gekko.id, unrelated.id))
		assertTrue(main.id in ids)
		assertTrue(gekko.id in ids)
		assertFalse(unrelated.id in ids)
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, main.id))
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, gekko.id))
		assertFalse(classifier.getDownloadedIds(FavouriteSpace.PRIVATE, listOf(main.id, gekko.id)).contains(main.id))

		assertTrue(manual.delete())
		presence.run(full = true)
		assertFalse(main.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(main.id, gekko.id)))
		assertFalse(gekko.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(main.id, gekko.id)))
	}

	@Test fun lifecycleStartupResumeToggleAndDestinationChangesDispatchCorrectScans() = runBlocking {
		withContext(Dispatchers.Main.immediate) {
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			val name = "exhentai-presence-lifecycle-${System.nanoTime()}"
			val isolatedPrefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
			isolatedPrefs.edit(commit = true) {
				putBoolean(ExhentaiPresenceDiscovery.KEY_SCAN_ON_RESUME, false)
			}
			val calls = mutableListOf<Boolean>()
			val owner = object : LifecycleOwner {
				override val lifecycle = LifecycleRegistry(this)
			}
			val observer = ExhentaiPresenceLifecycleObserver(
				scope = CoroutineScope(coroutineContext),
				prefs = isolatedPrefs,
				scan = { full -> calls += full },
			)
			try {
				owner.lifecycle.addObserver(observer)
				owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
				assertEquals(listOf(true), calls)
				owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
				assertEquals(listOf(true), calls)
				owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

				isolatedPrefs.edit(commit = true) {
					putString(AppSettings.KEY_LOCAL_STORAGE, "/test/new-normal")
				}
				isolatedPrefs.edit(commit = true) {
					putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, "/test/new-private")
				}
				assertEquals(listOf(true, false, false), calls)

				isolatedPrefs.edit(commit = true) {
					putBoolean(ExhentaiPresenceDiscovery.KEY_SCAN_ON_RESUME, true)
				}
				owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
				assertEquals(listOf(true, false, false, false), calls)
				owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
				isolatedPrefs.edit(commit = true) {
					putBoolean(ExhentaiPresenceDiscovery.KEY_SCAN_ON_RESUME, false)
				}
				owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
				assertEquals(listOf(true, false, false, false), calls)
			} finally {
				owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
				owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
				context.deleteSharedPreferences(name)
			}
		}
	}

	/**
	 * A temporarily missing mount must not erase the persisted Downloaded badge.
	 * Successful recovery/scan, not temporary storage loss, is what clears stale presence.
	 */
	@Test fun unavailableActiveStorageKeepsManualPresenceUntilSuccessfulRescan() = runBlocking {
		val manga = gallery(990075, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		data.storeManga(manga, replaceExisting = true, detailsFetched = true)
		val manual = File(root, "downloads/ExHentai (OTHER)/Legacy Gallery/Chapter.cbz")
		check(manual.parentFile!!.mkdirs() || manual.parentFile!!.isDirectory)
		manual.writeText("corrupt archive still counts as present")
		val ids = listOf(manga.id)

		presence.run(full = true)
		assertTrue(manga.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, ids))
		assertTrue(sqlDownloadedCondition(manga.id))

		// Simulate a temporarily unmounted selected destination, preserving its configured path.
		val parked = File(root.parentFile, root.name + "-temporarily-unavailable")
		check(!parked.exists())
		assertTrue(root.renameTo(parked))
		try {
			presence.run(full = true)
			assertTrue(FavouriteSpace.NORMAL in presence.state.value.unavailableSpaces)
			assertTrue(manga.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, ids))
			assertTrue(sqlDownloadedCondition(manga.id))
			assertTrue(database.getExhentaiPresenceDao().findForSpace(FavouriteSpace.NORMAL.dbValue)
				.any { it.mangaId == manga.id })
		} finally {
			assertTrue("Failed to restore test destination", parked.renameTo(root))
		}

		assertTrue(manual.delete())
		presence.run(full = true)
		assertFalse(FavouriteSpace.NORMAL in presence.state.value.unavailableSpaces)
		assertFalse(manga.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, ids))
		assertFalse(sqlDownloadedCondition(manga.id))
	}

	@Test fun directActiveRootLegacyCbzUsesIndexOnlyDownloadedClassification() = runBlocking {
		val temporary = archive("Legacy Gallery", "Chapter.cbz")
		val direct = File(root, "E-Hentai (EN)/Legacy Gallery/Chapter.cbz")
		check(direct.parentFile!!.mkdirs())
		temporary.copyTo(direct)
		assertTrue(temporary.delete())
		val online = gallery(990058, GEKKO_SOURCE)
		val unrelated = gallery(990059, "TSUKI:OTHER:unrelated:OTHER")
		data.storeManga(online, replaceExisting = true, detailsFetched = true)
		data.storeManga(unrelated, replaceExisting = true, detailsFetched = true)
		val dao = database.getFavouriteDownloadIndexDao()
		try {
			// Details uses the active destination. Cards intentionally require persisted index evidence.
			assertEquals(direct.canonicalFile,
				resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true)?.file?.canonicalFile)
			assertFalse(online.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(online.id)))
			assertFalse(sqlDownloadedCondition(online.id))
			dao.upsert(FavouriteDownloadIndexEntity(online.id, FavouriteSpace.NORMAL.dbValue, direct.canonicalPath))
			dao.upsert(FavouriteDownloadIndexEntity(unrelated.id, FavouriteSpace.NORMAL.dbValue, direct.canonicalPath))
			assertTrue(online.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(online.id)))
			assertTrue(online.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL))
			assertTrue(sqlDownloadedCondition(online.id))
			assertFalse(unrelated.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(unrelated.id)))
			assertFalse(sqlDownloadedCondition(unrelated.id))
			destinationStore.setRoot(FavouriteSpace.NORMAL, File(root, "new-normal"))
			assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true))
			assertFalse(online.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(online.id)))
			assertFalse(sqlDownloadedCondition(online.id))
			assertTrue(direct.isFile)
		} finally {
			dao.delete(FavouriteSpace.NORMAL.dbValue, online.id)
			dao.delete(FavouriteSpace.NORMAL.dbValue, unrelated.id)
		}
	}

	@Test fun comicInfoGalleryIdRequiresExplicitChoiceWhenItDisagrees() = runBlocking {
		val chapter = archive(
			"Legacy Gallery", "Chapter.cbz",
			comicInfo = "<ComicInfo><Web>https://exhentai.org/g/990037/token/</Web></ComicInfo>",
		)
		val online = gallery(990038, GEKKO_SOURCE)
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL, forExhentaiDetails = true))
		val choices = resolver.getExhentaiReadChoices(online, FavouriteSpace.NORMAL)
		assertEquals(1, choices.size)
		assertEquals(990037L, choices.single().galleryId)
		assertEquals(chapter.canonicalPath, choices.single().path)
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
	}

	@Test fun confirmedDeleteRecognizesSingleCbzDirectlyUnderSourceDirectory() = runBlocking {
		val source = File(root, "downloads/ExHentai (OTHER)").apply { check(mkdirs() || isDirectory) }
		val file = File(source, "Legacy Gallery.cbz")
		ZipOutputStream(file.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("1.webp"))
			zip.write(byteArrayOf(1, 2, 3))
			zip.closeEntry()
		}
		val manga = gallery(990039, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val local = resolver.findSavedManga(manga, FavouriteSpace.NORMAL, forExhentaiDetails = true)
		assertEquals(file.canonicalFile, local?.file?.canonicalFile)
		val approval = deletion.previewExhentaiPhysicalDeletion(manga, FavouriteSpace.NORMAL, local!!)
		assertNotNull(approval)
		assertEquals(file.canonicalPath, approval!!.canonicalPath)
		assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(manga, FavouriteSpace.NORMAL, local, approval))
		assertFalse(file.exists())
	}

	@Test fun confirmedDeleteRejectsSameSizeReplacementEvenWithRestoredMtime() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990040, GEKKO_SOURCE)
		val local = resolver.findSavedManga(manga, FavouriteSpace.NORMAL, forExhentaiDetails = true)!!
		val approval = deletion.previewExhentaiPhysicalDeletion(manga, FavouriteSpace.NORMAL, local)!!
		val bytes = chapter.readBytes()
		val changed = bytes.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }
		chapter.writeBytes(changed)
		assertTrue(chapter.setLastModified(approval.lastModified))
		assertEquals(approval.length, chapter.length())
		assertEquals(approval.lastModified, chapter.lastModified())
		// The modified archive still enumerates a readable page. The rejection must
		// come from the content fingerprint, not from a corrupt ZIP/layout.
		assertNotNull(org.koitharu.kotatsu.local.data.ExhentaiDownloadedCopy.readTitleMatchedChapter(
			manga, chapter, allowIndexed = true,
		))
		var rejected = false
		try {
			deletion.deleteConfirmedExhentaiPhysicalFile(manga, FavouriteSpace.NORMAL, local, approval)
		} catch (e: UnverifiedDownloadRemovalException) {
			rejected = true
		}
		assertTrue(rejected)
		assertTrue(chapter.exists())
	}

	@Test fun deletingOneLegacyCbzKeepsParentOwnershipIfAnotherCbzRemains() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val sibling = File(chapter.parentFile, "Other.cbz")
		chapter.copyTo(sibling)
		val manga = gallery(990041, GEKKO_SOURCE)
		val another = gallery(990042, GEKKO_SOURCE)
		data.storeManga(another, replaceExisting = true, detailsFetched = true)
		data.storeManga(manga, replaceExisting = true, detailsFetched = true)
		for (id in listOf(manga.id, another.id)) {
			database.getFavouriteDownloadIndexDao().upsert(
				FavouriteDownloadIndexEntity(
					id, FavouriteSpace.NORMAL.dbValue, chapter.parentFile.canonicalPath,
				),
			)
		}
		val space = FavouriteSpace.NORMAL
		val choices = resolver.getExhentaiReadChoices(manga, space)
		assertEquals(setOf(chapter.canonicalPath, sibling.canonicalPath), choices.map { it.path }.toSet())
		val selected = requireNotNull(resolver.selectExhentaiReadChoice(manga, space, choices.single { it.path == chapter.canonicalPath }))
		val local = requireNotNull(resolver.resolveExhentaiSessionChoice(manga, space, selected))
		// A legacy directory ownership row must not redirect confirmed Delete to the directory.
		assertEquals(chapter.canonicalFile, local.file.canonicalFile)
		val approval = checkNotNull(deletion.previewExhentaiPhysicalDeletion(manga, FavouriteSpace.NORMAL, local))
		try {
			assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(manga, FavouriteSpace.NORMAL, local, approval))
			assertFalse(chapter.exists())
			assertTrue(sibling.isFile)
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(
				FavouriteSpace.NORMAL.dbValue, manga.id,
			))
			assertNotNull(database.getFavouriteDownloadIndexDao().findEntry(
				FavouriteSpace.NORMAL.dbValue, another.id,
			))
		} finally {
			database.getFavouriteDownloadIndexDao().delete(FavouriteSpace.NORMAL.dbValue, manga.id)
			database.getFavouriteDownloadIndexDao().delete(FavouriteSpace.NORMAL.dbValue, another.id)
		}
	}

	@Test fun privateReadChoiceIsSessionOnlyAndIsolated() = runBlocking {
		val first = archive("Legacy Gallery", "Chapter.cbz", private = true)
		archive("Legacy Gallery_1", "Chapter.cbz", private = true)
		val manga = gallery(990055, GEKKO_SOURCE)
		val options = resolver.getExhentaiReadChoices(manga, FavouriteSpace.PRIVATE)
		assertEquals(2, options.size)
		val key = "exhentai_read_choice_${FavouriteSpace.PRIVATE.dbValue}_${manga.source.name}_${manga.id}"
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val privatePrefs = context.getSharedPreferences("exhentai_read_choices", android.content.Context.MODE_PRIVATE)
		privatePrefs.edit(commit = true) { remove(key) }
		val session = requireNotNull(resolver.selectExhentaiReadChoice(manga, FavouriteSpace.PRIVATE, options.single { it.path == first.canonicalPath }))
		assertNull(prefs.getString(key, null))
		assertFalse(privatePrefs.contains(key))
		assertEquals(first.canonicalFile, resolver.resolveExhentaiSessionChoice(manga, FavouriteSpace.PRIVATE, session)?.file?.canonicalFile)
		assertNull(resolver.resolveExhentaiSessionChoice(manga, FavouriteSpace.NORMAL, session))
		assertNull(resolver.findSavedManga(manga, FavouriteSpace.PRIVATE, forExhentaiDetails = true))
		assertEquals(options, resolver.getExhentaiReadChoices(manga, FavouriteSpace.PRIVATE))
	}

	@Test fun selectedGalleryChoiceRejectsSameSizeSameMtimeCbzReplacement() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		archive("Legacy Gallery_1", "Chapter.cbz")
		val online = gallery(990057, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val choices = resolver.getExhentaiReadChoices(online, space)
		assertEquals(2, choices.size)
		val selected = choices.single { it.path == chapter.canonicalPath }
		val key = "exhentai_read_choice_${space.dbValue}_${online.source.name}_${online.id}"
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val savedChoices = context.getSharedPreferences("exhentai_read_choices", android.content.Context.MODE_PRIVATE)
		try {
			val session = requireNotNull(resolver.selectExhentaiReadChoice(online, space, selected))
			assertEquals(chapter.canonicalFile,
				resolver.resolveExhentaiSessionChoice(online, space, session)?.file?.canonicalFile)
			val original = chapter.readBytes()
			val originalLength = chapter.length()
			val originalModified = chapter.lastModified()
			val replacement = archive("Detached Fixture", "Chapter.cbz", pageBytes = byteArrayOf(3, 2, 1))
			assertEquals(originalLength, replacement.length())
			replacement.copyTo(chapter, overwrite = true)
			assertTrue(chapter.setLastModified(originalModified))
			assertFalse(original.contentEquals(chapter.readBytes()))
			assertEquals(originalLength, chapter.length())
			assertEquals(originalModified, chapter.lastModified())
			// An unchanged candidate path/size/mtime is not the same physical content.
			assertNull(resolver.selectExhentaiReadChoice(online, space, selected))
			assertNull(resolver.resolveExhentaiSessionChoice(online, space, session))
			assertNull(resolver.findSavedManga(online, space, forExhentaiDetails = true))
			assertEquals(2, resolver.getExhentaiReadChoices(online, space).size)
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, online.id))
		} finally {
			savedChoices.edit(commit = true) { remove(key) }
		}
	}

	@Test fun oldPersistedChoiceIsIgnoredAndNewReadRequiresChoice() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		archive("Legacy Gallery_1", "Chapter.cbz")
		val manga = gallery(990056, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val options = resolver.getExhentaiReadChoices(manga, space)
		assertEquals(2, options.size)
		val selected = options.single { it.path == chapter.canonicalPath }
		val key = "exhentai_read_choice_${space.dbValue}_${manga.source.name}_${manga.id}"
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val choices = context.getSharedPreferences("exhentai_read_choices", android.content.Context.MODE_PRIVATE)
		try {
			choices.edit(commit = true) { putString(key, "old choice must be ignored") }
			val session = requireNotNull(resolver.selectExhentaiReadChoice(manga, space, selected))
			assertEquals("old choice must be ignored", choices.getString(key, null))
			assertNull(prefs.getString(key, null))
			assertEquals(chapter.canonicalFile, resolver.resolveExhentaiSessionChoice(manga, space, session)?.file?.canonicalFile)
			assertNull(resolver.findSavedManga(manga, space, forExhentaiDetails = true))
			assertEquals(options, resolver.getExhentaiReadChoices(manga, space))
			destinationStore.setRoot(space, File(root, "new-normal"))
			assertNull(resolver.resolveExhentaiSessionChoice(manga, space, session))
		} finally {
			choices.edit(commit = true) { remove(key) }
		}
	}

	@Test fun confirmedManualCorruptDeleteClearsBothSourcesAndKeepsSibling() = runBlocking {
		val folder = File(root, "downloads/ExHentai (OTHER)/Legacy Gallery")
		check(folder.mkdirs())
		val bad = File(folder, "Chapter.cbz").apply { writeText("not a ZIP") }
		val sibling = File(folder, "Chapter_1234abcd.cbz").apply { writeText("another CBZ") }
		val main = gallery(990083, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val gekko = gallery(990084, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		for (manga in listOf(main, gekko)) data.storeManga(manga, replaceExisting = true, detailsFetched = true)
		presence.run(full = true)
		val ids = listOf(main.id, gekko.id)
		assertEquals(ids.toSet(), classifier.getDownloadedIds(space, ids))
		assertEquals(setOf(bad, sibling), deletion.listExhentaiPhysicalDeletionTargets(main, space).toSet())
		assertNull(resolver.findSavedManga(main, space, forExhentaiDetails = true))
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(main, space, bad))
		assertTrue(bad.exists()) // preview alone/cancellation never removes a file
		assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(main, space, approval))
		assertFalse(bad.exists())
		assertTrue(folder.isDirectory)
		assertEquals("another CBZ", sibling.readText())
		assertEquals(ids.toSet(), classifier.getDownloadedIds(space, ids))
		val last = requireNotNull(deletion.previewExhentaiPhysicalDeletion(gekko, space, sibling))
		assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(gekko, space, last))
		assertFalse(sibling.exists())
		assertTrue(folder.isDirectory)
		assertTrue(classifier.getDownloadedIds(space, ids).isEmpty())
		for (manga in listOf(main, gekko)) {
			assertNotNull(database.getMangaDao().find(manga.id))
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manga.id))
		}
	}

	@Test fun manualDeleteRejectsWrongTitleOtherSourceAndPrivatePath() = runBlocking {
		val main = gallery(990085, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val unrelated = gallery(990086, "TSUKI:OTHER:other:EXHENTAI")
		val other = archive("Other Gallery", "Chapter.cbz")
		val privateFile = archive("Legacy Gallery", "Chapter.cbz", private = true)
		val file = archive("Legacy Gallery", "Chapter.cbz")
		val space = FavouriteSpace.NORMAL
		assertNull(deletion.previewExhentaiPhysicalDeletion(main, space, other))
		assertNull(deletion.previewExhentaiPhysicalDeletion(main, space, privateFile))
		assertNull(deletion.previewExhentaiPhysicalDeletion(unrelated, space, file))
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(main, space, file))
		val stamp = file.lastModified()
		val bytes = file.readBytes()
		file.writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
		assertTrue(file.setLastModified(stamp))
		assertTrue(runCatching { deletion.deleteConfirmedExhentaiPhysicalFile(main, space, approval) }.isFailure)
		assertTrue(file.exists())
		assertTrue(other.exists())
		assertTrue(privateFile.exists())
	}

	@Test fun confirmedLegacyDeleteRemovesExactlyTheSharedFileAndLeavesNoFakeOwnership() = runBlocking {
		prefs.edit(commit = true) {
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path)
		}
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990031, GEKKO_SOURCE)
		val local = resolver.findSavedManga(manga, FavouriteSpace.NORMAL, forExhentaiDetails = true)
		assertNotNull(local)
		val approval = deletion.previewExhentaiPhysicalDeletion(manga, FavouriteSpace.NORMAL, local!!)
		assertNotNull(approval)
		assertTrue(approval!!.sharedWithOtherSpace)
		// Old ExHentai task rows may point at the gallery directory, not Chapter.cbz.
		// Both spaces must lose their stale downloaded badge after physical deletion.
		data.storeManga(manga, replaceExisting = true, detailsFetched = true)
		for (space in FavouriteSpace.entries) {
			database.getFavouriteDownloadIndexDao().upsert(
				FavouriteDownloadIndexEntity(manga.id, space.dbValue, chapter.parentFile.canonicalPath),
			)
		}
		assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(manga, FavouriteSpace.NORMAL, local, approval))
		assertFalse(chapter.exists())
		assertNull(resolver.findSavedManga(manga, FavouriteSpace.NORMAL, forExhentaiDetails = true))
		assertNull(resolver.findSavedManga(manga, FavouriteSpace.PRIVATE, forExhentaiDetails = true))
		for (space in FavouriteSpace.entries) {
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manga.id))
		}
	}

	@Test fun approvedDeleteRejectsChangedFileWithoutDeletingIt() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990032, GEKKO_SOURCE)
		val local = resolver.findSavedManga(manga, FavouriteSpace.NORMAL, forExhentaiDetails = true)!!
		val approval = deletion.previewExhentaiPhysicalDeletion(manga, FavouriteSpace.NORMAL, local)!!
		chapter.appendBytes(byteArrayOf(9))
		var rejected = false
		try {
			deletion.deleteConfirmedExhentaiPhysicalFile(manga, FavouriteSpace.NORMAL, local, approval)
		} catch (e: UnverifiedDownloadRemovalException) {
			rejected = true
		}
		assertTrue(rejected)
		assertTrue(chapter.exists())
	}

	@Test fun approvedDeleteRejectsDestinationChange() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990033, GEKKO_SOURCE)
		val local = resolver.findSavedManga(manga, FavouriteSpace.NORMAL, forExhentaiDetails = true)!!
		val approval = deletion.previewExhentaiPhysicalDeletion(manga, FavouriteSpace.NORMAL, local)!!
		destinationStore.setRoot(FavouriteSpace.NORMAL, File(root, "moved"))
		var rejected = false
		try {
			deletion.deleteConfirmedExhentaiPhysicalFile(manga, FavouriteSpace.NORMAL, local, approval)
		} catch (e: UnverifiedDownloadRemovalException) {
			rejected = true
		}
		assertTrue(rejected)
		assertTrue(chapter.exists())
	}

	@Test fun confirmedDeleteRejectsDestinationChangeAtRemovalBoundary() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990088, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val space = FavouriteSpace.NORMAL
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		val bytes = chapter.readBytes()
		var reached = false
		val result = runCatching {
			deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) {
				destinationStore.setRoot(space, File(root, "new-active"))
				reached = true
			}
		}
		assertTrue("Removal boundary was not reached", reached)
		assertTrue("Old destination file must not be removed", chapter.isFile)
		assertArrayEquals(bytes, chapter.readBytes())
		assertTrue("Changed destination must be rejected", result.exceptionOrNull() is UnverifiedDownloadRemovalException)
	}

	@Test fun destinationPublicationSerializesWithRemovalCriticalSection() = runBlocking {
		val space = FavouriteSpace.NORMAL
		val oldRoot = destinationStore.effectiveRoot(space)?.canonicalFile
		val nextRoot = File(root, "serialized-active")
		val entered = java.util.concurrent.CountDownLatch(1)
		val completed = java.util.concurrent.CountDownLatch(1)
		val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
		val setter = Thread {
			try {
				entered.countDown()
				destinationStore.setRoot(space, nextRoot)
			} catch (error: Throwable) {
				failure.set(error)
			} finally {
				completed.countDown()
			}
		}
		try {
			destinationStore.withDestinationMutationLock {
				setter.start()
				assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
				assertFalse("Root publication must wait for the removal lock",
					completed.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
				assertEquals(oldRoot, destinationStore.effectiveRoot(space)?.canonicalFile)
			}
			assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
			assertNull(failure.get())
			assertEquals(nextRoot.canonicalFile, destinationStore.effectiveRoot(space)?.canonicalFile)
		} finally {
			setter.join(5000)
		}
	}

	@Test fun confirmedDeleteSerializesWithInternalDestinationCopyRemoval() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990091, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val local = requireNotNull(resolver.findSavedManga(manga, space, forExhentaiDetails = true))
		assertEquals(chapter.canonicalFile, local.file.canonicalFile)
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		val bytes = chapter.readBytes()
		val entered = java.util.concurrent.CountDownLatch(1)
		val completed = java.util.concurrent.CountDownLatch(1)
		val competingResult = java.util.concurrent.atomic.AtomicReference<Result<Boolean>?>()
		val competitor = Thread {
			try {
				competingResult.set(runCatching {
					runBlocking {
						// Enter the real repository method before signaling; at this HEAD its
						// first suspension is acquisition of the held removal mutex.
						val removal = async(start = CoroutineStart.UNDISPATCHED) {
							withTimeout(10000) {
								// Actual repository removal used by the source-owned delete path.
								localRepository.deleteExhentaiDestinationCopy(local)
							}
						}
						entered.countDown()
						removal.await()
					}
				})
			} finally {
				completed.countDown()
			}
		}
		try {
			assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) {
				competitor.start()
				assertTrue("Internal removal was not started", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
				// Give the competing entry point an opportunity to finish. The assertion is
				// about actual file state, not a timing-only assertion about a mutex.
				completed.await(2, java.util.concurrent.TimeUnit.SECONDS)
				assertTrue("Internal removal must not remove the confirmed target before commit", chapter.isFile)
				assertArrayEquals(bytes, chapter.readBytes())
			})
			assertFalse(chapter.exists())
		} finally {
			competitor.join(12000)
			assertFalse("Competing internal removal did not terminate", competitor.isAlive)
		}
		assertNotNull("Competing internal removal did not report an outcome", competingResult.get())
		assertFalse("Competing removal must not report a second successful deletion",
			competingResult.get()!!.getOrNull() == true)
		val error = competingResult.get()!!.exceptionOrNull()
		assertTrue("Unexpected competing removal failure: $error",
			error == null || error is UnverifiedDownloadRemovalException)
	}

	@Test fun confirmedDeleteRejectsWhenInternalDestinationRemovalWins() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990092, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val local = requireNotNull(resolver.findSavedManga(manga, space, forExhentaiDetails = true))
		assertEquals(chapter.canonicalFile, local.file.canonicalFile)
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		val sibling = File(chapter.parentFile, "Unrelated.cbz").apply { writeText("keep") }
		withTimeout(10000) {
			// Queue both actual removal entry points in order while holding the common mutex.
			val (first, second) = localRepository.exhentaiRemovalMutex.withLock {
				val removal = async(start = CoroutineStart.UNDISPATCHED) {
					localRepository.deleteExhentaiDestinationCopy(local)
				}
				val confirmed = async(start = CoroutineStart.UNDISPATCHED) {
					runCatching { deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) }
				}
				assertFalse(removal.isCompleted)
				assertFalse(confirmed.isCompleted)
				assertTrue(chapter.isFile)
				removal to confirmed
			}
			assertTrue("Repository removal must win", first.await())
			assertTrue("Stale approval must reject", second.await().exceptionOrNull() is UnverifiedDownloadRemovalException)
		}
		assertFalse(chapter.exists())
		assertEquals("keep", sibling.readText())
	}

	@Test fun confirmedDeleteSerializesWithGenericLocalRemoval() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990093, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val local = requireNotNull(resolver.findSavedManga(manga, space, forExhentaiDetails = true))
		assertEquals(chapter.canonicalFile, local.file.canonicalFile)
		assertEquals(chapter.canonicalFile, File(requireNotNull(android.net.Uri.parse(local.manga.url).path)).canonicalFile)
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		val bytes = chapter.readBytes()
		val entered = java.util.concurrent.CountDownLatch(1)
		val completed = java.util.concurrent.CountDownLatch(1)
		val competingResult = java.util.concurrent.atomic.AtomicReference<Result<Boolean>?>()
		val competitor = Thread {
			try {
				competingResult.set(runCatching {
					runBlocking {
						// Enter the actual generic removal before signaling. It may suspend
						// for IO, so use the real file state and bounded completion window.
						val removal = async(start = CoroutineStart.UNDISPATCHED) {
							withTimeout(10000) {
								// Actual generic Local Library physical deletion entry point.
								localRepository.delete(local.manga)
							}
						}
						entered.countDown()
						removal.await()
					}
				})
			} finally {
				completed.countDown()
			}
		}
		try {
			assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) {
				competitor.start()
				assertTrue("Generic removal was not started", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
				// Give the competing entry point an opportunity to finish. The assertion is
				// about actual file state, not a timing-only assertion about a mutex.
				completed.await(2, java.util.concurrent.TimeUnit.SECONDS)
				assertTrue("Generic local removal must not consume the confirmed target before commit", chapter.isFile)
				assertArrayEquals(bytes, chapter.readBytes())
			})
			assertFalse(chapter.exists())
		} finally {
			competitor.join(12000)
			assertFalse("Competing internal removal did not terminate", competitor.isAlive)
		}
		assertNotNull("Competing internal removal did not report an outcome", competingResult.get())
		assertFalse("Competing removal must not report a second successful deletion",
			competingResult.get()!!.getOrNull() == true)
		val error = competingResult.get()!!.exceptionOrNull()
		assertTrue("Unexpected competing removal failure: $error",
			error == null || error is UnverifiedDownloadRemovalException)
	}

	@Test fun confirmedDeleteNeverRemovesReplacementDirectoryAtRemovalBoundary() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990094, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		val original = File(chapter.parentFile, "Original.cbz")
		val bytes = chapter.readBytes()
		var reached = false
		val outcome = runCatching {
			deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) {
				check(chapter.renameTo(original))
				check(chapter.mkdir())
				reached = true
			}
		}
		assertTrue("Removal boundary was not reached", reached)
		assertArrayEquals(bytes, original.readBytes())
		assertTrue("A replacement directory must never be removed by file-only delete", chapter.isDirectory)
		assertTrue("Directory substitution must be rejected", outcome.exceptionOrNull() is UnverifiedDownloadRemovalException)
	}

	@Test fun confirmedSharedDeletePreservesMainAndGekkoPersonalState() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		ZipOutputStream(chapter.outputStream()).use { zip ->
			for (page in 1..8) {
				zip.putNextEntry(ZipEntry("$page.webp"))
				zip.write(byteArrayOf(1, 2, 3))
				zip.closeEntry()
			}
		}
		val main = gallery(990089, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val gekko = gallery(990090, GEKKO_SOURCE)
		val mangaList = listOf(main, gekko)
		val ids = mangaList.map { it.id }
		val space = FavouriteSpace.NORMAL
		val categoryDao = database.getFavouriteCategoriesDao()
		val categoryIds = ArrayList<Long>()
		try {
			for ((index, manga) in mangaList.withIndex()) {
				data.storeManga(manga, replaceExisting = true, detailsFetched = true)
				val onlineChapter = checkNotNull(manga.chapters).single()
				val page = if (index == 0) 7 else 2
				val percent = if (index == 0) 1f else 2f / 7f
				val history = HistoryEntity(
					mangaId = manga.id, createdAt = 100L + index, updatedAt = 200L + index,
					chapterId = onlineChapter.id, page = page, scroll = index.toFloat(),
					percent = percent, deletedAt = 0L, chaptersCount = 1, space = space.dbValue,
				)
				database.getHistoryDao().upsert(history)
				database.getChapterProgressDao().upsert(ChapterProgressEntity(
					mangaId = manga.id, source = manga.source.name, url = onlineChapter.url,
					space = space.dbValue, page = page, scroll = index, totalPages = 8,
				))
				database.getBookmarksDao().insert(BookmarkEntity(
					mangaId = manga.id, pageId = manga.id + 1000000L,
					chapterId = onlineChapter.id, page = page, scroll = index,
					imageUrl = manga.url, createdAt = 300L + index, percent = percent,
					note = "source-specific bookmark $index",
				))
				settings.setChapterReadOverride(manga.id, onlineChapter.id, index == 0)
				val categoryId = categoryDao.insert(FavouriteCategoryEntity(
					categoryId = 0, createdAt = 400L + index, sortKey = index,
					title = "Shared-delete regression $index", order = ListSortOrder.NEWEST.name,
					track = false, downloadNewChapters = false, isVisibleInLibrary = true,
					deletedAt = 0L, space = space.dbValue,
				))
				categoryIds.add(categoryId)
				database.getFavouritesDao().insert(FavouriteEntity(
					mangaId = manga.id, categoryId = categoryId, sortKey = index,
					isPinned = index == 0, createdAt = 500L + index, deletedAt = 0L,
				))
			}
			val historyBefore = ids.map { requireNotNull(database.getHistoryDao().find(it)) }
			val progressBefore = ids.map { id ->
				database.getChapterProgressDao().findAll(listOf(id), space.dbValue).single()
			}
			val bookmarksBefore = ids.map { database.getBookmarksDao().findAll(it).single() }
			val overridesBefore = ids.map { settings.getChapterReadOverrides(it) }
			val favouritesBefore = ids.map { database.getFavouritesDao().findAllRaw(it).single() }
			val categoriesBefore = categoryIds.map { requireNotNull(categoryDao.findOrNull(it.toInt())) }
			val metadataBefore = ids.map { requireNotNull(database.getMangaDao().find(it)).manga }
			assertEquals(listOf(7, 2), historyBefore.map { it.page })
			assertEquals(listOf(1f, 2f / 7f), historyBefore.map { it.percent })
			assertEquals(listOf(true, false), overridesBefore.map { it.values.single() })
			presence.run(full = true)
			assertEquals(ids.toSet(), classifier.getDownloadedIds(space, ids))
			val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(main, space, chapter))
			assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(main, space, approval))
			assertFalse(chapter.exists())
			assertTrue(classifier.getDownloadedIds(space, ids).isEmpty())
			assertEquals(historyBefore, ids.map { database.getHistoryDao().find(it) })
			assertEquals(progressBefore, ids.map { id ->
				database.getChapterProgressDao().findAll(listOf(id), space.dbValue).single()
			})
			assertEquals(bookmarksBefore, ids.map { database.getBookmarksDao().findAll(it).single() })
			assertEquals(overridesBefore, ids.map { settings.getChapterReadOverrides(it) })
			assertEquals(favouritesBefore, ids.map { database.getFavouritesDao().findAllRaw(it).single() })
			assertEquals(categoriesBefore, categoryIds.map { categoryDao.findOrNull(it.toInt()) })
			assertEquals(metadataBefore, ids.map { database.getMangaDao().find(it)?.manga })
		} finally {
			database.getMangaDao().delete(ids.mapNotNull { database.getMangaDao().find(it)?.manga })
			for (id in categoryIds) categoryDao.delete(id)
		}
	}

	@Test fun permissiveDetailsReadIsLimitedToMainAndGekkoSources() = runBlocking {
		prefs.edit(commit = true) {
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path)
		}
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val anotherTsuki = gallery(990025, "TSUKI:OTHER:other:EXHENTAI")
		assertNull(resolver.findSavedManga(anotherTsuki, FavouriteSpace.NORMAL, forExhentaiDetails = true))
		assertTrue(chapter.exists())
	}

	private fun sqlDownloadedCondition(id: Long): Boolean {
		val clause = classifier.getDownloadedCondition(FavouriteSpace.NORMAL, "m.manga_id")
		return database.openHelper.readableDatabase
			.query("SELECT $clause FROM manga m WHERE m.manga_id = $id")
			.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }
	}

	private fun gallery(id: Long, sourceName: String): Manga {
		val source = MangaSource(sourceName)
		val url = "https://exhentai.org/g/$id/token/"
		return SampleData.mangaDetails.copy(
			id = id, title = "Legacy Gallery", source = source,
			url = url, publicUrl = url,
			chapters = listOf(SampleData.chapter.copy(
				id = id + 800000, title = "Chapter", number = 1f, volume = 0,
				source = source, url = url, branch = null, scanlator = null,
			)),
		)
	}

	private fun downloaded(remote: Manga, local: org.koitharu.kotatsu.local.domain.model.LocalManga) =
		MangaDetails(remote).copy(localManga = local)
			.mapChapters(0L, 0, null, emptyList(), false, false).single().isDownloaded

	private fun archive(
		folder: String, name: String, comment: String? = null, private: Boolean = false,
		index: String? = null, comicInfo: String? = null, destination: File? = null,
		pageBytes: ByteArray = byteArrayOf(1, 2, 3),
	): File {
		val base = destination ?: if (private) File(root, "private") else root
		val dir = File(base, "downloads/ExHentai (OTHER)/$folder")
		check(dir.mkdirs())
		return File(dir, name).apply {
			ZipOutputStream(outputStream()).use { zip ->
				if (comment != null) zip.setComment(comment)
				zip.putNextEntry(ZipEntry("1.webp"))
				zip.write(pageBytes)
				zip.closeEntry()
				if (index != null) {
					zip.putNextEntry(ZipEntry("index.json"))
					zip.write(index.toByteArray())
					zip.closeEntry()
				}
				if (comicInfo != null) {
					zip.putNextEntry(ZipEntry("ComicInfo.xml"))
					zip.write(comicInfo.toByteArray())
					zip.closeEntry()
				}
			}
		}
	}

	companion object {
		private const val GEKKO_SOURCE = "TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"
	}
}
