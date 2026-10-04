package org.koitharu.kotatsu.local.library

import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.input.LocalPdfCache
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.DeleteReadChaptersUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Real production index, Room, format backends and ReaderActivity; no mocked index success. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SmartLocalLibraryRuntimeTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var library: SmartLocalLibrary
    @Inject lateinit var repository: LocalMangaRepository
    @Inject lateinit var documents: LocalDocuments
    @Inject lateinit var contentReader: LocalContentReader
    @Inject lateinit var dataRepository: MangaDataRepository
    @Inject lateinit var database: MangaDatabase
    @Inject lateinit var cleanup: DeleteReadChaptersUseCase
    @Inject lateinit var workerFactory: HiltWorkerFactory
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var fixture: File
    private val touched = HashSet<Long>()

    @Before fun setUp() {
        context.getSharedPreferences("smart_local_library", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "smart-local-index.json").delete()
        File(context.filesDir, "smart-local-index.json.bak").delete()
        fixture = File(context.cacheDir, "smart-local-test-${System.nanoTime()}").also { check(it.mkdirs()) }
        hiltRule.inject()
        // The production Application provides this configuration. HiltTestApplication does not.
        if (runCatching { WorkManager.getInstance(context) }.isFailure) {
            WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(workerFactory).build())
        }
    }

    @After fun tearDown(): Unit = runBlocking {
        if (::library.isInitialized) library.state.value.books.forEach { touched += it.id }
        touched.forEach { database.getHistoryDao().delete(it) }
        fixture.deleteRecursively() // Test-owned fixtures only, never a production deletion helper.
        context.getSharedPreferences("smart_local_library", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "smart-local-index.json").delete()
        Unit
    }

    @Test fun selectedRootsFormatsReaderProgressAndDisplay() = runBlocking {
        val root = dir("selected")
        val mixed = File(root, "Mixed").also { it.mkdirs() }
        archive(File(mixed, "Chapter 1.cbz"))
        val pages = File(mixed, "Chapter 2").also { it.mkdirs() }
        File(pages, "10.png").writeBytes(png()); File(pages, "2.png").writeBytes(png())
        archive(File(mixed, "Chapter 10.zip"))
        File(mixed, "ComicInfo.xml").writeText("<broken>")
        pdf(File(root, "Report.pdf")); epub(File(root, "Novel.epub"))
        val outside = dir("outside"); archive(File(outside, "Never scanned.cbz"))
        val source = File(root, "Arbitrary source").also { it.mkdirs() }
        for (name in listOf("Alpha", "Beta")) {
            val manga = File(source, name).also { it.mkdirs() }; archive(File(manga, "1.cbz"))
        }
        library.addRoot(root.toUri()); library.addRoot(root.toUri())
        assertEquals(1, library.state.value.roots.size)
        assertEquals(setOf("Mixed", "Report.pdf", "Novel.epub", "Alpha", "Beta"), library.state.value.books.map { it.node.name }.toSet())
        val book = library.state.value.books.single { it.node.name == "Mixed" }
        val manga = repository.getDetails(book.toManga(false))
        val chapters = requireNotNull(manga.chapters)
        assertTrue(MangaDetails(manga).mapChapters(0L, 0, null, emptyList(), false, false).all { it.isDownloaded })
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), chapters.map { it.title })
        assertTrue(chapters.all { repository.getPages(it).isNotEmpty() })
        assertTrue(repository.getPages(chapters[1]).first().url.endsWith("2.png"))
        val ids = chapters.map { it.id }
        library.setDisplayOptions(true)
        assertEquals(ids, library.details(book.id)?.chapters?.map { it.id })
        assertEquals("Chapter 1.cbz", library.details(book.id)?.chapters?.first()?.title)
        assertTrue(File(mixed, "Chapter 1.cbz").exists())
        assertEquals(0, cleanup(manga))
        assertTrue(File(mixed, "Chapter 1.cbz").exists())

        // The Activity's production PageLoader decodes actual archive, folder and PDF pages.
        openReader(book.id, chapters[0].id, text = false)
        openReader(book.id, chapters[1].id, text = false)
        val report = library.state.value.books.single { it.node.name == "Report.pdf" }
        openReader(report.id, report.chapters.single().id, text = false)
        val novel = library.state.value.books.single { it.node.name == "Novel.epub" }
        val novelChapter = requireNotNull(library.details(novel.id)?.chapters).single()
        val html = requireNotNull(repository.getChapterHtml(novelChapter))
        assertTrue(html.contains("First section") && html.contains("Second section"))
        assertTrue(html.contains("local-epub:OPS/cover.png"))
        assertNotNull(repository.getLocalChapterImage(novelChapter.url, "local-epub:OPS/cover.png"))
        openReader(novel.id, novelChapter.id, text = true)

        // Save a real chapter/page state, then reconstruct the durable index without a scan.
        val now = System.currentTimeMillis()
        database.getHistoryDao().upsert(HistoryEntity(book.id, now, now, chapters[1].id, 1, .4f, .5f, 0, 3, now))
        touched += book.id
        val restarted = restart()
        assertEquals(chapters[1].id, restarted.details(book.id)?.chapters?.get(1)?.id)
        assertEquals(1, database.getHistoryDao().find(book.id)?.page)
        val beta = restarted.state.value.books.single { it.node.name == "Beta" }
        restarted.hide(setOf(beta.id)); restarted.scan()
        assertNotNull(restarted.state.value.books.singleOrNull { it.node.name == "Alpha" })
        assertNull(restarted.book(beta.id))
        restarted.restore(beta.node.key)
        restarted.setDisplayOptions(false, LocalReadingFilter.READING, LocalLibrarySort.LAST_READ)
        assertTrue(restarted.list(null).any { it.id == book.id })
        restarted.setDisplayOptions(false, LocalReadingFilter.UNREAD)
        assertFalse(restarted.list(null).any { it.id == book.id })
        restarted.setDisplayOptions(false, LocalReadingFilter.ALL)
        restarted.addRoot(outside.toUri())
        assertEquals(2, restarted.state.value.roots.size)
        assertEquals(6, restarted.state.value.books.size)
    }

    @Test fun exclusionsRestartDetachAndOwnedDeletion() = runBlocking {
        val root = dir("selected")
        val target = File(root, "Target").also { it.mkdirs() }
        archive(File(target, "1.cbz"))
        File(target, "notes.txt").writeText("unknown files must be retained")
        val sibling = File(root, "Sibling").also { it.mkdirs() }; archive(File(sibling, "1.zip"))
        val bytes = File(sibling, "1.zip").readBytes()
        library.addRoot(root.toUri())
        val id = library.state.value.books.single { it.node.name == "Target" }.id
        val key = library.book(id)!!.node.key
        library.hide(setOf(id)); library.scan()
        assertNull(library.book(id)); assertTrue(File(target, "1.cbz").exists())
        val restarted = restart(); restarted.scan()
        assertNull(restarted.book(id)); assertTrue(restarted.excluded().containsKey(key))
        restarted.restore(key); assertNotNull(restarted.book(id))
        restarted.deleteFromDevice(setOf(id))
        assertNull(restarted.book(id)); assertFalse(File(target, "1.cbz").exists())
        assertTrue(root.isDirectory && target.isDirectory && sibling.isDirectory)
        assertEquals("unknown files must be retained", File(target, "notes.txt").readText())
        assertArrayEquals(bytes, File(sibling, "1.zip").readBytes())
        restarted.scan(); assertNull(restarted.book(id))
        restarted.removeRoot(root.toUri().toString())
        assertTrue(restarted.state.value.books.isEmpty())
        assertArrayEquals(bytes, File(sibling, "1.zip").readBytes())
        assertTrue(restart().state.value.roots.isEmpty())
    }

    @Test fun externalMovesPermissionLossAndProgressiveDiagnosis() = runBlocking {
        val root = dir("selected")
        val valid = File(root, "Valid").also { it.mkdirs() }; archive(File(valid, "1.cbz"))
        val ambiguous = File(root, "Needs review").also { it.mkdirs() }
        File(ambiguous, "1.png").writeBytes(png()); archive(File(ambiguous, "2.zip"))
        File(root, "unsupported.bin").writeText("not supported"); File(root, "Empty").mkdirs()
        library.addRoot(root.toUri())
        val book = library.state.value.books.single()
        assertTrue(library.state.value.diagnoses.any { it.reason == "review" })
        assertTrue(library.state.value.diagnoses.any { it.reason == "unsupported" })
        val chapter = requireNotNull(library.details(book.id)?.chapters).single()
        check(File(valid, "1.cbz").renameTo(File(fixture, "Moved.cbz")))
        assertTrue(runCatching { repository.getPages(chapter) }.isFailure)
        library.scan(); assertNull(library.book(book.id))
        archive(File(valid, "2.cbz")); library.scan()
        val preserved = library.state.value.books.single().id
        check(root.renameTo(File(fixture, "disconnected")))
        library.scan()
        assertNotNull(library.book(preserved))
        assertTrue(library.state.value.diagnoses.any { it.reason == "unavailable" })
        assertTrue(restart().state.value.diagnoses.any { it.reason == "unavailable" })

        // Android rejects traversal entries before ZIP enumeration; keep them out of valid books.
        val unsafeRoot = dir("unsafe-archive")
        zip(File(unsafeRoot, "Unsafe.cbz"), mapOf("../outside.png" to png()))
        library.addRoot(unsafeRoot.toUri())
        val unsafe = library.state.value.books.single { it.node.name == "Unsafe.cbz" }
        val unsafeChapter = requireNotNull(library.details(unsafe.id)?.chapters).single()
        assertTrue(runCatching { repository.getPages(unsafeChapter) }.isFailure)
        assertTrue(library.state.value.diagnoses.any { it.node?.key == unsafe.node.key && it.reason == "unreadable" })
    }

    @Test fun optionalMetadataCoversDiscoveriesAndLegacyResume() = runBlocking {
        val root = dir("selected")
        val metadata = File(root, "Metadata").also { it.mkdirs() }
        File(metadata, "ComicInfo.xml").writeText("<ComicInfo><Series>Enriched title</Series><Writer>Author</Writer><Cover>poster.png</Cover></ComicInfo>")
        File(metadata, "poster.png").writeBytes(png(24))
        File(metadata, "cover.png").writeText("corrupt explicit cover")
        archive(File(metadata, "1.cbz"))
        val archiveWithMetadata = File(root, "Embedded.cbz")
        zip(archiveWithMetadata, mapOf("1.png" to png(), "poster.png" to png(24),
            "ComicInfo.xml" to "<ComicInfo><Series>Embedded title</Series><Writer>Embedded author</Writer><Cover>poster.png</Cover></ComicInfo>".toByteArray()))
        val legacy = File(root, "Legacy").also { it.mkdirs() }
        archive(File(legacy, "Chapter 1.cbz")); archive(File(legacy, "Chapter 2.zip"))
        val previous = LocalMangaParser(legacy).getManga(true).manga
        dataRepository.storeManga(previous, replaceExisting = true)
        val last = requireNotNull(previous.chapters).last()
        val now = System.currentTimeMillis()
        database.getHistoryDao().upsert(HistoryEntity(previous.id, now, now, last.id, 1, 0f, .5f, 0, 2, now))
        touched += previous.id
        library.addRoot(root.toUri())
        val enriched = library.state.value.books.single { it.node.name == "Metadata" }
        val embedded = library.state.value.books.single { it.node.name == "Embedded.cbz" }
        assertEquals("Embedded title", repository.getDetails(embedded.toManga(false)).title)
        val embeddedCover = requireNotNull(library.cover(embedded.id))
        assertEquals(24, BitmapFactory.decodeByteArray(embeddedCover, 0, embeddedCover.size)?.width)
        assertEquals("Enriched title", library.details(enriched.id)?.title)
        assertEquals(setOf("Author"), library.details(enriched.id)?.authors)
        val cover = requireNotNull(library.cover(enriched.id))
        assertEquals(24, BitmapFactory.decodeByteArray(cover, 0, cover.size)?.width)
        assertEquals(last.id, library.details(previous.id)?.chapters?.last()?.id)
        assertEquals(last.id, restart().details(previous.id)?.chapters?.last()?.id)
        openReader(previous.id, last.id, text = false, resumePage = 1)
        database.getHistoryDao().find(previous.id)?.let { database.getHistoryDao().upsert(it.copy(percent = 1f, chaptersCount = 2)) }
        library.acknowledgeDiscoveries()
        archive(File(legacy, "Chapter 3.cbz")); library.scan()
        assertEquals(1, library.book(previous.id)?.newChapters)
        assertEquals(last.id, library.details(previous.id)?.chapters?.get(1)?.id)
        library.setDisplayOptions(false, LocalReadingFilter.COMPLETED)
        assertFalse(library.list(null).any { it.id == previous.id })
        library.setDisplayOptions(false, LocalReadingFilter.ALL)

        // An already-read EPUB keeps its old spine boundary and exact chapter ID.
        val textFile = File(root, "Old novel.epub"); epub(textFile)
        val oldNovel = LocalMangaParser(textFile).getManga(true).manga
        dataRepository.storeManga(oldNovel, replaceExisting = true)
        val oldSection = requireNotNull(oldNovel.chapters).last()
        database.getHistoryDao().upsert(HistoryEntity(oldNovel.id, now, now, oldSection.id, 0, 0f, .5f, 0, 2, now))
        touched += oldNovel.id
        library.scan()
        val retained = requireNotNull(library.details(oldNovel.id)?.chapters).last()
        assertEquals(oldSection.id, retained.id)
        val html = requireNotNull(repository.getChapterHtml(retained))
        assertTrue(html.contains("Second section")); assertFalse(html.contains("First section"))
        openReader(oldNovel.id, retained.id, text = true, resumePage = 0)
    }

    private suspend fun restart() = SmartLocalLibrary(context, documents, contentReader, dataRepository, database,
        MutableSharedFlow<LocalManga?>(extraBufferCapacity = 1)).also { it.initialize() }

    private suspend fun openReader(id: Long, chapterId: Long, text: Boolean, resumePage: Int? = null) {
        touched += id
        val manga = requireNotNull(library.details(id))
        val builder = ReaderIntent.Builder(context).manga(manga).incognito(false)
        if (resumePage == null) builder.state(ReaderState(chapterId, 0, 0))
        val intent = builder.build().intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ReaderActivity
        try {
            lateinit var model: ReaderViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[ReaderViewModel::class.java] }
            val loaded = withTimeout(30_000) { model.content.first { it.state?.chapterId == chapterId && it.pages.any { page -> page.chapterId == chapterId } } }
            val currentPage = loaded.pages.first { it.chapterId == chapterId }
            assertEquals(chapterId, loaded.state?.chapterId)
            if (resumePage != null) assertEquals(resumePage, loaded.state?.page)
            if (!text) {
                val loader = EntryPointAccessors.fromActivity(activity, ReaderDependencies::class.java).pageLoader()
                val image = loader.loadPage(currentPage.toMangaPage(), force = false)
                val file = File(requireNotNull(image.path))
                val readable = if (LocalPdfCache.isPdfPage(file)) LocalPdfCache.materializePage(file) else file
                assertNotNull("Actual Reader page must decode", BitmapFactory.decodeFile(readable.path)?.also { it.recycle() })
                // Exercise the actual content:// PageLoader path used for SAF image pages.
                val media = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "smart-local-test-${System.nanoTime()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MiyorareAcceptance")
                })!!
                try {
                    context.contentResolver.openOutputStream(media)!!.use { it.write(png()) }
                    val cached = loader.loadPage(MangaPage(System.nanoTime(), media.toString(), null, LocalMangaSource), false)
                    assertNotNull(BitmapFactory.decodeFile(cached.path)?.also { it.recycle() })
                } finally { context.contentResolver.delete(media, null, null) }
            } else {
                assertTrue(requireNotNull(model.getMangaOrNull()).url.endsWith(".epub"))
                withTimeout(15_000) {
                    while (true) {
                        var rendered = false
                        instrumentation.runOnMainSync { rendered = hasSectionText(activity.window.decorView) }
                        if (rendered) break
                        delay(100)
                    }
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
    }

    private fun hasSectionText(view: View): Boolean {
        if (view is TextView && view.text.contains("section")) return true
        return view is ViewGroup && (0 until view.childCount).any { hasSectionText(view.getChildAt(it)) }
    }

    private fun dir(name: String) = File(fixture, name).also { check(it.mkdirs()) }
    private fun png(width: Int = 16): ByteArray {
        val bitmap = Bitmap.createBitmap(width, 32, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle(); stream.toByteArray() }
    }
    private fun archive(file: File) = zip(file, mapOf("10.png" to png(), "2.png" to png()))
    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (name, data) ->
            output.putNextEntry(ZipEntry(name)); output.write(data); output.closeEntry()
        } }
    }
    private fun pdf(file: File) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(64, 96, 1).create())
            page.canvas.drawColor(android.graphics.Color.WHITE); doc.finishPage(page)
            file.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
    }
    private fun epub(file: File) = zip(file, mapOf(
        "mimetype" to "application/epub+zip".toByteArray(),
        "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>".toByteArray(),
        "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Novel</dc:title></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""".toByteArray(),
        "OPS/one.xhtml" to "<html><body>First section<img src=\"cover.png\"/></body></html>".toByteArray(),
        "OPS/two.xhtml" to "<html><body>Second section</body></html>".toByteArray(),
        "OPS/cover.png" to png(),
    ))

    @EntryPoint @InstallIn(ActivityComponent::class)
    interface ReaderDependencies { fun pageLoader(): PageLoader }
}
