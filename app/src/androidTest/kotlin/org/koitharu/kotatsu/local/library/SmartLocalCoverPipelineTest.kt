package org.koitharu.kotatsu.local.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.asDrawable
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.ScaleDrawable
import coil3.toBitmap
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import okhttp3.internal.platform.PlatformRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.image.MiyorareImageDiskCache
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Provider

/** Executes real ImageRequests, domain owners, SAF copies, PdfRenderer, and encoded thumbnails. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SmartLocalCoverPipelineTest {
    @get:Rule var hilt = HiltAndroidRule(this)
    @Inject lateinit var repository: MangaDataRepository
    @Inject lateinit var database: MangaDatabase
    private lateinit var context: FixtureContext
    private lateinit var library: SmartLocalLibrary
    private lateinit var reader: CountingReader
    private lateinit var cache: SmartLocalCoverCache
    private var loader: ImageLoader? = null
    private var beforePresentation: (suspend () -> Unit)? = null
    private var previousPdfContext: Context? = null
    private val counts = Counts()
    private val readerPageDirectories = HashSet<File>()
    private val resolver get() = context.contentResolver
    private val provider = Uri.parse("content://org.noirero.miyorare.test.cover-fixtures")
    private val rootUri = DocumentsContract.buildTreeDocumentUri(provider.authority, "root").toString()

    @Before fun setUp() {
        hilt.inject()
        context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        previousPdfContext = PlatformRegistry.applicationContext
        PlatformRegistry.applicationContext = context
        fixtureGrant(false)
        call("fixture-reset")
        context.getSharedPreferences("smart_local_library", Context.MODE_PRIVATE).edit().putString("roots",
            JSONArray().put(JSONObject().put("uri", rootUri).put("name", "Fixture")).toString()).commit()
        owners()
    }

    @After fun tearDown() {
        loader?.shutdown()
        call("fixture-reset")
        context.getSharedPreferences("smart_local_library", Context.MODE_PRIVATE).edit().clear().commit()
        readerPageDirectories.forEach { it.deleteRecursively() }
        context.storage.deleteRecursively()
        PlatformRegistry.applicationContext = previousPdfContext
        fixtureGrant(true)
    }

    @Test fun coldPdfRecreateOwnersWarmHitNeverExtractsMaterializesOrRendersAgain() = runBlocking {
        put("book.pdf", pdf(Color.RED), padding = 1024 * 1024)
        library.scan()
        val manga = library.state.value.books.single().toManga(false)
        val cold = request(manga)
        assertEquals(1, counts.extract)
        assertEquals(1, counts.materialize)
        assertEquals(1, counts.render)
        assertTrue(cache.size() > 0L)
        assertTrue(contentFiles().isEmpty())
        assertEquals(1, metrics().getInt("opens"))

        // All relevant owners are recreated from the same persisted index/files. No in-memory
        // cache, cached PDF render, or previous reader object survives this lifecycle boundary.
        loader!!.shutdown(); loader = null
        owners(); call("fixture-metrics-reset")
        val warm = request(manga)
        assertEquals(cold.image.toBitmap().getPixel(10, 10), warm.image.toBitmap().getPixel(10, 10))
        assertEquals(1, counts.extract)
        assertEquals(1, counts.materialize)
        assertEquals(1, counts.render)
        assertEquals(0, metrics().getInt("opens"))
        assertEquals(0, metrics().getInt("queries"))
        assertTrue(contentFiles().isEmpty())
    }

    @Test fun refreshChangesOnlyRelatedSourceAndBypassesStaleCoilMemoryAndDerivedDisk() = runBlocking {
        put("first.pdf", pdf(Color.RED)); put("second.pdf", pdf(Color.BLUE))
        library.scan()
        val mangas = library.state.value.books.associate { it.node.name to it.toManga(false) }
        val first = mangas.getValue("first.pdf")
        val second = mangas.getValue("second.pdf")
        val old = request(first); request(second)
        assertEquals(2, counts.extract)
        put("first.pdf", pdf(Color.GREEN))
        library.scan()
        val updated = library.state.value.books.single { it.node.name == "first.pdf" }.toManga(false)
        assertNotEquals(first.coverUrl, updated.coverUrl) // Existing adapter cells receive a changed model.
        assertEquals(first.id, updated.id)
        assertEquals(first.url, updated.url)
        assertEquals(first.publicUrl, updated.publicUrl)
        val fresh = request(first) // Even a caller holding the old transport URL must use the new version.
        assertNotEquals(old.memoryCacheKey, fresh.memoryCacheKey)
        assertNotEquals(old.image.toBitmap().getPixel(10, 10), fresh.image.toBitmap().getPixel(10, 10))
        request(second)
        assertEquals(3, counts.extract)
        assertEquals(3, counts.materialize)
        assertEquals(3, counts.render)
        loader!!.memoryCache!!.clear()
        request(first); request(second)
        assertEquals(3, counts.extract)
    }

    @Test fun coverReleaseAndThumbnailClearPreserveRealReaderBackingAndLazyPages() = runBlocking {
        put("reader.pdf", pdf(Color.RED))
        library.scan()
        val book = library.state.value.books.single()
        val chapter = book.chapters.single()
        val original = book.toManga(false, true).chapters!!.single()
        val documents = LocalDocuments(context)
        val root = documents.root(rootUri)
        val pages = reader.pages(root, chapter, original)
        readerPageDirectories += File(Uri.parse(pages.first().url).path!!).parentFile!!
        val backing = contentFiles().single()
        request(book.toManga(false))
        assertTrue(backing.exists())
        cache.clear()
        assertTrue(backing.exists())
        // Opening another cover releases/deletes only its own temporary copy.
        put("other.pdf", pdf(Color.BLUE)); library.scan()
        request(library.state.value.books.single { it.node.name == "other.pdf" }.toManga(false))
        assertTrue(backing.exists())
        val page = File(Uri.parse(pages.single().url).path!!)
        // LocalPdfCache's pre-existing Reader page API remains responsible for lazy pages.
        assertTrue(org.koitharu.kotatsu.local.data.input.LocalPdfCache.materializePage(page).length() > 0)
    }

    @Test fun failedPdfRenderStillReleasesTemporaryMaterialization() = runBlocking {
        put("broken.pdf", byteArrayOf(1, 2, 3))
        library.scan()
        assertNull(library.cover(library.state.value.books.single().id))
        assertEquals(1, counts.materialize)
        assertEquals(1, counts.render)
        assertTrue(contentFiles().isEmpty())
        assertEquals(0L, cache.size())
    }

    @Test fun manyPdfCoversDoNotRetainFullSourcesAndWarmOpenDoesNoSafWork() = runBlocking {
        repeat(12) { put("$it.pdf", pdf(Color.RED), padding = 1024 * 1024) }
        library.scan()
        val mangas = library.state.value.books.map { it.toManga(false) }
        for (manga in mangas) request(manga)
        assertEquals(12, counts.extract)
        assertTrue(contentFiles().isEmpty())
        assertTrue(cache.size() < 1024 * 1024)
        loader!!.shutdown(); loader = null; owners(); call("fixture-metrics-reset")
        for (manga in mangas) request(manga)
        assertEquals(12, counts.extract)
        assertEquals(0, metrics().getInt("queries"))
        assertEquals(0, metrics().getInt("opens"))
    }

    @Test fun archiveSidecarAndDirectCoversKeepBoundedExtractionAndRestartReuse() = runBlocking {
        val image = png(Color.BLUE)
        for (extension in listOf("cbz", "zip", "epub")) put("book.$extension", archive(image))
        put("Images/001.png", image)
        put("Sidecar/book.pdf", pdf(Color.RED))
        put("Sidecar/cover.png", png(Color.GREEN))
        library.scan()
        assertEquals(5, library.state.value.books.size)
        val mangas = library.state.value.books.map { it.toManga(false) }
        mangas.forEach { request(it) }
        assertEquals(5, counts.extract)
        assertEquals(0, counts.materialize)
        assertEquals(0, counts.render) // Explicit sidecar wins over the PDF.
        assertTrue(contentFiles().isEmpty())
        loader!!.shutdown(); loader = null; owners(); call("fixture-metrics-reset")
        mangas.forEach { request(it) }
        assertEquals(5, counts.extract)
        assertEquals(0, metrics().getInt("opens"))
        val sidecar = library.state.value.books.single { it.node.name == "Sidecar" }.toManga(false)
        val old = request(sidecar)
        put("Sidecar/cover.png", png(Color.YELLOW)); library.scan()
        val changed = request(sidecar)
        assertNotEquals(old.memoryCacheKey, changed.memoryCacheKey)
        assertNotEquals(old.image.toBitmap().getPixel(10, 10), changed.image.toBitmap().getPixel(10, 10))
        assertEquals(6, counts.extract)
        assertEquals(0, counts.materialize)
    }

    @Test fun indexed350TitlesResolvePresentationVersionsWithoutPerBindSafQueries() = runBlocking {
        val source = pdf(Color.RED)
        repeat(350) { put("title-$it.pdf", source) }
        library.scan()
        val ids = library.state.value.books.map { it.id }
        assertEquals(350, ids.size)
        owners(); library.initialize(); call("fixture-metrics-reset")
        repeat(3) { for (id in ids) assertNotNull(library.coverFingerprint(id)) }
        assertEquals(0, counts.extract)
        assertEquals(0, metrics().getInt("queries"))
        assertEquals(0, metrics().getInt("opens"))
    }

    @Test fun userCoverOverrideKeepsIndependentPresentationIdentityAndSkipsSourceGeneration() = runBlocking {
        put("book.pdf", pdf(Color.RED)); library.scan()
        val manga = library.state.value.books.single().toManga(false)
        request(manga)
        val customCover = File(context.filesDir, "user-cover.png").apply { writeBytes(png(Color.GREEN)) }
        val url = Uri.fromFile(customCover).toString()
        val first = request(manga, url)
        assertNull(first.request.diskCacheKey)
        assertEquals(1, counts.extract)
        put("book.pdf", pdf(Color.BLUE)); library.scan()
        val warm = request(manga, url)
        assertEquals(first.memoryCacheKey, warm.memoryCacheKey)
        assertEquals(first.image.toBitmap().getPixel(10, 10), warm.image.toBitmap().getPixel(10, 10))
        assertEquals(1, counts.extract)
        assertEquals(1, counts.materialize)
        assertEquals(1, counts.render)
    }

    @Test fun refreshRacingCoilMemoryHitRetriesBeforeReturningSupersededCover() = runBlocking {
        put("book.pdf", pdf(Color.RED)); library.scan()
        val manga = library.state.value.books.single().toManga(false)
        val old = request(manga)
        beforePresentation = {
            beforePresentation = null
            put("book.pdf", pdf(Color.BLUE)); library.scan()
        }
        val fresh = request(manga)
        assertNotEquals(old.memoryCacheKey, fresh.memoryCacheKey)
        assertNotEquals(old.image.toBitmap().getPixel(10, 10), fresh.image.toBitmap().getPixel(10, 10))
        assertEquals(2, counts.extract)
        assertEquals(2, counts.materialize)
        assertEquals(2, counts.render)
    }

    @Test fun thumbnailEncodingBoundsDimensionsAndKeepsValidImage() {
        val encoded = LocalCoverThumbnail.encode(png(Color.BLUE, 2048, 1024))!!
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)!!
        try { assertEquals(768, decoded.width); assertEquals(384, decoded.height) }
        finally { decoded.recycle() }
    }

    @Test fun boundedGifAndWebpStayAnimatedAndReuseEncodedPayloadAfterOwnerRecreation() = runBlocking {
        val originals = listOf("gif", "webp").associateWith { asset("animated.$it") }
        originals.forEach { (format, bytes) -> put("$format/001.$format", bytes) }
        library.scan()
        val mangas = library.state.value.books.map { it.toManga(false) }
        assertEquals(2, mangas.size)
        for (manga in mangas) {
            assertArrayEquals(originals.getValue(manga.title), library.cover(manga.id))
            assertAnimated(request(manga))
        }
        assertEquals(2, counts.extract)
        assertEquals(0, counts.materialize)
        assertTrue(cache.size() in 1..SmartLocalCoverCache.MAX_BYTES)
        assertEquals(2, derivedFiles().size)
        loader!!.shutdown(); loader = null; owners(); call("fixture-metrics-reset")
        for (manga in mangas) {
            assertAnimated(request(manga))
            assertArrayEquals(originals.getValue(manga.title), library.cover(manga.id))
        }
        assertEquals(2, counts.extract)
        assertEquals(0, metrics().getInt("opens"))
        assertEquals(0, metrics().getInt("queries"))
    }

    @Test fun animatedArchiveAndSidecarCoversPreserveOriginalFramesAndRestartReuse() = runBlocking {
        val gif = asset("animated.gif")
        val webp = asset("animated.webp")
        for (extension in listOf("cbz", "zip", "epub")) put("book.$extension", archive(gif, "cover.gif"))
        put("Sidecar/book.pdf", pdf(Color.RED)); put("Sidecar/cover.webp", webp)
        library.scan()
        val books = library.state.value.books
        assertEquals(4, books.size)
        for (book in books) {
            assertArrayEquals(if (book.node.name == "Sidecar") webp else gif, library.cover(book.id))
            assertAnimated(request(book.toManga(false)))
        }
        assertEquals(4, counts.extract)
        assertEquals(0, counts.materialize)
        assertEquals(0, counts.render)
        loader!!.shutdown(); loader = null; owners(); call("fixture-metrics-reset")
        books.forEach { assertAnimated(request(it.toManga(false))) }
        assertEquals(4, counts.extract)
        assertEquals(0, metrics().getInt("opens"))
        assertEquals(0, metrics().getInt("queries"))
    }

    @Test fun oversizedGifAndWebpRemainAnimatedPassthroughAndNeverEnterDerivedStorage() = runBlocking {
        for (format in listOf("gif", "webp")) putOversizedAnimation("$format/001.$format", format)
        library.scan()
        val mangas = library.state.value.books.map { it.toManga(false) }
        assertEquals(2, mangas.size)
        var extractions = 0
        for (manga in mangas) {
            val payload = library.cover(manga.id)!!
            assertTrue(payload.size > SmartLocalCoverCache.MAX_THUMBNAIL_BYTES)
            assertTrue(payload.size <= BoundedArchiveCoverReader.MAX_CANDIDATE_BYTES)
            assertEquals(true, LocalCoverThumbnail.animation(payload))
            assertAnimated(request(manga))
            assertEquals(0L, cache.size())
            assertTrue(derivedFiles().isEmpty())
            loader!!.shutdown(); loader = null; owners(); call("fixture-metrics-reset")
            assertAnimated(request(manga))
            extractions += 3
            assertEquals(extractions, counts.extract)
            assertEquals(1, metrics().getInt("opens"))
            assertEquals(0, metrics().getInt("queries"))
            assertEquals(0L, cache.size())
            assertTrue(derivedFiles().isEmpty())
        }
        assertEquals(0, counts.materialize)
        assertEquals(0, counts.render)
    }

    @Test fun animationAboveThumbnailDimensionsUsesUnmodifiedPassthrough() = runBlocking {
        val original = asset("wide.gif")
        assertTrue(original.size < SmartLocalCoverCache.MAX_THUMBNAIL_BYTES)
        put("Wide/001.gif", original); library.scan()
        val manga = library.state.value.books.single().toManga(false)
        assertArrayEquals(original, library.cover(manga.id))
        assertAnimated(request(manga))
        assertEquals(0L, cache.size())
        loader!!.shutdown(); loader = null; owners()
        assertAnimated(request(manga))
        assertEquals(3, counts.extract)
        assertEquals(0L, cache.size())
    }

    @Test fun singleFrameGifAndStaticWebpUsePersistentStaticThumbnails() = runBlocking {
        for (format in listOf("gif", "webp")) {
            val source = asset("static.$format")
            assertEquals(false, LocalCoverThumbnail.animation(source))
            put("$format/001.$format", source)
        }
        library.scan()
        val mangas = library.state.value.books.map { it.toManga(false) }
        assertEquals(2, mangas.size)
        for (manga in mangas) {
            val derived = library.cover(manga.id)!!
            assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), derived.copyOf(4))
            assertFalse(request(manga).image.asDrawable(context.resources) is Animatable)
        }
        assertEquals(2, counts.extract)
        assertEquals(2, derivedFiles().size)
        loader!!.shutdown(); loader = null; owners(); call("fixture-metrics-reset")
        mangas.forEach { assertFalse(request(it).image.asDrawable(context.resources) is Animatable) }
        assertEquals(2, counts.extract)
        assertEquals(0, metrics().getInt("opens"))
        assertEquals(0, metrics().getInt("queries"))
    }

    @Test fun refreshedAnimationCanBecomeStaticWithoutReusingOldEncodedOrMemoryCover() = runBlocking {
        put("Title/001.gif", asset("animated.gif")); library.scan()
        val manga = library.state.value.books.single().toManga(false)
        val animated = request(manga)
        assertAnimated(animated)
        put("Title/001.gif", asset("static.gif")); library.scan()
        val static = request(manga)
        assertFalse(static.image.asDrawable(context.resources) is Animatable)
        assertNotEquals(animated.memoryCacheKey, static.memoryCacheKey)
        assertEquals(2, counts.extract)
    }

    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("smart-local-covers/$name").use { it.readBytes() }

    private fun assertAnimated(result: SuccessResult) {
        val drawable = result.image.asDrawable(context.resources)
        assertTrue("Cover was flattened: ${result.image}", drawable is Animatable)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val native = (drawable as? ScaleDrawable)?.child ?: drawable
            assertTrue("Expected Android's multi-frame drawable: $native", native is AnimatedImageDrawable)
        }
    }

    private fun derivedFiles() = File(context.filesDir, "smart-local-covers").listFiles().orEmpty().toList()

    private fun putOversizedAnimation(name: String, format: String) {
        val original = asset("animated.$format")
        val block: ByteArray
        val repeats: Int
        val prefix: ByteArray
        val suffix: ByteArray
        if (format == "gif") {
            // Insert a valid Comment Extension before the trailer; preserve both image frames.
            prefix = original.copyOf(original.size - 1) + byteArrayOf(0x21, 0xfe.toByte())
            block = byteArrayOf(255.toByte()) + ByteArray(255) { 'x'.code.toByte() }
            repeats = SmartLocalCoverCache.MAX_THUMBNAIL_BYTES / block.size + 1
            suffix = byteArrayOf(0, 0x3b)
        } else {
            // Append a valid even-sized unknown RIFF chunk and update the container length.
            block = ByteArray(8192) { 'x'.code.toByte() }
            repeats = SmartLocalCoverCache.MAX_THUMBNAIL_BYTES / block.size
            prefix = original + "JUNK".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(block.size * repeats).array()
            ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).putInt(4, prefix.size + block.size * repeats - 8)
            suffix = byteArrayOf()
        }
        call("fixture-put", name, Bundle().apply {
            putByteArray("bytes", prefix); putByteArray("repeat-block", block)
            putInt("repeat-count", repeats); putByteArray("suffix", suffix)
        })
    }

    private fun owners() {
        val documents = LocalDocuments(context)
        reader = CountingReader(context, documents, counts)
        cache = SmartLocalCoverCache(context)
        library = SmartLocalLibrary(context, documents, reader, repository, database, cache)
    }

    private suspend fun request(manga: Manga, coverUrl: String? = manga.coverUrl): SuccessResult {
        val imageLoader = loader ?: ImageLoader.Builder(context).diskCache { MiyorareImageDiskCache(context) }
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
                else add(GifDecoder.Factory())
                add(LocalCoverFetcher.Factory(Provider { library }))
                add(LocalCoverVersionInterceptor(Provider { library }))
                add(Interceptor { chain -> beforePresentation?.invoke(); chain.proceed() })
            }.build().also { loader = it }
        val result = imageLoader.execute(ImageRequest.Builder(context).data(coverUrl).mangaExtra(manga)
            .stableMangaCoverKey(manga, coverUrl).size(128, 192).allowHardware(false).build())
        assertTrue("ImageRequest failed: $result", result is SuccessResult)
        return result as SuccessResult
    }

    private fun contentFiles() = File(context.cacheDir, "smart-local-content").listFiles().orEmpty().toList()
    private fun metrics() = call("fixture-counts")!!
    private fun call(method: String, arg: String? = null, bundle: Bundle? = null) = resolver.call(Uri.parse(rootUri), method, arg, bundle)
    private fun fixtureGrant(revoke: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = "${instrumentation.context.packageName}/${CoverFixtureGrantActivity::class.java.name}"
        val command = "am start -W -n $component --es recipient ${context.packageName} --ez revoke $revoke"
        val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        check(!output.contains("Error:")) { output }
    }
    private fun put(name: String, bytes: ByteArray, padding: Int = 0) = call("fixture-put", name,
        Bundle().apply { putByteArray("bytes", bytes); putInt("padding", padding) })
    private fun pdf(color: Int): ByteArray {
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 450, 1).create())
            page.canvas.drawColor(color); pdf.finishPage(page)
            return ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray()
        } finally { pdf.close() }
    }
    private fun png(color: Int, width: Int = 100, height: Int = 150): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private fun archive(image: ByteArray, name: String = "cover.png"): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(image); zip.closeEntry() }
    }.toByteArray()

    private class Counts { var extract = 0; var materialize = 0; var render = 0 }
    private class CountingReader(context: Context, documents: LocalDocuments, val counts: Counts) : LocalContentReader(context, documents) {
        internal override suspend fun cover(root: Node, plan: LocalCoverPlan): GeneratedLocalCover? {
            counts.extract++; return super.cover(root, plan)
        }
        override suspend fun materialize(root: Node, node: Node): File {
            counts.materialize++; return super.materialize(root, node)
        }
        override fun renderPdfCover(file: File): ByteArray? { counts.render++; return super.renderPdfCover(file) }
    }
    private class FixtureContext(base: Context) : ContextWrapper(base) {
        val storage = File(base.cacheDir, "derived-cover-test-${UUID.randomUUID()}").apply { mkdirs() }
        private val suffix = storage.name
        override fun getFilesDir() = File(storage, "files").apply { mkdirs() }
        override fun getCacheDir() = File(storage, "cache").apply { mkdirs() }
        override fun getExternalCacheDir() = cacheDir
        override fun getExternalFilesDir(type: String?) = File(filesDir, type ?: "external").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$name-$suffix", mode)
    }
}
