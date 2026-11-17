package org.koitharu.kotatsu.download

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.internal.closeQuietly
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.download.ui.list.resolveDownloadChapterMetadata
import org.koitharu.kotatsu.download.ui.worker.DownloadTempFiles
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaDirOutput
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.data.output.LocalMangaZipOutput
import org.koitharu.kotatsu.local.data.output.LocalNovelEpubOutput
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import java.io.File
import java.util.UUID
import javax.inject.Provider

/** Real Room/filesystem boundaries; does not claim a WorkManager or physical-device UI test. */
@RunWith(AndroidJUnit4::class)
class DownloadFixRegressionTest {

	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var root: File
	private lateinit var databaseName: String

	@Before
	fun setUp() {
		val name = "download-fix-${UUID.randomUUID()}"
		root = File(context.cacheDir, name).apply { check(mkdirs()) }
		databaseName = "$name.db"
	}

	@After
	fun tearDown() {
		context.deleteDatabase(databaseName)
		root.deleteRecursively()
	}

	@Test
	fun finishingAnotherWorkerPreservesStagingAndAllOutputFormatsFinalize() = runBlocking {
		val manga = manga("MIHON_424242")
		val novel = manga("LN_DOWNLOAD_FIX")
		val officialSeed = manga(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val official = officialSeed.copy(
			url = "https://exhentai.org/g/12345/abcdef/",
			publicUrl = "https://exhentai.org/g/12345/abcdef/",
			chapters = requireNotNull(officialSeed.chapters).take(1),
		)
		val outputs = listOf(
			LocalMangaZipOutput(File(root, "single.cbz"), manga) to manga,
			LocalMangaDirOutput(File(root, "multiple"), manga) to manga,
			LocalNovelEpubOutput(File(root, "novel.epub"), novel) to novel,
			LocalMangaOutput.createExhentaiArchive(root, official, DownloadFormat.MULTIPLE_CBZ, false) to official,
		)
		val filesB = DownloadTempFiles()
		try {
			val coverB = filesB.create(root, "png").apply {
				writeBytes(android.util.Base64.decode(
					"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
					android.util.Base64.DEFAULT,
				))
			}
			val html = File(root, "chapter.html").apply { writeText("<p>Offline chapter</p>") }
			for ((output, seed) in outputs) {
				output.addCover(coverB, MimeType("image/png"))
				val chapter = requireNotNull(seed.chapters).first()
				val isNovel = output is LocalNovelEpubOutput
				output.addPage(IndexedValue(0, chapter), if (isNovel) html else coverB, 0,
					MimeType(if (isNovel) "text/html" else "image/png"))
			}
			val stagingB = root.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()
			assertTrue(stagingB.size >= 5) // four archive staging files and B's cover
			lateinit var coverA: File
			DownloadTempFiles().use { filesA ->
				coverA = filesA.create(root, "jpg").apply { writeText("worker A") }
			}
			assertFalse(coverA.exists())
			assertTrue(stagingB.all(File::isFile))
			assertTrue(coverB.isFile)
			for ((output, seed) in outputs) {
				output.flushChapter(requireNotNull(seed.chapters).first())
				output.finish()
				val committed = requireNotNull(output.committedFile)
				assertTrue(committed.exists())
				assertTrue(requireNotNull(LocalMangaParser(committed).getManga(true).manga.chapters).isNotEmpty())
			}
		} finally {
			withContext(NonCancellable) {
				for ((output, _) in outputs) {
					try { output.cleanup() } finally { output.closeQuietly() }
				}
				filesB.close()
			}
		}
		assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
	}

	@Test
	fun cancellationCleansOnlyItsOwnerWhileForeignCoverIsStillOpen() = runBlocking {
		val ready = CompletableDeferred<File>()
		DownloadTempFiles().use { filesB ->
			val coverB = filesB.create(root, "jpg")
			coverB.outputStream().use { transfer ->
				transfer.write("before ".toByteArray())
				val workerA = launch {
					val filesA = DownloadTempFiles()
					try {
						ready.complete(filesA.create(root, "jpg").apply { writeText("partial A") })
						awaitCancellation()
					} finally {
						withContext(NonCancellable) { filesA.close() }
					}
				}
				val own = ready.await()
				workerA.cancelAndJoin()
				assertFalse(own.exists())
				assertTrue(coverB.isFile)
				transfer.write("after".toByteArray())
			}
			assertEquals("before after", coverB.readText())
		}
	}

	@Test
	fun coldMangaAndNovelExpansionReadsPersistedChaptersWithoutInstalledSource() = runBlocking {
		val seeds = listOf(manga("MIHON_424242"), manga("LN_DOWNLOAD_FIX").copy(id = 43L))
		Room.databaseBuilder(context, MangaDatabase::class.java, databaseName).build().let { db ->
			try {
				val repository = repository(db)
				for (seed in seeds) repository.storeManga(seed, replaceExisting = true)
			} finally { db.close() }
		}
		// No live Room instance, source cache or chapter-bearing seed survives this boundary.
		val reopened = Room.databaseBuilder(context, MangaDatabase::class.java, databaseName).build()
		try {
			val repository = repository(reopened)
			for (seed in seeds) {
				val cold = requireNotNull(repository.findMangaById(seed.id, withChapters = false))
				assertEquals(null, cold.chapters)
				val groupedIds = setOf(1L) + setOf(3L)
				val chapters = resolveDownloadChapterMetadata(cold, groupedIds,
					{ repository.findMangaById(it, withChapters = true) },
					{ error("Cold offline expansion must not reach the uninstalled source") })
				assertEquals(listOf(1L, 2L, 3L), chapters.orEmpty().map { it.id })
				assertEquals(listOf(1L, 3L), chapters.orEmpty().filter { it.id in groupedIds }.map { it.id })
			}
		} finally { reopened.close() }
	}

	private fun repository(db: MangaDatabase) = MangaDataRepository(db,
		Provider { error("Link resolver is not used") }, Provider { error("Shortcut manager is not used") })

	private fun manga(sourceName: String): Manga {
		val source = MissingMangaSource(sourceName)
		return Manga(
			id = 42L, title = "Download regression", altTitles = emptySet(), state = null, rating = 0f,
			contentRating = null, url = "/manga", publicUrl = "https://example.invalid/manga",
			coverUrl = null, largeCoverUrl = null, authors = emptySet(), description = null,
			source = source, tags = emptySet(), chapters = listOf(1L, 2L, 3L).map { id ->
				MangaChapter(id = id, title = "Chapter $id", number = id.toFloat(), volume = 0,
					url = "/chapter/$id", scanlator = null, uploadDate = 0L, branch = null, source = source)
			},
		)
	}
}
