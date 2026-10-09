package org.koitharu.kotatsu.local.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.pdf.PdfDocument
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        assertEquals(0, counts.materialize)
        assertEquals(1, counts.render)
        assertTrue(cache.size() > 0L)
        assertTrue(contentFiles().isEmpty())
        assertEquals(1, metrics().getInt("opens"))
        assertClosedDescriptors()
        assertEquals(1, counts.descriptors.size)

        // All relevant owners are recreated from the same persisted index/files. No in-memory
        // cache, cached PDF render, or previous reader object survives this lifecycle boundary.
        loader!!.shutdown(); loader = null
        owners(); call("fixture-metrics-reset")
        val warm = request(manga)
        assertEquals(cold.image.toBitmap().getPixel(10, 10), warm.image.toBitmap().getPixel(10, 10))
        assertEquals(1, counts.extract)
        assertEquals(0, counts.materialize)
        assertEquals(1, counts.render)
        assertEquals(0, metrics().getInt("opens"))
        assertEquals(0, metrics().getInt("queries"))
        assertTrue(contentFiles().isEmpty())
    }

    @Test fun nonSeekableAndRejectedDescriptorsUseTemporaryFallbackThenWarmReuse() = runBlocking {
        put("pipe.pdf", pdf(Color.RED)); descriptorMode("pipe.pdf", "pipe")
        put("rejected.pdf", pdf(Color.BLUE)); descriptorMode("rejected.pdf", "reject-once")
        library.scan(); call("fixture-metrics-reset")
        val books = library.state.value.books
        val cold = books.associate { it.id to library.cover(it.id)!! }
        assertEquals(2, counts.materialize)
        assertEquals(3, counts.render) // Pipe rejected by PdfRenderer; both copies render.
        assertEquals(4, metrics().getInt("opens"))
        assertEquals(1, counts.descriptors.size)
        assertClosedDescriptors()
        assertTrue(contentFiles().isEmpty())
        owners(); call("fixture-metrics-reset")
        books.forEach { assertArrayEquals(cold.getValue(it.id), library.cover(it.id)) }
        assertEquals(2, counts.extract)
        assertEquals(2, counts.materialize)
        assertEquals(3, counts.render)
        assertEquals(0, metrics().getInt("opens"))
        assertEquals(0, metrics().getInt("queries"))
    }

    @Test fun directRendererFailureClosesDescriptorBeforeSuccessfulFallback() = runBlocking {
        put("book.pdf", pdf(Color.GREEN)); library.scan()
        reader.directGate = { throw IOException("Controlled direct-access failure") }
        val bytes = library.cover(library.state.value.books.single().id)!!
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        try { assertEquals(Color.GREEN, bitmap.getPixel(10, 10)) } finally { bitmap.recycle() }
        assertEquals(1, counts.materialize)
        assertEquals(2, counts.render)
        assertClosedDescriptors()
        assertTrue(contentFiles().isEmpty())
    }

    @Test fun cancelledDirectGenerationClosesDescriptorWithoutFallbackOrPublication() = runBlocking {
        put("book.pdf", pdf(Color.RED)); library.scan()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        reader.directGate = {
            started.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "Fixture gate was not released" }
        }
        val id = library.state.value.books.single().id
        val job = async { library.cover(id) }
        try {
            kotlinx.coroutines.withTimeout(10_000) { started.await() }
            job.cancel()
        } finally { release.countDown(); job.join(); reader.directGate = null }
        assertTrue(job.isCancelled)
        assertClosedDescriptors()
        assertEquals(0, counts.materialize)
        assertEquals(0L, cache.size())
        assertTrue(contentFiles().isEmpty())
        assertNotNull(library.cover(id)) // Cancellation also released source/flight permits.
    }

    @Test fun cancelledFallbackReleasesItsTemporaryCopyAndDescriptor() = runBlocking {
        put("book.pdf", pdf(Color.RED)); descriptorMode("book.pdf", "pipe"); library.scan()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        reader.materializedGate = {
            started.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "Fixture gate was not released" }
        }
        val job = async { library.cover(library.state.value.books.single().id) }
        try {
            kotlinx.coroutines.withTimeout(10_000) { started.await() }
            assertEquals(1, contentFiles().size)
            job.cancel()
        } finally { release.countDown(); job.join(); reader.materializedGate = null }
        assertTrue(job.isCancelled)
        assertClosedDescriptors()
        assertEquals(1, counts.materialize)
        assertTrue(contentFiles().isEmpty())
        assertEquals(0L, cache.size())
    }

    @Test fun coldMixedGridAndSameKeyRequestsProgressWhileTwoPdfsAreBlocked() = runBlocking {
        repeat(3) { put("heavy$it.pdf", pdf(Color.RED)) }
        for (extension in listOf("cbz", "zip", "epub")) put("book.$extension", archive(png(Color.BLUE)))
        put("Images/001.png", png(Color.GREEN))
        put("Sidecar/book.pdf", pdf(Color.RED)); put("Sidecar/cover.png", png(Color.GREEN))
        library.scan()
        val books = library.state.value.books
        val heavyBooks = books.filter { it.node.name.startsWith("heavy") }
        val entered = java.util.concurrent.atomic.AtomicInteger()
        val twoStarted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        reader.directGate = {
            if (entered.incrementAndGet() == 2) twoStarted.complete(Unit)
            check(release.await(15, TimeUnit.SECONDS)) { "Fixture gate was not released" }
        }
        val heavy = heavyBooks.take(2).map { async { library.cover(it.id) } }
        val queued = ArrayList<kotlinx.coroutines.Deferred<ByteArray?>>()
        try {
            kotlinx.coroutines.withTimeout(10_000) { twoStarted.await() }
            queued += async { library.cover(heavyBooks.last().id) }
            repeat(8) { queued += async { library.cover(heavyBooks.first().id) } }
            val light = books.filterNot { it in heavyBooks }.map { async { library.cover(it.id) } }
            // A watchdog prevents hangs; completion is proven before the heavy gate is released.
            kotlinx.coroutines.withTimeout(10_000) { light.awaitAll().forEach { assertNotNull(it) } }
            assertEquals(2, entered.get())
            assertTrue(heavy.none { it.isCompleted })
            assertEquals(0, counts.materialize)
        } finally { release.countDown(); (heavy + queued).awaitAll(); reader.directGate = null }
        assertEquals(3, entered.get()) // Concurrent requests for heavy0 reused its single generation.
        assertEquals(3, counts.render)
        assertClosedDescriptors()
        assertTrue(contentFiles().isEmpty())
        owners(); call("fixture-metrics-reset")
        books.forEach { assertNotNull(library.cover(it.id)) }
        assertEquals(0, metrics().getInt("opens"))
        assertEquals(0, metrics().getInt("queries"))
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
        assertEquals(0, counts.materialize)
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
        descriptorMode("reader.pdf", "pipe")
        request(book.toManga(false))
        assertTrue(backing.exists())
        cache.clear()
        assertTrue(backing.exists())
        // Opening another cover releases/deletes only its own temporary copy.
        put("other.pdf", pdf(Color.BLUE)); descriptorMode("other.pdf", "pipe"); library.scan()
        request(library.state.value.books.single { it.node.name == "other.pdf" }.toManga(false))
        assertTrue(backing.exists())
        val page = File(Uri.parse(pages.single().url).path!!)
        // LocalPdfCache's pre-existing Reader page API remains responsible for lazy pages.
        assertTrue(org.koitharu.kotatsu.local.data.input.LocalPdfCache.materializePage(page).length() > 0)
        assertEquals(3, counts.materialize)
        assertClosedDescriptors()
    }

    @Test fun failedPdfRenderStillReleasesTemporaryMaterialization() = runBlocking {
        put("broken.pdf", byteArrayOf(1, 2, 3))
        library.scan()
        assertNull(library.cover(library.state.value.books.single().id))
        assertEquals(1, counts.materialize)
        assertEquals(2, counts.render)
        assertClosedDescriptors()
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
        assertEquals(0, counts.materialize)
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
        assertEquals(0, counts.materialize)
        assertEquals(2, counts.render)
    }

    @Test fun thumbnailEncodingBoundsDimensionsAndKeepsValidImage() {
        val encoded = LocalCoverThumbnail.encode(png(Color.BLUE, 2048, 1024))!!
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)!!
        try { assertEquals(512, decoded.width); assertEquals(256, decoded.height) }
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
            val source = library.state.value.books.single { it.id == manga.id }.coverPlan.candidates.first()
            val sourceDocumentId = DocumentsContract.getDocumentId(Uri.parse(source.uri))
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
            assertEquals(0L, cache.size())
            assertTrue("Oversized source ${source.key} created derived files: ${derivedFiles()}", derivedFiles().isEmpty())
            val operations = metrics()
            assertEquals(1, operations.getInt("opens"))
            assertEquals(listOf(sourceDocumentId), operations.getStringArrayList("opened-documents"))
            // Passthrough must extract again, including LocalDocuments.root()'s authorized-root
            // lookup. It is not a persistent warm hit: forbid candidate metadata/child scans,
            // and identify the only allowed query instead of conflating queries with storage.
            assertEquals(listOf("root"), operations.getStringArrayList("queried-documents"))
            assertEquals(0, operations.getStringArrayList("queried-children")!!.size)
            assertEquals(operations.getStringArrayList("queried-documents")!!.size, operations.getInt("queries"))
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
            val source = BitmapFactory.decodeByteArray(asset("static.${manga.title}"), 0, asset("static.${manga.title}").size)!!
            val pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            source.recycle()
            if (pixels.any { (it ushr 24) != 255 }) assertPng(derived) else assertJpeg(derived)
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

    @Test fun opaqueAndTransparentRecipePreservesContentAndBoundsWithoutFlatteningAlpha() {
        val opaque = LocalCoverThumbnail.encode(png(Color.BLUE, 2048, 1024))!!
        assertJpeg(opaque)
        val decoded = BitmapFactory.decodeByteArray(opaque, 0, opaque.size)!!
        try {
            assertEquals(512, decoded.width); assertEquals(256, decoded.height)
            assertTrue(Color.blue(decoded.getPixel(100, 100)) > 245)
            assertTrue(Color.red(decoded.getPixel(100, 100)) < 10)
        } finally { decoded.recycle() }
        val alpha = LocalCoverThumbnail.encode(png(0x6600ff00, 2048, 1024))!!
        assertPng(alpha)
        val transparent = BitmapFactory.decodeByteArray(alpha, 0, alpha.size)!!
        try { assertEquals(0x66, Color.alpha(transparent.getPixel(100, 100))) }
        finally { transparent.recycle() }
    }

    @Test fun clearBypassesCoilMemoryAndKeepsRemotePresentationReaderAndIndexDomains() = runBlocking {
        put("book.pdf", pdf(Color.RED)); library.scan()
        val manga = library.state.value.books.single().toManga(false)
        val cold = request(manga)
        request(manga)
        assertEquals(1, counts.extract)
        val memory = loader!!.memoryCache!!
        val beforeEntries = memory.keys.toSet()
        val index = library.state.value
        val outside = File(context.cacheDir, "unrelated-cache/keep").apply { parentFile!!.mkdirs(); writeText("keep") }
        library.clearCoverCache()
        assertEquals(beforeEntries, memory.keys.toSet()) // No global memory clear.
        assertSame(index, library.state.value)
        assertEquals("keep", outside.readText())
        assertEquals(0, cache.stats().entries)
        val fresh = request(manga)
        assertNotEquals(cold.memoryCacheKey, fresh.memoryCacheKey)
        assertEquals(2, counts.extract)
        request(manga)
        assertEquals(2, counts.extract)
        assertEquals(1L, cache.diagnostics.snapshot().count(SmartLocalCoverDiagnostics.Reason.CLEAR))
    }

    @Test fun pdfStageMeasurementsIdentifyDirectWarmAndPresentationMemoryWork() = runBlocking {
        put("book.pdf", pdf(Color.RED)); library.scan()
        val manga = library.state.value.books.single().toManga(false)
        request(manga); request(manga)
        val snapshot = cache.diagnostics.snapshot()
        for (reason in listOf(SmartLocalCoverDiagnostics.Reason.SOURCE_OPEN, SmartLocalCoverDiagnostics.Reason.PDF_DESCRIPTOR_OPEN,
            SmartLocalCoverDiagnostics.Reason.PDF_RENDERER_OPEN, SmartLocalCoverDiagnostics.Reason.PDF_RENDER,
            SmartLocalCoverDiagnostics.Reason.ENCODE, SmartLocalCoverDiagnostics.Reason.GENERATED, SmartLocalCoverDiagnostics.Reason.PUBLISHED)) {
            assertEquals("Missing or duplicate $reason", 1L, snapshot.count(reason))
        }
        assertEquals(1L, snapshot.count(SmartLocalCoverDiagnostics.Reason.PRESENTATION_MEMORY_HIT))
        loader!!.memoryCache!!.clear(); call("fixture-metrics-reset"); request(manga)
        assertEquals(1L, cache.diagnostics.snapshot().count(SmartLocalCoverDiagnostics.Reason.CACHE_HIT))
        assertEquals(0, metrics().getInt("opens"))
        assertEquals(0, metrics().getInt("queries"))
    }

    @Test fun recipeSurveyMeasuresLegacyAndNewBytesAndEncodingWithoutDeviceClaims() {
        val oldSizes = ArrayList<Int>(); val newSizes = ArrayList<Int>()
        var oldNanos = 0L; var newNanos = 0L
        repeat(12) { seed ->
            val bitmap = texturedBitmap(1024, 1536, seed)
            val input = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val old = Bitmap.createScaledBitmap(bitmap, 512, 768, true)
            bitmap.recycle()
            try {
                var started = System.nanoTime()
                val oldBytes = ByteArrayOutputStream().also { old.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                oldNanos += System.nanoTime() - started
                started = System.nanoTime()
                val newBytes = LocalCoverThumbnail.encode(input)!!
                newNanos += System.nanoTime() - started
                oldSizes += oldBytes.size; newSizes += newBytes.size
                assertJpeg(newBytes)
            } finally { old.recycle() }
        }
        oldSizes.sort(); newSizes.sort()
        assertTrue("Representation bytes did not fall", newSizes[6] < oldSizes[6] / 2)
        writeSurvey("recipe", JSONObject().put("dataset", "SYNTHETIC_12_TEXTURED_IMAGES").put("legacyRecipe", "768PNG100")
            .put("recipe", LocalCoverRecipe.IDENTITY).put("samples", 12).put("legacyMedianBytes", oldSizes[6]).put("medianBytes", newSizes[6])
            .put("legacyP95Bytes", oldSizes.last()).put("p95Bytes", newSizes.last()).put("legacyEncodeNanos", oldNanos)
            .put("decodeResizeEncodeNanos", newNanos))
        android.util.Log.i("SmartLocalCoverSurvey", "SYNTHETIC recipe=768PNG->512JPEG82 n=12 median=${oldSizes[6]}->${newSizes[6]} p95=${oldSizes.last()}->${newSizes.last()} oldEncodeNs=$oldNanos newDecodeResizeEncodeNs=$newNanos")
    }

    @Test fun mixed350TitleColdRevisitRestartAndUnchangedRefreshNeverThrash(): Unit = runBlocking {
        val bitmap = texturedBitmap(256, 384, 17)
        val image = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { bitmap.recycle() }
        val pdf = pdf(Color.BLUE); val archive = archive(image)
        repeat(350) { index ->
            when (index % 3) {
                0 -> put("pdf-$index.pdf", pdf)
                1 -> put("archive-$index.cbz", archive)
                else -> put("image-$index/001.png", image)
            }
        }
        library.scan(); call("fixture-metrics-reset")
        val books = library.state.value.books
        assertEquals(350, books.size)
        val start = System.nanoTime()
        books.chunked(6).forEach { chunk -> chunk.map { book -> async { assertNotNull(library.cover(book.id)) } }.awaitAll() }
        val coldNanos = System.nanoTime() - start
        val report = cache.report()
        assertEquals(350, report.storage.entries)
        assertEquals(350, counts.extract)
        assertEquals(0L, report.diagnostics.count(SmartLocalCoverDiagnostics.Reason.EVICT_BYTES))
        assertEquals(0L, report.diagnostics.count(SmartLocalCoverDiagnostics.Reason.EVICT_ENTRY_COUNT))
        assertTrue(contentFiles().isEmpty())
        repeat(3) { books.forEach { assertNotNull(library.cover(it.id)) } }
        assertEquals(350, counts.extract)
        owners(); call("fixture-metrics-reset")
        books.forEach { assertNotNull(library.cover(it.id)) }
        assertEquals(0, metrics().getInt("opens")); assertEquals(0, metrics().getInt("queries"))
        library.scan(); call("fixture-metrics-reset")
        library.state.value.books.forEach { assertNotNull(library.cover(it.id)) }
        assertEquals(350, counts.extract)
        assertEquals(0, metrics().getInt("opens")); assertEquals(0, metrics().getInt("queries"))
        writeSurvey("mixed350", JSONObject().put("dataset", "SYNTHETIC_MIXED_350").put("recipe", LocalCoverRecipe.IDENTITY)
            .put("coldNanos", coldNanos).put("entries", report.storage.entries).put("bytes", report.storage.bytes)
            .put("medianEntryBytes", report.storage.medianEntryBytes).put("p90EntryBytes", report.storage.p90EntryBytes)
            .put("p95EntryBytes", report.storage.p95EntryBytes).put("projected100Bytes", report.storage.projectedP95Bytes(100))
            .put("projected350Bytes", report.storage.projectedP95Bytes(350)).put("projected1000Bytes", report.storage.projectedP95Bytes(1000))
            .put("generationCount", report.diagnostics.count(SmartLocalCoverDiagnostics.Reason.GENERATED))
            .put("byteEvictions", report.diagnostics.count(SmartLocalCoverDiagnostics.Reason.EVICT_BYTES))
            .put("countEvictions", report.diagnostics.count(SmartLocalCoverDiagnostics.Reason.EVICT_ENTRY_COUNT))
            .put("revisitRestartRefreshSourceOpens", metrics().getInt("opens")))
        android.util.Log.i("SmartLocalCoverSurvey", "SYNTHETIC mixed350 coldNs=$coldNanos bytes=${report.storage.bytes} median=${report.storage.medianEntryBytes} p90=${report.storage.p90EntryBytes} p95=${report.storage.p95EntryBytes} projected100=${report.storage.projectedP95Bytes(100)} projected350=${report.storage.projectedP95Bytes(350)} projected1000=${report.storage.projectedP95Bytes(1000)}")
    }

    @Test fun revokedProviderPermissionFailsColdWithoutPublicationOrReaderArtifacts() = runBlocking {
        put("book.pdf", pdf(Color.RED)); library.scan()
        val book = library.state.value.books.single()
        assertNotNull(library.cover(book.id))
        cache.clear()
        fixtureGrant(true)
        try {
            assertTrue(runCatching { library.cover(book.id) }.isFailure)
            assertEquals(0, cache.stats().entries)
            assertTrue(contentFiles().isEmpty())
            assertClosedDescriptors()
        } finally { fixtureGrant(false) }
    }

    @Test fun exifRotationIsAppliedBeforeTheStableGridRecipe() {
        val input = Bitmap.createBitmap(160, 320, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "rotation.jpg")
        try {
            input.eraseColor(Color.BLUE)
            file.outputStream().use { input.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            android.media.ExifInterface(file.absolutePath).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val encoded = LocalCoverThumbnail.encode(file.readBytes())!!
            assertJpeg(encoded)
            val rotated = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)!!
            try { assertEquals(320, rotated.width); assertEquals(160, rotated.height) }
            finally { rotated.recycle() }
        } finally { input.recycle(); file.delete() }
    }

    // Kept outside the per-test FixtureContext, so the existing CI job can collect exact-head
    // measurement evidence after teardown. Contains fixture numbers only, not user source paths.
    private fun writeSurvey(name: String, report: JSONObject) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.filesDir, "on-device-cover-survey").apply { check(isDirectory || mkdirs()) }
        File(directory, "$name.json").writeText(report.toString())
    }

    private fun assertJpeg(bytes: ByteArray) = assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), bytes.copyOf(3))
    private fun assertPng(bytes: ByteArray) = assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), bytes.copyOf(4))
    private fun texturedBitmap(width: Int, height: Int, seed: Int): Bitmap {
        val random = java.util.Random(seed.toLong())
        val pixels = IntArray(width * height) { index ->
            val noise = random.nextInt(31)
            Color.rgb(((index % width) * 255 / width + noise).coerceAtMost(255),
                ((index / width) * 255 / height + noise).coerceAtMost(255), (seed * 17 + noise) and 255)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
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
        val diagnostics = SmartLocalCoverDiagnostics()
        reader = CountingReader(context, documents, counts, diagnostics)
        cache = SmartLocalCoverCache(context, diagnostics)
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

    private fun descriptorMode(name: String, mode: String) = call("fixture-descriptor-mode", name,
        Bundle().apply { putString("mode", mode) })
    private fun assertClosedDescriptors() {
        counts.descriptors.forEach { descriptor ->
            assertFalse("Source descriptor leaked", runCatching { descriptor.fileDescriptor.valid() }.getOrDefault(false))
        }
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

    private class Counts {
        private val extractions = java.util.concurrent.atomic.AtomicInteger()
        private val materializations = java.util.concurrent.atomic.AtomicInteger()
        private val renders = java.util.concurrent.atomic.AtomicInteger()
        val extract get() = extractions.get()
        val materialize get() = materializations.get()
        val render get() = renders.get()
        fun extracted() { extractions.incrementAndGet() }
        fun materialized() { materializations.incrementAndGet() }
        fun rendered() { renders.incrementAndGet() }
        val descriptors = java.util.Collections.synchronizedList(ArrayList<ParcelFileDescriptor>())
    }
    private class CountingReader(context: Context, documents: LocalDocuments, val counts: Counts, diagnostics: SmartLocalCoverDiagnostics) : LocalContentReader(context, documents, diagnostics) {
        var directGate: (() -> Unit)? = null
        var materializedGate: (() -> Unit)? = null
        internal override suspend fun cover(plan: LocalCoverPlan, publish: suspend (GeneratedLocalCover) -> ByteArray): ByteArray? {
            counts.extracted(); return super.cover(plan, publish)
        }
        override suspend fun materialize(root: Node, node: Node): File {
            counts.materialized(); return super.materialize(root, node)
        }
        override fun renderPdfCover(file: File): ByteArray? { counts.rendered(); materializedGate?.invoke(); return super.renderPdfCover(file) }
        override fun renderPdfCover(descriptor: ParcelFileDescriptor): ByteArray? {
            counts.descriptors.add(descriptor); counts.rendered(); directGate?.invoke()
            return super.renderPdfCover(descriptor)
        }
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

