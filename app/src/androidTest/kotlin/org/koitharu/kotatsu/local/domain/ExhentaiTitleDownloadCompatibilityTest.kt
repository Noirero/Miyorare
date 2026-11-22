package org.koitharu.kotatsu.local.domain

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
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
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
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
	@Inject lateinit var destinationStore: DownloadDestinationStore
	@Inject lateinit var database: MangaDatabase
	@Inject lateinit var deletion: DeleteLocalMangaUseCase
	@Inject lateinit var data: MangaDataRepository

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

	/**
	 * Characterization of Private destination migration: active roots can differ while the old
	 * Normal root remains readable as historical Private. The same unmarked CBZ must remain
	 * inaccessible through an ambiguous automatic title match until that overlap is removed.
	 */
	@Test fun migratedPrivateHistoryAloneBlocksAnOtherwiseReadableLegacyChapter() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val original = chapter.readBytes()
		val emptyDuplicate = File(root, "downloads/ExHentai (OTHER)/Legacy Gallery_1")
		check(emptyDuplicate.mkdirs())
		val online = gallery(990013, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)

		// Recreate the actual preference transition instead of clearing history in setUp().
		prefs.edit(commit = true) { remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT) }
		assertEquals(root.canonicalFile, destinationStore.effectiveRoot(FavouriteSpace.PRIVATE)?.canonicalFile)
		destinationStore.setRoot(FavouriteSpace.PRIVATE, File(root, "private"))
		assertTrue(destinationStore.privateUsesOwnRoot())
		assertFalse(destinationStore.rootsOverlap())
		assertTrue(destinationStore.readableRoots(FavouriteSpace.PRIVATE).any {
			it.canonicalFile == root.canonicalFile
		})
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL))

		// Control: change only the other space's historical root history, not the CBZ.
		destinationStore.forgetRoot(root)
		assertFalse(destinationStore.readableRoots(FavouriteSpace.PRIVATE).any {
			it.canonicalFile == root.canonicalFile
		})
		assertEquals(chapter.canonicalFile,
			resolver.findSavedManga(online, FavouriteSpace.NORMAL)?.file?.canonicalFile)
		assertTrue(emptyDuplicate.isDirectory)
		assertArrayEquals(original, chapter.readBytes())
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))

		// A second physical CBZ must remain ambiguous even after historical overlap is cleared.
		val otherReceipt = ExhentaiChapterOwnership.scopedComment(
			990014, ExhentaiChapterOwnership.Receipt(FavouriteSpace.NORMAL, UUID.randomUUID()), false,
		)
		val duplicate = archive("Legacy Gallery_2", "Chapter.cbz", comment = otherReceipt)
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL))
		assertTrue(duplicate.isFile)
		assertArrayEquals(original, chapter.readBytes())
		assertNull(database.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, online.id))
	}

	@Test fun sharedRootRequiresExistingSpaceProofAndNeverWritesOwnership() = runBlocking {
		prefs.edit(commit = true) {
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path)
		}
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val online = gallery(990008, EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		assertNull(resolver.findSavedManga(online, FavouriteSpace.NORMAL))
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

	private fun archive(folder: String, name: String, comment: String? = null, private: Boolean = false): File {
		val base = if (private) File(root, "private") else root
		val dir = File(base, "downloads/ExHentai (OTHER)/$folder")
		check(dir.mkdirs())
		return File(dir, name).apply {
			ZipOutputStream(outputStream()).use { zip ->
				if (comment != null) zip.setComment(comment)
				zip.putNextEntry(ZipEntry("1.webp"))
				zip.write(byteArrayOf(1, 2, 3))
				zip.closeEntry()
			}
		}
	}

	companion object {
		private const val GEKKO_SOURCE = "TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"
	}
}
