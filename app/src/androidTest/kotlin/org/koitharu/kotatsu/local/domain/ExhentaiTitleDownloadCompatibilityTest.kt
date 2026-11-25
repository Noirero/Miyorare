package org.koitharu.kotatsu.local.domain

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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
import org.koitharu.kotatsu.details.data.ChapterProgressRepository
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
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalRoutingMangaRepository
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.library.SmartLocalLibrary
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.DownloadedContentClassifier
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceDiscovery
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceLifecycleObserver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.registerCommittedExhentaiArtifact
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.util.zip.ZipFile
import org.koitharu.kotatsu.tsuki.model.TsukiCompatibilityStatus
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiSourceIdentity
import javax.inject.Inject

/** Real Android Details lookup using flat CBZs from both ExHentai download paths. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExhentaiTitleDownloadCompatibilityTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var resolver: DownloadedMangaResolver
	@Inject lateinit var detailsLoader: DetailsLoadUseCase
	@Inject lateinit var chapterProgress: ChapterProgressRepository
	@Inject lateinit var destinationStore: DownloadDestinationStore
	@Inject lateinit var classifier: DownloadedContentClassifier
	@Inject lateinit var presence: ExhentaiPresenceDiscovery
	@Inject lateinit var localRepository: LocalMangaRepository
	@Inject lateinit var localRouting: LocalRoutingMangaRepository
	@Inject lateinit var smartLocal: SmartLocalLibrary
	@Inject lateinit var database: MangaDatabase
	@Inject lateinit var deletion: DeleteLocalMangaUseCase
	@Inject lateinit var data: MangaDataRepository
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var mangaLock: MangaLock
	@Inject @LocalStorageChanges lateinit var storageChanges: MutableSharedFlow<LocalManga?>

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

	@Test fun confirmedDeleteSerializesWithLegacyLocalChapterRemoval() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val parent = requireNotNull(chapter.parentFile)
		val sibling = chapter.copyTo(File(parent, "Keep.cbz"))
		val local = LocalMangaParser(parent).getManga(withDetails = true).manga
		val selected = requireNotNull(local.chapters).single {
			android.net.Uri.parse(it.url).fragment == chapter.name
		}
		assertEquals(2, local.chapters!!.size)
		verifyConfirmedRemovalExcludesChapterMutation(chapter, sibling, gallery(990107, GEKKO_SOURCE)) {
			// Actual LocalChaptersRemoveService/DeleteReadChaptersUseCase mutation owner.
			localRepository.deleteChapters(local, setOf(selected.id))
		}
	}

	@Test fun confirmedDeleteSerializesWithSmartLocalChapterRemoval() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val sibling = chapter.copyTo(File(chapter.parentFile, "Keep.cbz"))
		val uri = android.net.Uri.fromFile(requireNotNull(chapter.parentFile))
		val fixtureIds = ArrayList<Long>()
		try {
			// Index the real shared download folder as a selected Smart Local root.
			smartLocal.addRoot(uri)
			val books = smartLocal.state.value.books.filter { it.rootUri == uri.toString() }
			fixtureIds.addAll(books.map { it.id })
			val book = books.single { item -> item.chapters.any { it.node.key == chapter.canonicalPath } }
			val selected = book.chapters.single { it.node.key == chapter.canonicalPath }
			verifyConfirmedRemovalExcludesChapterMutation(chapter, sibling, gallery(990108, GEKKO_SOURCE)) {
				// Real routed Smart Local operation, not a manual filesystem delete.
				localRouting.deleteIndexedChapters(book.id, setOf(selected.id))
			}
		} finally {
			smartLocal.removeRoot(uri.toString())
			database.getMangaDao().delete(fixtureIds.mapNotNull { database.getMangaDao().find(it)?.manga })
		}
	}

	@Test fun confirmedDeleteSerializesWithSmartLocalWholeBookRemoval() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val sibling = chapter.copyTo(File(chapter.parentFile, "Keep.cbz"))
		val uri = android.net.Uri.fromFile(requireNotNull(chapter.parentFile))
		val fixtureIds = ArrayList<Long>()
		try {
			smartLocal.addRoot(uri)
			val books = smartLocal.state.value.books.filter { it.rootUri == uri.toString() }
			fixtureIds.addAll(books.map { it.id })
			val book = books.single { item -> item.chapters.any { it.node.key == chapter.canonicalPath } }
			assertEquals(chapter.canonicalPath, book.node.key)
			verifyConfirmedRemovalExcludesChapterMutation(chapter, sibling, gallery(990111, GEKKO_SOURCE)) {
				localRouting.deleteIndexedBook(book.id)
			}
		} finally {
			smartLocal.removeRoot(uri.toString())
			database.getMangaDao().delete(fixtureIds.mapNotNull { database.getMangaDao().find(it)?.manga })
		}
	}

	private suspend fun verifyConfirmedRemovalExcludesChapterMutation(
		chapter: File,
		sibling: File,
		manga: Manga,
		mutate: suspend () -> Unit,
	) {
		val space = FavouriteSpace.NORMAL
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		val bytes = chapter.readBytes()
		val siblingBytes = sibling.readBytes()
		val entered = java.util.concurrent.CountDownLatch(1)
		val completed = java.util.concurrent.CountDownLatch(1)
		val result = java.util.concurrent.atomic.AtomicReference<Result<Unit>?>()
		val competitor = Thread {
			try {
				result.set(runCatching {
					runBlocking {
						val mutation = async(start = CoroutineStart.UNDISPATCHED) {
							withTimeout(10000) { mutate() }
						}
						entered.countDown()
						mutation.await()
					}
				})
			} finally {
				completed.countDown()
			}
		}
		try {
			assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) {
				competitor.start()
				assertTrue("Local chapter mutation was not started", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
				completed.await(2, java.util.concurrent.TimeUnit.SECONDS)
				assertTrue("Local chapter mutation must not consume the confirmed CBZ before commit", chapter.isFile)
				assertArrayEquals(bytes, chapter.readBytes())
				assertEquals("Competing removal must wait for the confirmed commit", 1L, completed.count)
			})
			assertFalse(chapter.exists())
		} finally {
			competitor.join(12000)
			assertFalse("Competing chapter mutation did not terminate", competitor.isAlive)
		}
		assertNotNull("Competing chapter mutation did not report an outcome", result.get())
		assertArrayEquals(siblingBytes, sibling.readBytes())
		assertTrue(chapter.parentFile!!.isDirectory)
	}

	@Test fun confirmedDeleteReconcilesCancelledRemovalAndRescan() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val bytes = chapter.readBytes()
		val main = gallery(990109, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val gekko = gallery(990110, GEKKO_SOURCE)
		val ids = listOf(main.id, gekko.id)
		val space = FavouriteSpace.NORMAL
		val dao = database.getFavouriteDownloadIndexDao()
		try {
			// Both sources and spaces intentionally share this synthetic destination.
			prefs.edit(commit = true) {
				putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path)
			}
			for (manga in listOf(main, gekko)) {
				data.storeManga(manga, replaceExisting = true, detailsFetched = true)
				for (targetSpace in FavouriteSpace.entries) {
					dao.upsert(FavouriteDownloadIndexEntity(manga.id, targetSpace.dbValue, chapter.canonicalPath))
				}
			}
			presence.run(full = true)
			for (targetSpace in FavouriteSpace.entries) {
				assertEquals(ids.toSet(), classifier.getDownloadedIds(targetSpace, ids))
			}
			val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(main, space, chapter))
			val reached = java.util.concurrent.atomic.AtomicBoolean(false)
			val worker = launch(Dispatchers.Default) {
				val job = requireNotNull(currentCoroutineContext()[Job])
				deletion.deleteConfirmedExhentaiPhysicalFile(main, space, approval) {
					reached.set(true)
					job.cancel()
				}
			}
			withTimeout(15000) { worker.join() }
			assertTrue("Cancellation must reach the actual removal boundary", reached.get())
			assertTrue(worker.isCancelled)
			// The current non-suspending unlink commits even if cancellation is requested
			// at this seam. NonCancellable cleanup must reconcile both sources and spaces.
			assertFalse("The in-flight removal must complete its physical commit", chapter.exists())
			for (targetSpace in FavouriteSpace.entries) {
				assertTrue("Cancelled removal left a stale Downloaded badge",
					classifier.getDownloadedIds(targetSpace, ids).isEmpty())
				for (id in ids) assertNull(dao.findEntry(targetSpace.dbValue, id))
			}
			presence.run(full = true)
			for (targetSpace in FavouriteSpace.entries) {
				assertTrue(classifier.getDownloadedIds(targetSpace, ids).isEmpty())
			}
			// An independent new manual CBZ at the same path must be discoverable,
			// without resurrecting the removed ownership records.
			val replacement = bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
			chapter.writeBytes(replacement)
			presence.run(full = true)
			assertArrayEquals(replacement, chapter.readBytes())
			for (targetSpace in FavouriteSpace.entries) {
				assertEquals(ids.toSet(), classifier.getDownloadedIds(targetSpace, ids))
				for (id in ids) assertNull(dao.findEntry(targetSpace.dbValue, id))
			}
		} finally {
			for (targetSpace in FavouriteSpace.entries) {
				for (id in ids) dao.delete(targetSpace.dbValue, id)
			}
			database.getMangaDao().delete(ids.mapNotNull { database.getMangaDao().find(it)?.manga })
		}
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

	@Test fun confirmedDeleteRejectsParentDirectoryReplacementAtRemovalBoundary() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val replacement = archive("Replacement Gallery", "Chapter.cbz", pageBytes = byteArrayOf(4, 5, 6))
		val parent = requireNotNull(chapter.parentFile)
		val replacementParent = requireNotNull(replacement.parentFile)
		val movedParent = File(parent.parentFile, "Original Gallery")
		val originalBytes = chapter.readBytes()
		val replacementBytes = replacement.readBytes()
		assertFalse(originalBytes.contentEquals(replacementBytes))
		val sibling = File(replacementParent, "Keep.cbz").apply { writeText("keep replacement sibling") }
		val manga = gallery(990104, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		var reached = false
		val outcome = runCatching {
			deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) {
				check(parent.renameTo(movedParent))
				check(replacementParent.renameTo(parent))
				reached = true
			}
		}
		assertTrue("Parent replacement boundary was not reached: ${outcome.exceptionOrNull()}", reached)
		assertArrayEquals(originalBytes, File(movedParent, chapter.name).readBytes())
		assertTrue("A CBZ in a substituted parent must not be removed", chapter.isFile)
		assertArrayEquals(replacementBytes, chapter.readBytes())
		assertEquals("keep replacement sibling", File(parent, sibling.name).readText())
		assertTrue("Parent namespace substitution must be rejected", outcome.exceptionOrNull() is UnverifiedDownloadRemovalException)
	}

	@Test fun confirmedDeleteRejectsInRootParentSymlinkAtRemovalBoundary() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val replacement = archive("Redirect Gallery", "Chapter.cbz", pageBytes = byteArrayOf(7, 8, 9))
		val parent = requireNotNull(chapter.parentFile)
		val redirectParent = requireNotNull(replacement.parentFile)
		val movedParent = File(parent.parentFile, "Original Gallery")
		val originalBytes = chapter.readBytes()
		val replacementBytes = replacement.readBytes()
		assertFalse(originalBytes.contentEquals(replacementBytes))
		val manga = gallery(990105, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		var reached = false
		try {
			val outcome = runCatching {
				deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) {
					check(parent.renameTo(movedParent))
					android.system.Os.symlink(redirectParent.path, parent.path)
					check(java.nio.file.Files.isSymbolicLink(parent.toPath()))
					reached = true
				}
			}
			assertTrue("Symlink fixture did not reach removal boundary: ${outcome.exceptionOrNull()}", reached)
			assertArrayEquals(originalBytes, File(movedParent, chapter.name).readBytes())
			assertTrue("A CBZ reached through a redirected parent must not be removed", replacement.isFile)
			assertArrayEquals(replacementBytes, replacement.readBytes())
			assertTrue("Parent symlink substitution must be rejected", outcome.exceptionOrNull() is UnverifiedDownloadRemovalException)
		} finally {
			// Remove only the fixture link itself, before recursive fixture teardown.
			if (java.nio.file.Files.isSymbolicLink(parent.toPath())) {
				java.nio.file.Files.delete(parent.toPath())
			}
		}
	}

	@Test fun confirmedDeleteRejectsActiveRootReplacementWithSameParentAtRemovalBoundary() = runBlocking {
		val destination = File(root, "namespace-root").apply { check(mkdir()) }
		val movedRoot = File(root, "namespace-original-root")
		val space = FavouriteSpace.NORMAL
		destinationStore.setRoot(space, destination)
		val chapter = archive("Legacy Gallery", "Chapter.cbz", destination = destination)
		val parent = requireNotNull(chapter.parentFile)
		val sourceFolder = requireNotNull(parent.parentFile)
		val relativeParent = parent.relativeTo(destination)
		val originalBytes = chapter.readBytes()
		val sibling = File(parent, "Keep.cbz").apply { writeText("keep sibling") }
		val manga = gallery(990106, GEKKO_SOURCE)
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		val parentBefore = android.system.Os.lstat(parent.path)
		var reached = false
		val outcome = runCatching {
			deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) {
				check(destination.renameTo(movedRoot))
				check(sourceFolder.mkdirs())
				// Restore the very same title directory under a different root namespace.
				check(File(movedRoot, relativeParent.path).renameTo(parent))
				val parentAfter = android.system.Os.lstat(parent.path)
				check(parentBefore.st_dev == parentAfter.st_dev && parentBefore.st_ino == parentAfter.st_ino)
				reached = true
			}
		}
		assertTrue("Active root replacement boundary was not reached: ${outcome.exceptionOrNull()}", reached)
		assertTrue("The approved file must not be removed under a substituted active root", chapter.isFile)
		assertArrayEquals(originalBytes, chapter.readBytes())
		assertEquals("keep sibling", sibling.readText())
		assertTrue(movedRoot.isDirectory)
		assertTrue("Active root namespace substitution must be rejected", outcome.exceptionOrNull() is UnverifiedDownloadRemovalException)
	}

	@Test fun confirmedDeleteSupportsAppSpecificExternalDestination() = runBlocking {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val external = requireNotNull(context.getExternalFilesDir(null))
		val fixture = File(external, "exhentai-file-only-" + UUID.randomUUID())
		check(fixture.mkdir())
		try {
			val space = FavouriteSpace.NORMAL
			destinationStore.setRoot(space, fixture)
			val chapter = archive("Legacy Gallery", "Chapter.cbz", destination = fixture)
			val manga = gallery(990095, GEKKO_SOURCE)
			val sibling = File(chapter.parentFile, "Keep.cbz").apply { writeText("keep") }
			val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
			assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval))
			assertFalse(chapter.exists())
			assertTrue(chapter.parentFile!!.isDirectory)
			assertEquals("keep", sibling.readText())
		} finally {
			check(fixture.deleteRecursively())
		}
	}

	@Test fun successiveConfirmedDeletesOfDistinctArchivesPreserveSiblingAndFolder() = runBlocking {
		val first = archive("Legacy Gallery", "Chapter.cbz")
		val second = archive("Other Gallery", "Chapter.cbz")
		val firstManga = gallery(990096, GEKKO_SOURCE)
		val secondManga = gallery(990097, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME).copy(title = "Other Gallery")
		val space = FavouriteSpace.NORMAL
		val keep = File(first.parentFile, "Keep.cbz").apply { writeText("keep") }
		val a = requireNotNull(deletion.previewExhentaiPhysicalDeletion(firstManga, space, first))
		val b = requireNotNull(deletion.previewExhentaiPhysicalDeletion(secondManga, space, second))
		withTimeout(10000) {
			val firstDelete = async(Dispatchers.IO) { deletion.deleteConfirmedExhentaiPhysicalFile(firstManga, space, a) }
			val secondDelete = async(Dispatchers.IO) { deletion.deleteConfirmedExhentaiPhysicalFile(secondManga, space, b) }
			assertTrue(firstDelete.await())
			assertTrue(secondDelete.await())
		}
		assertFalse(first.exists())
		assertFalse(second.exists())
		assertTrue(first.parentFile!!.isDirectory)
		assertTrue(second.parentFile!!.isDirectory)
		assertEquals("keep", keep.readText())
	}

	@Test fun downloadMainStagingSurvivesConfirmedDeleteInSharedTitleFolder() = runBlocking {
		verifyStagedDownloadSurvivesConfirmedDelete(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, 990098L)
	}

	@Test fun downloadGekkoGenericStagingSurvivesConfirmedDeleteInSharedTitleFolder() = runBlocking {
		verifyStagedDownloadSurvivesConfirmedDelete(GEKKO_SOURCE, 990099L)
	}

	private suspend fun verifyStagedDownloadSurvivesConfirmedDelete(source: String, id: Long) {
		val victim = archive("Legacy Gallery", "Chapter.cbz")
		val sibling = File(victim.parentFile, "Keep.cbz").apply { writeText("keep") }
		val seed = gallery(id, source)
		// Use real Tsuki metadata objects, not MissingMangaSource when CI lacks the plugin.
		// Both test descriptors intentionally model the recognized shared OTHER folder.
		val identity = requireNotNull(TsukiSourceIdentity.parse(source))
		val descriptor = TsukiSourceDescriptor(identity.sourceName, "ExHentai", "", "HENTAI")
		val liveShape = TsukiMangaSource(
			TsukiPluginDescriptor(
				pluginId = identity.pluginId, displayName = "Synthetic download fixture",
				provider = identity.provider, version = "test", origin = "", sha256 = "",
				fileSize = 0L, lastModified = 0L, requiredApi = null,
				compatibility = TsukiCompatibilityStatus.COMPATIBLE, state = TsukiPluginState.ENABLED,
				sources = listOf(descriptor),
			), descriptor,
		)
		assertEquals(source, liveShape.name)
		val downloader = seed.copy(source = liveShape,
			chapters = requireNotNull(seed.chapters).map { it.copy(source = liveShape) })
		val deleteSource = if (source == GEKKO_SOURCE) EhentaiSourceFamily.OFFICIAL_SOURCE_NAME else GEKKO_SOURCE
		val remote = gallery(id + 1000L, deleteSource)
		val space = FavouriteSpace.NORMAL
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(remote, space, victim))
		val page = File(root, "download-page.png").apply {
			writeBytes(android.util.Base64.decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
				android.util.Base64.DEFAULT,
			))
		}
		val chapter = requireNotNull(downloader.chapters).single()
		val output = if (source == GEKKO_SOURCE) {
			LocalMangaOutput.getOrCreate(root, downloader, DownloadFormat.MULTIPLE_CBZ)
		} else {
			LocalMangaOutput.createExhentaiArchive(root, downloader, DownloadFormat.MULTIPLE_CBZ, true,
				ExhentaiChapterOwnership.Receipt(space, UUID.randomUUID()))
		}
		try {
			assertEquals("Both writers must use the same physical title folder",
				victim.parentFile!!.canonicalFile, output.rootFile.canonicalFile)
			output.addPage(IndexedValue(0, chapter), page, 0, MimeType("image/png"))
			val staging = requireNotNull(output.rootFile.listFiles()).single { it.name.endsWith(".tmp") }
			assertTrue(staging.isFile)
			// Actual output remains open while the other source confirms removal of a sibling.
			assertTrue(deletion.deleteConfirmedExhentaiPhysicalFile(remote, space, approval))
			assertFalse(victim.exists())
			assertTrue(staging.isFile)
			assertEquals("keep", sibling.readText())
			output.addPage(IndexedValue(0, chapter), page, 1, MimeType("image/png"))
			assertTrue(output.flushChapter(chapter))
			output.mergeWithExisting()
			output.finish()
			val committed = requireNotNull(output.rootFile.listFiles()).single {
				it.isFile && it.extension.equals("cbz", true) && it != sibling
			}
			assertFalse("Download must not recreate the deleted sibling", victim.exists())
			ZipFile(committed).use { zip ->
				for (name in listOf("1.png", "2.png")) {
					assertArrayEquals(page.readBytes(), zip.getInputStream(requireNotNull(zip.getEntry(name))).use { it.readBytes() })
				}
			}
			assertEquals("keep", sibling.readText())
		} finally {
			try { output.cleanup() } finally { output.close() }
		}
		assertTrue(victim.parentFile!!.isDirectory)
		assertEquals("keep", sibling.readText())
	}

	@Test fun committedMainRegistrationCannotRestoreOwnershipAfterGekkoRemoval() = runBlocking {
		val main = gallery(990100L, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val gekko = gallery(990101L, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val request = UUID.randomUUID()
		val task = DownloadTask(main.id, false, true, null, root, DownloadFormat.MULTIPLE_CBZ, true, space)
		val committed = archive("Legacy Gallery", "Chapter.cbz",
			comment = ExhentaiChapterOwnership.scopedComment(990100L, ExhentaiChapterOwnership.Receipt(space, request), false))
		val dao = database.getFavouriteDownloadIndexDao()
		data.storeManga(main, replaceExisting = true, detailsFetched = true)
		data.storeManga(gekko, replaceExisting = true, detailsFetched = true)
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(gekko, space, committed))
		var reached = false
		try {
			withTimeout(15000) {
				val scope = this
				var competing: kotlinx.coroutines.Deferred<Boolean>? = null
				// Mirror the worker's manga lock, but use Gekko's different identity for Delete.
				mangaLock.withLock(main) {
					registerCommittedExhentaiArtifact(main, task, committed, request, database, localRepository, storageChanges) {
						reached = true
						val removal = scope.async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
							deletion.deleteConfirmedExhentaiPhysicalFile(gekko, space, approval)
						}
						competing = removal
						// Without a common publication/removal boundary, actual Delete can finish
						// here before the worker writes ownership. A serialized writer may wait.
						withTimeoutOrNull(3000) { removal.await() }
					}
				}
				assertTrue("Registration seam must be reached after real verification", reached)
				assertTrue("Confirmed removal must succeed", requireNotNull(competing).await())
			}
			assertFalse(committed.exists())
			assertNull("Registration must not resurrect ownership of a physically deleted CBZ",
				dao.findEntry(space.dbValue, main.id))
			assertNull(dao.findEntry(space.dbValue, gekko.id))
			assertTrue(classifier.getDownloadedIds(space, listOf(main.id, gekko.id)).isEmpty())
		} finally {
			dao.delete(space.dbValue, main.id)
			dao.delete(space.dbValue, gekko.id)
		}
	}

	@Test fun committedMainRegistrationRejectsWhenGekkoRemovalWins() = runBlocking {
		val main = gallery(990102L, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val gekko = gallery(990103L, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val request = UUID.randomUUID()
		val task = DownloadTask(main.id, false, true, null, root, DownloadFormat.MULTIPLE_CBZ, true, space)
		val committed = archive("Legacy Gallery", "Chapter.cbz",
			comment = ExhentaiChapterOwnership.scopedComment(990102L, ExhentaiChapterOwnership.Receipt(space, request), false))
		val dao = database.getFavouriteDownloadIndexDao()
		data.storeManga(main, replaceExisting = true, detailsFetched = true)
		data.storeManga(gekko, replaceExisting = true, detailsFetched = true)
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(gekko, space, committed))
		val reached = java.util.concurrent.atomic.AtomicBoolean(false)
		try {
			withTimeout(15000) {
				// Preserve the worker's MangaLock -> shared removal mutex order.
				mangaLock.withLock(main) {
					val (removal, registration) = localRepository.exhentaiRemovalMutex.withLock {
						val first = async(start = CoroutineStart.UNDISPATCHED) {
							deletion.deleteConfirmedExhentaiPhysicalFile(gekko, space, approval)
						}
						val second = async(start = CoroutineStart.UNDISPATCHED) {
							runCatching {
								registerCommittedExhentaiArtifact(main, task, committed, request, database, localRepository, storageChanges) {
									reached.set(true)
								}
							}
						}
						assertFalse(first.isCompleted)
						assertFalse(second.isCompleted)
						assertTrue(committed.isFile)
						first to second
					}
					assertTrue("Confirmed removal must win", removal.await())
					val error = registration.await().exceptionOrNull()
					assertTrue("Missing archive must reject registration", error is IllegalStateException)
					assertEquals("Committed ExHentai archive cannot be read", error?.message)
				}
			}
			assertFalse("Verification must reject before ownership registration", reached.get())
			assertFalse(committed.exists())
			assertNull(dao.findEntry(space.dbValue, main.id))
			assertNull(dao.findEntry(space.dbValue, gekko.id))
			assertTrue(classifier.getDownloadedIds(space, listOf(main.id, gekko.id)).isEmpty())
		} finally {
			dao.delete(space.dbValue, main.id)
			dao.delete(space.dbValue, gekko.id)
		}
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

	@Test fun renamedMainCbzKeepsOnlineChapterAndCreatesUnreadLocalChapter() = runBlocking {
		verifyRenamedCbzHasIndependentChapterIdentity(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, 990112L)
	}

	@Test fun renamedGekkoCbzKeepsOnlineChapterAndCreatesUnreadLocalChapter() = runBlocking {
		verifyRenamedCbzHasIndependentChapterIdentity(GEKKO_SOURCE, 990113L)
	}

	private suspend fun verifyRenamedCbzHasIndependentChapterIdentity(source: String, id: Long) {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val originalBytes = chapter.readBytes()
		val online = gallery(id, source)
		val original = requireNotNull(online.chapters).single()
		val space = FavouriteSpace.NORMAL
		val progressDao = database.getChapterProgressDao()
		val historyDao = database.getHistoryDao()
		val bookmarkDao = database.getBookmarksDao()
		try {
			data.storeManga(online, replaceExisting = true, detailsFetched = true)
			val intent = MangaIntent(SavedStateHandle(mapOf(AppRouter.KEY_MANGA to ParcelableManga(online))))
			suspend fun details() = detailsLoader(
				intent = intent, force = false, favouriteSpace = space,
				preferLocalBeforeInitialSnapshot = true,
			).first()
			presence.run(full = true)
			val before = details()
			val initial = before.mapChapters(0L, 0, null, emptyList(), false, false).single()
			assertEquals(original.id, initial.chapter.id)
			assertTrue(initial.isDownloaded)
			assertEquals(chapter.canonicalFile, before.local?.file?.canonicalFile)
			val progress = ChapterProgressEntity(
				mangaId = id, source = initial.personalKey.source, url = initial.personalKey.url,
				space = space.dbValue, page = 7, scroll = 0, totalPages = 8,
			)
			progressDao.upsert(progress)
			historyDao.upsert(HistoryEntity(
				mangaId = id, createdAt = 100L, updatedAt = 200L, chapterId = original.id,
				page = 7, scroll = 0f, percent = 1f, deletedAt = 0L, chaptersCount = 1, space = space.dbValue,
			))
			bookmarkDao.insert(BookmarkEntity(
				mangaId = id, pageId = id + 1000000L, chapterId = original.id, page = 7,
				scroll = 0, imageUrl = online.url, createdAt = 300L, percent = 1f, note = "keep original bookmark",
			))
			settings.setChapterReadOverride(id, original.id, true)
			val bookmark = org.koitharu.kotatsu.bookmarks.domain.Bookmark(
				manga = online, pageId = id + 1000000L, chapterId = original.id, page = 7,
				scroll = 0, imageUrl = online.url, createdAt = java.time.Instant.ofEpochMilli(300L),
				percent = 1f, note = "keep original bookmark",
			)
			val savedHistory = requireNotNull(historyDao.find(id))
			val savedBookmarks = bookmarkDao.findAll(id)
			val savedOverrides = settings.getChapterReadOverrides(id)
			assertEquals(1, savedBookmarks.size)
			val renamed = File(chapter.parentFile, "Chapter_Baru.cbz")
			assertTrue(chapter.renameTo(renamed))
			assertArrayEquals(originalBytes, renamed.readBytes())
			presence.run(full = true)
			assertEquals(setOf(id), classifier.getDownloadedIds(space, listOf(id)))
			val after = details()
			val rows = after.mapChapters(
				original.id, 0, null, listOf(bookmark), false, false,
				readOverrides = savedOverrides,
				progressByKey = progressDao.findAll(listOf(id), space.dbValue).associateBy { it.key() },
				currentPage = savedHistory.page,
			)
			assertEquals("Renaming a CBZ must keep the online row and add a distinct local row", 2, rows.size)
			val oldRow = rows.single { it.chapter.id == original.id }
			val newRow = rows.single { it.chapter.id != original.id }
			assertEquals(original.title, oldRow.chapter.title)
			assertFalse("The original online chapter must no longer be downloaded", oldRow.isDownloaded)
			assertTrue(oldRow.isBookmarked)
			assertFalse(oldRow.isUnread)
			assertEquals(7, oldRow.lastReadPage)
			assertEquals("Chapter_Baru", newRow.chapter.title)
			assertTrue(newRow.isDownloaded)
			assertTrue("Renamed chapter must start unread", newRow.isUnread)
			assertFalse(newRow.isBookmarked)
			assertFalse(newRow.isCurrent)
			assertNull(newRow.lastReadPage)
			assertFalse("Renamed chapter must have a distinct personal key", oldRow.personalKey == newRow.personalKey)
			assertEquals(renamed.canonicalFile, after.local?.file?.canonicalFile)
			assertEquals(savedHistory, historyDao.find(id))
			assertEquals(savedBookmarks, bookmarkDao.findAll(id))
			assertEquals(savedOverrides, settings.getChapterReadOverrides(id))
			assertEquals(listOf(progress), progressDao.findAll(listOf(id), space.dbValue))
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, id))
		} finally {
			database.getMangaDao().delete(listOfNotNull(database.getMangaDao().find(id)?.manga))
		}
	}

	@Test fun readerChoiceKeepsRenamedCbzSeparateFromOnlineChapter() = runBlocking {
		val original = archive("Legacy Gallery", "Chapter.cbz")
		val renamed = original.copyTo(File(original.parentFile, "Chapter_Baru.cbz"))
		for ((index, source) in listOf(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, GEKKO_SOURCE).withIndex()) {
			val manga = gallery(990114L + index, source)
			val remoteChapter = requireNotNull(manga.chapters).single()
			val space = FavouriteSpace.NORMAL
			val choices = resolver.getExhentaiReadChoices(manga, space)
			assertEquals(setOf(original.canonicalPath, renamed.canonicalPath), choices.map { it.path }.toSet())
			assertNull(resolver.findSavedManga(manga, space, forExhentaiDetails = true))
			val selected = requireNotNull(resolver.selectExhentaiReadChoice(manga, space,
				choices.single { it.path == renamed.canonicalPath }))
			val copy = requireNotNull(resolver.resolveExhentaiSessionChoice(manga, space, selected))
			val again = requireNotNull(resolver.resolveExhentaiSessionChoice(manga, space, selected))
			val localChapter = requireNotNull(copy.manga.chapters).single()
			assertEquals(localChapter, again.manga.chapters!!.single())
			assertTrue(localChapter.id != remoteChapter.id)
			val rows = MangaDetails(manga).copy(localManga = copy)
				.mapChapters(remoteChapter.id, 0, null, emptyList(), false, false, currentPage = 7)
			assertEquals(2, rows.size)
			assertFalse(rows.single { it.chapter.id == remoteChapter.id }.isDownloaded)
			val localRow = rows.single { it.chapter.id == localChapter.id }
			assertEquals("Chapter_Baru", localRow.chapter.title)
			assertTrue(localRow.isDownloaded)
			assertTrue(localRow.isUnread)
			assertNull(localRow.lastReadPage)
			assertEquals(source, localRow.personalKey.source)
			assertEquals(1, localRepository.getPages(localChapter).size)
			assertNull(database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manga.id))
			val canonicalSelection = requireNotNull(resolver.selectExhentaiReadChoice(manga, space,
				choices.single { it.path == original.canonicalPath }))
			val canonical = requireNotNull(resolver.resolveExhentaiSessionChoice(manga, space, canonicalSelection))
			assertEquals(remoteChapter.id, canonical.manga.chapters!!.single().id)
		}
	}

	@Test fun renamedChapterOpensRealReaderAtFirstPageWithOldHistoryPresent() = runBlocking {
		val file = archive("Legacy Gallery", "Chapter.cbz")
		val bitmap = android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888)
		val png = try {
			java.io.ByteArrayOutputStream().use { output ->
				check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
				output.toByteArray()
			}
		} finally {
			bitmap.recycle()
		}
		ZipOutputStream(file.outputStream()).use { zip ->
			for (page in 1..8) {
				zip.putNextEntry(ZipEntry("$page.png"))
				zip.write(png)
				zip.closeEntry()
			}
		}
		val manga = gallery(990116L, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val online = requireNotNull(manga.chapters).single()
		try {
			data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			val before = loadVersionFixtureDetails(manga, space)
			val oldRow = before.mapChapters(0L, 0, null, emptyList(), false, false).single()
			val oldProgress = ChapterProgressEntity(manga.id, oldRow.personalKey.source,
				oldRow.personalKey.url, space.dbValue, 7, 0, 8)
			database.getChapterProgressDao().upsert(oldProgress)
			val oldHistory = HistoryEntity(manga.id, 100L, 200L, online.id,
				7, 0f, 1f, 0L, 1, space.dbValue)
			database.getHistoryDao().upsert(oldHistory)
			val renamed = File(file.parentFile, "Chapter_Baru.cbz")
			assertTrue(file.renameTo(renamed))
			presence.run(full = true)
			val after = loadVersionFixtureDetails(manga, space)
			val local = after.mapChapters(online.id, 0, null, emptyList(), false, false)
				.single { it.isDownloaded }
			assertTrue(local.chapter.id != online.id)
			assertNull(chapterProgress.get(manga.id, space, local.personalKey, local.chapter.id))
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			// Exercise the actual Reader Activity/ViewModel initial-load path with an explicit
			// chapter tap. Incognito isolates the initial resume check from asynchronous saves.
			val intent = ReaderIntent.Builder(context)
				.mangaForRead(after.toManga(), after.sourceManga)
				.favouriteSpace(space.dbValue)
				.state(ReaderState(local.chapter.id, 0, 0))
				.incognito()
				.build().intent
			ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
				lateinit var reader: ReaderViewModel
				scenario.onActivity { reader = ViewModelProvider(it)[ReaderViewModel::class.java] }
				val content = withTimeout(30000) {
					reader.content.first { it.pages.isNotEmpty() && it.state != null }
				}
				assertEquals(ReaderState(local.chapter.id, 0, 0), content.state)
				assertEquals(8, content.pages.count { it.chapterId == local.chapter.id })
			}
			assertEquals(oldHistory, database.getHistoryDao().find(manga.id))
			assertEquals(listOf(oldProgress), database.getChapterProgressDao().findAll(listOf(manga.id), space.dbValue))
		} finally {
			database.getMangaDao().delete(listOfNotNull(database.getMangaDao().find(manga.id)?.manga))
		}
	}

	@Test fun samePathSameSizeSameMtimeImageChangeStartsNewContentVersion() = runBlocking {
		verifyChangedCbzVersion(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, 990117L, metadataOnly = false)
	}

	@Test fun samePathSameSizeSameMtimeArchiveMetadataChangeStartsNewContentVersion() = runBlocking {
		verifyChangedCbzVersion(GEKKO_SOURCE, 990118L, metadataOnly = true)
	}

	private suspend fun verifyChangedCbzVersion(source: String, id: Long, metadataOnly: Boolean) {
		val file = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(id, source)
		val space = FavouriteSpace.NORMAL
		val progressDao = database.getChapterProgressDao()
		val historyDao = database.getHistoryDao()
		val bookmarkDao = database.getBookmarksDao()
		fun writeVersion(target: File, payload: ByteArray, comment: String) {
			ZipOutputStream(target.outputStream()).use { zip ->
				zip.setComment(comment)
				for (page in 1..8) {
					zip.putNextEntry(ZipEntry("$page.webp").apply { time = 1700000000000L })
					zip.write(payload)
					zip.closeEntry()
				}
			}
		}
		writeVersion(file, byteArrayOf(1, 2, 3), "fixture-A")
		val original = file.readBytes()
		val originalModified = file.lastModified()
		try {
			data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			presence.run(full = true)
			val initial = loadVersionFixtureDetails(manga, space)
			val row = initial.mapChapters(0L, 0, null, emptyList(), false, false).single()
			assertTrue(row.isDownloaded)
			assertEquals(8, localRepository.getPages(row.chapter).size)
			val state = ReaderState(row.chapter.id, 7, 0)
			chapterProgress.record(manga, space, row.personalKey, state, 8, isNewVisit = true)
			val oldProgress = requireNotNull(chapterProgress.get(id, space, row.personalKey, row.chapter.id))
			val oldHistory = HistoryEntity(id, 100L, 200L, row.chapter.id, 7, 0f, 1f, 0L, 1, space.dbValue)
			historyDao.upsert(oldHistory)
			val bookmark = org.koitharu.kotatsu.bookmarks.domain.Bookmark(
				manga = manga, pageId = id + 1000000L, chapterId = row.chapter.id, page = 7,
				scroll = 0, imageUrl = row.chapter.url, createdAt = java.time.Instant.ofEpochMilli(300L),
				percent = 1f, note = "previous CBZ version",
			)
			bookmarkDao.insert(BookmarkEntity(
				mangaId = id, pageId = bookmark.pageId, chapterId = bookmark.chapterId,
				page = bookmark.page, scroll = bookmark.scroll, imageUrl = bookmark.imageUrl,
				createdAt = 300L, percent = bookmark.percent, note = bookmark.note,
			))
			settings.setChapterReadOverride(id, row.chapter.id, true)
			val oldBookmarks = bookmarkDao.findAll(id)
			val oldOverrides = settings.getChapterReadOverrides(id)
			suspend fun currentDownloadedRow() = loadVersionFixtureDetails(manga, space).mapChapters(
				oldHistory.chapterId, 0, null, listOf(bookmark), false, false,
				readOverrides = settings.getChapterReadOverrides(id),
				progressByKey = progressDao.findAll(listOf(id), space.dbValue).associateBy { it.key() },
				currentPage = oldHistory.page,
			).single { it.isDownloaded }

			val unchanged = currentDownloadedRow()
			assertEquals(row.personalKey, unchanged.personalKey)
			assertEquals(7, unchanged.lastReadPage)
			assertTrue(unchanged.isBookmarked)
			assertFalse(unchanged.isUnread)

			val replacement = File(root, "detached-version.cbz")
			writeVersion(replacement, if (metadataOnly) byteArrayOf(1, 2, 3) else byteArrayOf(3, 2, 1),
				if (metadataOnly) "fixture-B" else "fixture-A")
			assertEquals(file.length(), replacement.length())
			replacement.copyTo(file, overwrite = true)
			assertTrue(file.setLastModified(originalModified))
			assertEquals(original.size.toLong(), file.length())
			assertEquals(originalModified, file.lastModified())
			assertFalse(original.contentEquals(file.readBytes()))
			ZipFile(file).use { archive ->
				assertEquals(if (metadataOnly) "fixture-B" else "fixture-A", archive.comment)
				assertArrayEquals(if (metadataOnly) byteArrayOf(1, 2, 3) else byteArrayOf(3, 2, 1),
					archive.getInputStream(archive.getEntry("1.webp")).use { it.readBytes() })
			}
			presence.run(full = true)
			assertEquals(setOf(id), classifier.getDownloadedIds(space, listOf(id)))
			val changed = currentDownloadedRow()
			assertEquals(8, localRepository.getPages(changed.chapter).size)
			assertFalse("New CBZ bytes must not reuse the previous progress identity", changed.personalKey == row.personalKey)
			assertNull("New CBZ version must not inherit the old page", changed.lastReadPage)
			assertTrue("New CBZ version must be unread", changed.isUnread)
			assertFalse("New CBZ version must not inherit the old bookmark", changed.isBookmarked)
			assertNull(chapterProgress.get(id, space, changed.personalKey, changed.chapter.id))
			assertEquals(oldHistory, historyDao.find(id))
			assertEquals(oldBookmarks, bookmarkDao.findAll(id))
			assertEquals(oldOverrides, settings.getChapterReadOverrides(id))
			assertTrue(oldProgress in progressDao.findAll(listOf(id), space.dbValue))

			chapterProgress.record(manga, space, changed.personalKey, ReaderState(changed.chapter.id, 2, 0),
				8, isNewVisit = true)
			assertEquals(2, chapterProgress.get(id, space, changed.personalKey, changed.chapter.id)?.page)
			assertTrue("Recording new progress must retain the previous version",
				oldProgress in progressDao.findAll(listOf(id), space.dbValue))
			assertEquals(oldBookmarks, bookmarkDao.findAll(id))
		} finally {
			database.getMangaDao().delete(listOfNotNull(database.getMangaDao().find(id)?.manga))
		}
	}

	@Test fun changedCbzOpensNormalReaderAtFirstPageAndRetainsPreviousCursorAndBookmark() = runBlocking {
		val file = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990122L, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val bitmap = android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888)
		val png = try {
			java.io.ByteArrayOutputStream().use { output ->
				check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
				output.toByteArray()
			}
		} finally { bitmap.recycle() }
		fun writeVersion(comment: String) {
			ZipOutputStream(file.outputStream()).use { zip ->
				zip.setComment(comment)
				for (page in 1..8) {
					zip.putNextEntry(ZipEntry("$page.png").apply { time = 1700000000000L })
					zip.write(png)
					zip.closeEntry()
				}
			}
		}
		val historyDao = database.getHistoryDao()
		val progressDao = database.getChapterProgressDao()
		val bookmarkDao = database.getBookmarksDao()
		try {
			writeVersion("version-A")
			data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			val first = loadVersionFixtureDetails(manga, space)
			val old = first.mapChapters(0L, 0, null, emptyList(), false, false).single()
			val oldPage = localRepository.getPages(old.chapter).first()
			val oldHistory = HistoryEntity(manga.id, 100L, 200L, old.chapter.id,
				7, 0f, 1f, 0L, 1, space.dbValue)
			historyDao.upsert(oldHistory)
			// Simulate a pre-versioning installation with only a latest History cursor.
			assertTrue(progressDao.findAll(listOf(manga.id), space.dbValue).isEmpty())
			val oldBookmark = BookmarkEntity(
				mangaId = manga.id, pageId = oldPage.id, chapterId = old.chapter.id, page = 0,
				scroll = 0, imageUrl = oldPage.url, createdAt = 300L, percent = 0f,
				note = "keep the old version bookmark",
			)
			bookmarkDao.insert(oldBookmark)
			val length = file.length()
			val mtime = file.lastModified()
			val oldBytes = file.readBytes()
			writeVersion("version-B")
			assertTrue(file.setLastModified(mtime))
			assertEquals(length, file.length())
			assertEquals(mtime, file.lastModified())
			assertFalse(oldBytes.contentEquals(file.readBytes()))
			presence.run(full = true)
			val details = loadVersionFixtureDetails(manga, space)
			val next = details.mapChapters(0L, 0, null, emptyList(), false, false).single { it.isDownloaded }
			assertTrue(old.chapter.id != next.chapter.id)
			assertEquals(listOf(oldBookmark), bookmarkDao.findAll(manga.id))
			assertEquals(oldHistory, historyDao.find(manga.id))
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			val intent = ReaderIntent.Builder(context)
				.mangaForRead(details.toManga(), details.sourceManga)
				.favouriteSpace(space.dbValue)
				.incognito(false)
				.build().intent
			ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
				lateinit var reader: ReaderViewModel
				scenario.onActivity { reader = ViewModelProvider(it)[ReaderViewModel::class.java] }
				var stage = "initial Reader content"
				try {
					val content = withTimeout(30000) {
						// Prefetch deliberately emits content with state=null (no scroll command).
						reader.content.first { it.pages.isNotEmpty() && reader.getCurrentState() != null }
					}
					assertEquals(ReaderState(next.chapter.id, 0, 0), content.state ?: reader.getCurrentState())
					assertEquals(ReaderState(next.chapter.id, 0, 0), reader.getCurrentState())
					assertEquals(8, content.pages.count { it.chapterId == next.chapter.id })
					assertTrue(content.pages.first().id != oldPage.id)
					stage = "normal history and progress persistence"
					withTimeout(30000) {
						while (historyDao.find(manga.id)?.chapterId != next.chapter.id ||
							chapterProgress.get(manga.id, space, next.personalKey, next.chapter.id)?.page != 0) {
							kotlinx.coroutines.delay(25)
						}
					}
					stage = "previous cursor and bookmark preservation"
					assertEquals(7, chapterProgress.get(manga.id, space, old.personalKey, old.chapter.id)?.page)
					assertEquals(0, historyDao.find(manga.id)?.page)
					assertEquals(listOf(oldBookmark), bookmarkDao.findAll(manga.id))
					scenario.onActivity { reader.toggleBookmark() }
					stage = "new-version bookmark insertion"
					withTimeout(15000) {
						while (bookmarkDao.findAll(manga.id).size != 2) kotlinx.coroutines.delay(25)
					}
					val newBookmark = bookmarkDao.findAll(manga.id).single { it.chapterId == next.chapter.id }
					assertTrue(newBookmark.pageId != oldBookmark.pageId)
					assertEquals(0, newBookmark.page)
					assertTrue(oldBookmark in bookmarkDao.findAll(manga.id))
					stage = "new-version bookmark observation"
					withTimeout(15000) { reader.isBookmarkAdded.first { it } }
					scenario.onActivity { reader.toggleBookmark() }
					stage = "new-version bookmark removal"
					withTimeout(15000) {
						while (bookmarkDao.findAll(manga.id).size != 1) kotlinx.coroutines.delay(25)
					}
					assertEquals(listOf(oldBookmark), bookmarkDao.findAll(manga.id))
				} catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
					// Only synthetic fixture IDs/positions, never archive paths or user content.
					val durable = withTimeoutOrNull(2000) {
						val history = historyDao.find(manga.id)
						val progress = progressDao.findAll(listOf(manga.id), space.dbValue)
						"historyId=${history?.chapterId}, historyPage=${history?.page}, " +
							"oldProgress=${progress.singleOrNull { it.key() == old.personalKey }?.page}, " +
							"newProgress=${progress.singleOrNull { it.key() == next.personalKey }?.page}, " +
							"progressCount=${progress.size}, bookmarks=${bookmarkDao.findAll(manga.id).size}"
					}
					throw AssertionError(
						"Reader acceptance timed out at $stage; expectedId=${next.chapter.id}, " +
							"current=${reader.getCurrentState()}, contentState=${reader.content.value.state}, " +
							"pages=${reader.content.value.pages.size}, incognito=${reader.isIncognitoMode.value}, " +
							"peek=${reader.isPeekMode.value}, globalIncognito=${settings.isIncognitoModeEnabled}, " +
							"nsfwIncognito=${settings.incognitoModeForNsfw}, " +
							"pickerPending=${reader.exhentaiChoiceRequest.value != null}; " +
							(durable ?: "database snapshot timed out"),
						timeout,
					)
				}
			}
		} finally {
			database.getMangaDao().delete(listOfNotNull(database.getMangaDao().find(manga.id)?.manga))
		}
	}

	@Test fun observedCbzByteRestorationStartsAnotherUnreadVersionWithoutReusingHistory() = runBlocking {
		val file = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990123L, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		fun writeArchive(comment: String) {
			ZipOutputStream(file.outputStream()).use { zip ->
				zip.setComment(comment)
				for (page in 1..8) {
					zip.putNextEntry(ZipEntry("$page.webp").apply { time = 1700000000000L })
					zip.write(byteArrayOf(1, 2, 3))
					zip.closeEntry()
				}
			}
		}
		writeArchive("version-A")
		val bytesA = file.readBytes()
		val mtime = file.lastModified()
		val dao = database.getChapterProgressDao()
		try {
			data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			suspend fun downloadedRow() = loadVersionFixtureDetails(manga, space)
				.mapChapters(
					0L, 0, null, emptyList(), false, false,
					readOverrides = settings.getChapterReadOverrides(manga.id),
					progressByKey = dao.findAll(listOf(manga.id), space.dbValue).associateBy { it.key() },
				).single { it.isDownloaded }
			val a = downloadedRow()
			chapterProgress.record(manga, space, a.personalKey, ReaderState(a.chapter.id, 7, 0), 8, isNewVisit = true)
			val savedA = requireNotNull(chapterProgress.get(manga.id, space, a.personalKey, a.chapter.id))
			writeArchive("version-B")
			assertTrue(file.setLastModified(mtime))
			assertEquals(bytesA.size.toLong(), file.length())
			assertFalse(bytesA.contentEquals(file.readBytes()))
			val b = downloadedRow()
			assertFalse(a.personalKey == b.personalKey)
			assertTrue(b.isUnread)
			chapterProgress.record(manga, space, b.personalKey, ReaderState(b.chapter.id, 2, 0), 8, isNewVisit = true)
			val savedB = requireNotNull(chapterProgress.get(manga.id, space, b.personalKey, b.chapter.id))
			file.writeBytes(bytesA)
			assertTrue(file.setLastModified(mtime))
			assertArrayEquals(bytesA, file.readBytes())
			assertEquals(mtime, file.lastModified())
			presence.run(full = true)
			val restored = downloadedRow()
			// Q22: every observed byte change must start a new version, even when the
			// content returns to a previous digest. Old positions must remain intact.
			assertFalse("Restored bytes must not reuse the first observed version", restored.personalKey == a.personalKey)
			assertFalse("Restored bytes must not reuse the immediately preceding version", restored.personalKey == b.personalKey)
			assertTrue(restored.isUnread)
			assertNull(restored.lastReadPage)
			assertNull(chapterProgress.get(manga.id, space, restored.personalKey, restored.chapter.id))
			assertTrue(savedA in dao.findAll(listOf(manga.id), space.dbValue))
			assertTrue(savedB in dao.findAll(listOf(manga.id), space.dbValue))
			assertEquals(restored.personalKey, downloadedRow().personalKey)
		} finally {
			database.getMangaDao().delete(listOfNotNull(database.getMangaDao().find(manga.id)?.manga))
		}
	}

	@Test fun movingCbzBetweenGalleriesAndSharingWithGekkoDoesNotTransferProgress() = runBlocking {
		val file = archive("Legacy Gallery", "Chapter.cbz")
		val bytes = file.readBytes()
		val original = gallery(990119L, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val movedMain = gallery(990120L, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME).copy(title = "Another Gallery")
		val movedGekko = gallery(990121L, GEKKO_SOURCE).copy(title = movedMain.title)
		val space = FavouriteSpace.NORMAL
		val all = listOf(original, movedMain, movedGekko)
		try {
			for (manga in all) data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			val oldRow = loadVersionFixtureDetails(original, space)
				.mapChapters(0L, 0, null, emptyList(), false, false).single()
			assertTrue(oldRow.isDownloaded)
			chapterProgress.record(original, space, oldRow.personalKey, ReaderState(oldRow.chapter.id, 0, 0),
				1, isNewVisit = true)
			val saved = requireNotNull(chapterProgress.get(original.id, space, oldRow.personalKey, oldRow.chapter.id))
			val folder = File(file.parentFile!!.parentFile, movedMain.title)
			check(folder.mkdir())
			val moved = File(folder, file.name)
			assertTrue(file.renameTo(moved))
			assertArrayEquals(bytes, moved.readBytes())
			presence.run(full = true)
			assertEquals(setOf(movedMain.id, movedGekko.id),
				classifier.getDownloadedIds(space, all.map { it.id }))
			for (manga in listOf(movedMain, movedGekko)) {
				val details = loadVersionFixtureDetails(manga, space)
				assertEquals(moved.canonicalFile, details.local?.file?.canonicalFile)
				val row = details.mapChapters(0L, 0, null, emptyList(), false, false).single()
				assertTrue(row.isDownloaded)
				assertTrue(row.isUnread)
				assertNull(chapterProgress.get(manga.id, space, row.personalKey, row.chapter.id))
			}
			val main = loadVersionFixtureDetails(movedMain, space)
				.mapChapters(0L, 0, null, emptyList(), false, false).single()
			chapterProgress.record(movedMain, space, main.personalKey, ReaderState(main.chapter.id, 0, 0),
				1, isNewVisit = true)
			val gekko = loadVersionFixtureDetails(movedGekko, space)
				.mapChapters(0L, 0, null, emptyList(), false, false).single()
			assertNull(chapterProgress.get(movedGekko.id, space, gekko.personalKey, gekko.chapter.id))
			assertEquals(saved, chapterProgress.get(original.id, space, oldRow.personalKey, oldRow.chapter.id))
			assertFalse(loadVersionFixtureDetails(original, space)
				.mapChapters(0L, 0, null, emptyList(), false, false).single().isDownloaded)
		} finally {
			database.getMangaDao().delete(all.mapNotNull { database.getMangaDao().find(it.id)?.manga })
		}
	}

	@Test fun sharedPhysicalCbzKeepsMainAndGekkoReaderBookmarksAndCompletionIndependent() = runBlocking {
		val file = archive("Legacy Gallery", "Chapter.cbz")
		val main = gallery(990124L, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val gekkoSource = gallery(990125L, GEKKO_SOURCE)
		// Same remote gallery locator AND same physical pages, but distinct source manga identities.
		val gekko = gekkoSource.copy(
			url = main.url, publicUrl = main.publicUrl,
			chapters = gekkoSource.chapters!!.map { it.copy(url = main.url) },
		)
		val all = listOf(main, gekko)
		val space = FavouriteSpace.NORMAL
		val bitmap = android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888)
		val png = try {
			java.io.ByteArrayOutputStream().use { output ->
				check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
				output.toByteArray()
			}
		} finally { bitmap.recycle() }
		ZipOutputStream(file.outputStream()).use { zip ->
			for (page in 1..8) {
				zip.putNextEntry(ZipEntry("$page.png").apply { time = 1700000000000L })
				zip.write(png)
				zip.closeEntry()
			}
		}
		val originalBytes = file.readBytes()
		val history = database.getHistoryDao()
		val progress = database.getChapterProgressDao()
		val bookmarks = database.getBookmarksDao()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		suspend fun awaitCondition(stage: String, condition: suspend () -> Boolean) {
			assertTrue("Timed out: $stage", withTimeoutOrNull(30000) {
				while (!condition()) kotlinx.coroutines.delay(25)
				true
			} == true)
		}
		try {
			for (manga in all) data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			presence.run(full = true)
			assertEquals(all.map { it.id }.toSet(), classifier.getDownloadedIds(space, all.map { it.id }))
			val mainDetails = loadVersionFixtureDetails(main, space)
			val gekkoDetails = loadVersionFixtureDetails(gekko, space)
			assertEquals(file.canonicalFile, mainDetails.local?.file?.canonicalFile)
			assertEquals(file.canonicalFile, gekkoDetails.local?.file?.canonicalFile)
			val mainRow = mainDetails.mapChapters(0L, 0, null, emptyList(), false, false).single()
			val gekkoRow = gekkoDetails.mapChapters(0L, 0, null, emptyList(), false, false).single()
			assertTrue(mainRow.isDownloaded)
			assertTrue(gekkoRow.isDownloaded)
			assertFalse(mainRow.personalKey == gekkoRow.personalKey)
			fun open(details: MangaDetails) = ReaderIntent.Builder(context)
				.mangaForRead(details.toManga(), details.sourceManga)
				.favouriteSpace(space.dbValue)
				.incognito(false)
				.build().intent

			ActivityScenario.launch<ReaderActivity>(open(mainDetails)).use { scenario ->
				lateinit var reader: ReaderViewModel
				scenario.onActivity { reader = ViewModelProvider(it)[ReaderViewModel::class.java] }
				awaitCondition("Main first page") {
					reader.content.value.pages.count { it.chapterId == mainRow.chapter.id } == 8 &&
						reader.getCurrentState() == ReaderState(mainRow.chapter.id, 0, 0)
				}
				scenario.onActivity { reader.toggleBookmark() }
				awaitCondition("Main bookmark inserted") { bookmarks.findAll(main.id).size == 1 }
				assertEquals(0, bookmarks.findAll(main.id).single().page)
				scenario.onActivity { reader.switchChapter(mainRow.chapter.id, 7) }
				awaitCondition("Main completed and durable") {
					reader.getCurrentState()?.page == 7 &&
						history.find(main.id)?.let { it.page == 7 && it.percent == 1f } == true &&
						chapterProgress.get(main.id, space, mainRow.personalKey, mainRow.chapter.id)?.page == 7
				}
			}
			val mainHistory = requireNotNull(history.find(main.id))
			val mainProgress = requireNotNull(chapterProgress.get(main.id, space, mainRow.personalKey, mainRow.chapter.id))
			val mainBookmark = bookmarks.findAll(main.id).single()
			assertEquals(1f, mainHistory.percent)
			assertEquals(7, mainProgress.page)
			assertNull(history.find(gekko.id))
			assertTrue(progress.findAll(listOf(gekko.id), space.dbValue).isEmpty())
			assertTrue(bookmarks.findAll(gekko.id).isEmpty())
			assertTrue(loadVersionFixtureDetails(gekko, space)
				.mapChapters(0L, 0, null, emptyList(), false, false).single().isUnread)

			ActivityScenario.launch<ReaderActivity>(open(gekkoDetails)).use { scenario ->
				lateinit var reader: ReaderViewModel
				scenario.onActivity { reader = ViewModelProvider(it)[ReaderViewModel::class.java] }
				awaitCondition("Gekko first page without inheriting Main completion") {
					reader.content.value.pages.count { it.chapterId == gekkoRow.chapter.id } == 8 &&
						reader.getCurrentState() == ReaderState(gekkoRow.chapter.id, 0, 0)
				}
				awaitCondition("Gekko independent durable first-page history") {
					history.find(gekko.id)?.let { it.page == 0 && it.percent == 0f } == true &&
						chapterProgress.get(gekko.id, space, gekkoRow.personalKey, gekkoRow.chapter.id)?.page == 0
				}
				assertFalse(reader.isBookmarkAdded.value)
				assertEquals(mainBookmark.pageId, reader.getCurrentPage()?.id)
				scenario.onActivity { reader.toggleBookmark() }
				awaitCondition("Gekko bookmark inserted") { bookmarks.findAll(gekko.id).size == 1 }
				val gekkoBookmark = bookmarks.findAll(gekko.id).single()
				assertEquals(mainBookmark.pageId, gekkoBookmark.pageId)
				assertEquals(gekkoRow.chapter.id, gekkoBookmark.chapterId)
				assertEquals(listOf(mainBookmark), bookmarks.findAll(main.id))
				awaitCondition("Gekko observes its own bookmark") { reader.isBookmarkAdded.value }
				scenario.onActivity { reader.toggleBookmark() }
				awaitCondition("Gekko bookmark removed") { bookmarks.findAll(gekko.id).isEmpty() }
				assertEquals(listOf(mainBookmark), bookmarks.findAll(main.id))
			}
			val finalMain = requireNotNull(history.find(main.id))
			val finalGekko = requireNotNull(history.find(gekko.id))
			assertEquals(mainHistory.chapterId, finalMain.chapterId)
			assertEquals(7, finalMain.page)
			assertEquals(1f, finalMain.percent)
			assertEquals(gekkoRow.chapter.id, finalGekko.chapterId)
			assertEquals(0, finalGekko.page)
			assertEquals(0f, finalGekko.percent)
			assertEquals(mainProgress, chapterProgress.get(main.id, space, mainRow.personalKey, mainRow.chapter.id))
			assertEquals(0, chapterProgress.get(gekko.id, space, gekkoRow.personalKey, gekkoRow.chapter.id)?.page)
			assertEquals(listOf(mainBookmark), bookmarks.findAll(main.id))
			assertTrue(bookmarks.findAll(gekko.id).isEmpty())
			assertEquals(all.map { it.id }.toSet(), classifier.getDownloadedIds(space, all.map { it.id }))
			assertArrayEquals(originalBytes, file.readBytes())
		} finally {
			database.getMangaDao().delete(all.mapNotNull { database.getMangaDao().find(it.id)?.manga })
		}
	}

	@Test fun foregroundCbzByteChangeRequiresExplicitReaderReloadFromFirstPage() = runBlocking {
		verifyReaderFileChange(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, 990126L, unavailableOnResume = false)
	}

	@Test fun resumedReaderRejectsMissingAndCorruptCbzWithoutSwitchingToSibling() = runBlocking {
		verifyReaderFileChange(GEKKO_SOURCE, 990127L, unavailableOnResume = true)
	}

	private suspend fun verifyReaderFileChange(source: String, id: Long, unavailableOnResume: Boolean) {
		val file = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(id, source)
		val space = FavouriteSpace.NORMAL
		val bitmap = android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888)
		val png = try {
			java.io.ByteArrayOutputStream().use { output ->
				check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
				output.toByteArray()
			}
		} finally { bitmap.recycle() }
		fun archiveBytes(prefix: String): ByteArray = java.io.ByteArrayOutputStream().use { output ->
			ZipOutputStream(output).use { zip ->
				for (page in 1..8) {
					zip.putNextEntry(ZipEntry("$prefix$page.png").apply { time = 1700000000000L })
					zip.write(png)
					zip.closeEntry()
				}
			}
			output.toByteArray()
		}
		val original = archiveBytes("a")
		val replacement = archiveBytes("b")
		assertEquals(original.size, replacement.size)
		assertFalse(original.contentEquals(replacement))
		file.writeBytes(original)
		val mtime = file.lastModified()
		val history = database.getHistoryDao()
		val bookmarks = database.getBookmarksDao()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		suspend fun awaitCondition(stage: String, condition: suspend () -> Boolean) {
			assertTrue("Timed out: $stage", withTimeoutOrNull(30000) {
				while (!condition()) kotlinx.coroutines.delay(25)
				true
			} == true)
		}
		try {
			data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			val initial = loadVersionFixtureDetails(manga, space)
			val old = initial.mapChapters(0L, 0, null, emptyList(), false, false).single()
			val oldVersion = requireNotNull(initial.local?.exhentaiContentVersion)
			val intent = ReaderIntent.Builder(context)
				.mangaForRead(initial.toManga(), initial.sourceManga)
				.favouriteSpace(space.dbValue)
				.incognito(false)
				.build().intent
			ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
				lateinit var reader: ReaderViewModel
				scenario.onActivity { reader = ViewModelProvider(it)[ReaderViewModel::class.java] }
				awaitCondition("original Reader pages") {
					reader.content.value.pages.count { it.chapterId == old.chapter.id } == 8 &&
						reader.getCurrentState() == ReaderState(old.chapter.id, 0, 0)
				}
				scenario.onActivity { reader.switchChapter(old.chapter.id, 5) }
				awaitCondition("original reading state persisted") {
					reader.getCurrentState() == ReaderState(old.chapter.id, 5, 0) &&
						history.find(id)?.let { it.chapterId == old.chapter.id && it.page == 5 } == true &&
						chapterProgress.get(id, space, old.personalKey, old.chapter.id)?.page == 5
				}
				scenario.onActivity { reader.toggleBookmark() }
				awaitCondition("original bookmark persisted") { bookmarks.findAll(id).size == 1 }
				val originalBookmark = bookmarks.findAll(id).single()
				assertEquals(5, originalBookmark.page)
				val originalProgress = requireNotNull(chapterProgress.get(id, space, old.personalKey, old.chapter.id))
				suspend fun assertOldState() {
					assertEquals(ReaderState(old.chapter.id, 5, 0), reader.getCurrentState())
					assertEquals(oldVersion, reader.mangaDetails.value?.local?.exhentaiContentVersion)
					assertEquals(8, reader.content.value.pages.count { it.chapterId == old.chapter.id })
					assertEquals(old.chapter.id, history.find(id)?.chapterId)
					assertEquals(5, history.find(id)?.page)
					assertEquals(originalProgress, chapterProgress.get(id, space, old.personalKey, old.chapter.id))
					assertEquals(listOf(originalBookmark), bookmarks.findAll(id))
				}
				suspend fun awaitDialog(change: ReaderViewModel.ExhentaiFileChange) {
					awaitCondition("Reader dialog $change") {
						var shown = false
						scenario.onActivity { activity ->
							val dialog = activity.exhentaiFileChangeDialog
							shown = dialog?.isShowing == true &&
								dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.isEnabled == true
						}
						shown && reader.exhentaiFileChange.value == change
					}
					scenario.onActivity { activity ->
						val dialog = requireNotNull(activity.exhentaiFileChangeDialog)
						assertEquals(activity.getString(org.koitharu.kotatsu.R.string.exhentai_reader_file_changed_title),
							dialog.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString())
						assertTrue(activity.getString(org.koitharu.kotatsu.R.string.exhentai_reader_file_reload).equals(
							dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).text.toString(), ignoreCase = true))
						val expectedMessage = if (change == ReaderViewModel.ExhentaiFileChange.UNAVAILABLE)
							org.koitharu.kotatsu.R.string.exhentai_reader_file_unavailable
						else org.koitharu.kotatsu.R.string.exhentai_reader_file_changed_message
						assertEquals(activity.getString(expectedMessage),
							dialog.findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString())
					}
				}
				fun clickReload() {
					scenario.onActivity { activity ->
						val button = requireNotNull(activity.exhentaiFileChangeDialog)
							.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
						assertTrue(button.isEnabled)
						assertTrue(button.performClick())
					}
				}
				var sibling: File? = null
				if (unavailableOnResume) {
					// A different valid CBZ must not become an implicit reload target.
					val siblingFolder = File(file.parentFile!!.parentFile, "Legacy Gallery_1")
					check(siblingFolder.mkdir())
					sibling = File(siblingFolder, "Chapter.cbz").apply { writeBytes(original) }
					assertEquals(setOf(file.canonicalPath, sibling.canonicalPath),
						resolver.getExhentaiReadChoices(manga, space).map { it.path }.toSet())
					scenario.moveToState(Lifecycle.State.STARTED)
					assertTrue(file.delete())
					scenario.moveToState(Lifecycle.State.RESUMED)
					awaitDialog(ReaderViewModel.ExhentaiFileChange.CHANGED)
					assertOldState()
					clickReload()
					awaitDialog(ReaderViewModel.ExhentaiFileChange.UNAVAILABLE)
					assertOldState()
					assertArrayEquals(original, sibling.readBytes())
					file.writeBytes(byteArrayOf(1, 2, 3, 4))
					clickReload()
					awaitDialog(ReaderViewModel.ExhentaiFileChange.UNAVAILABLE)
					assertOldState()
					assertArrayEquals(original, sibling.readBytes())
				}
				file.writeBytes(replacement)
				assertTrue(file.setLastModified(mtime))
				assertEquals(original.size.toLong(), file.length())
				assertEquals(mtime, file.lastModified())
				assertFalse(original.contentEquals(file.readBytes()))
				awaitDialog(if (unavailableOnResume) ReaderViewModel.ExhentaiFileChange.UNAVAILABLE
					else ReaderViewModel.ExhentaiFileChange.CHANGED)
				// No automatic state/cursor publication, even though the new bytes are readable.
				assertOldState()
				clickReload()
				awaitCondition("explicit new CBZ version at first page") {
					val next = reader.mangaDetails.value?.local?.exhentaiContentVersion
					next != null && next.chapterId != oldVersion.chapterId && next.sha256 != oldVersion.sha256 &&
						reader.exhentaiFileChange.value == null &&
						reader.getCurrentState() == ReaderState(next.chapterId, 0, 0) &&
						reader.content.value.pages.count { it.chapterId == next.chapterId } == 8 &&
						reader.getCurrentPage()?.url?.contains("b1.png") == true
				}
				val nextDetails = requireNotNull(reader.mangaDetails.value)
				val nextVersion = requireNotNull(nextDetails.local?.exhentaiContentVersion)
				val next = nextDetails.mapChapters(0L, 0, null, emptyList(), false, false).single { it.isDownloaded }
				assertEquals(file.canonicalPath, nextVersion.path)
				assertFalse(next.personalKey == old.personalKey)
				awaitCondition("new version persisted at first page") {
					history.find(id)?.let { it.chapterId == next.chapter.id && it.page == 0 } == true &&
						chapterProgress.get(id, space, next.personalKey, next.chapter.id)?.page == 0
				}
				assertEquals(originalProgress, chapterProgress.get(id, space, old.personalKey, old.chapter.id))
				assertEquals(listOf(originalBookmark), bookmarks.findAll(id))
				assertArrayEquals(replacement, file.readBytes())
				sibling?.let { assertArrayEquals(original, it.readBytes()) }
				scenario.onActivity { assertNull(it.exhentaiFileChangeDialog) }
			}
		} finally {
			database.getMangaDao().delete(listOfNotNull(database.getMangaDao().find(id)?.manga))
		}
	}

	private suspend fun loadVersionFixtureDetails(manga: Manga, space: FavouriteSpace): MangaDetails =
		detailsLoader(
			intent = MangaIntent(SavedStateHandle(mapOf(AppRouter.KEY_MANGA to ParcelableManga(manga)))),
			force = false, favouriteSpace = space, preferLocalBeforeInitialSnapshot = true,
		).first()

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
