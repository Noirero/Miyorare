package org.koitharu.kotatsu.local.library

import android.content.ContentResolver
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.core.net.toFile
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.util.ext.toZipUri
import org.koitharu.kotatsu.local.data.input.EpubParser
import org.koitharu.kotatsu.local.data.input.LocalPdfCache
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import org.koitharu.kotatsu.local.library.SmartLocalCoverDiagnostics.Event
import org.koitharu.kotatsu.local.library.SmartLocalCoverDiagnostics.Reason

/** Reuses archive/PDF/EPUB backends. Seekable copies are lazy cache artifacts, never user imports. */
@Singleton
open class LocalContentReader @Inject constructor(
    @ApplicationContext private val context: Context, private val documents: LocalDocuments,
    private val diagnostics: SmartLocalCoverDiagnostics = SmartLocalCoverDiagnostics(),
) {
    private val pdfMeasurement = ThreadLocal<CoverMeasurement?>()
    private val cacheMutex = Mutex()
    private val coverWork = LocalCoverWorkScheduler()
    // Returned archive URIs remain in Reader state. Pin them for this process so cache
    // maintenance cannot invalidate a still-open/previous chapter. Temporary cover users acquire
    // their own reference and release it as soon as extraction finishes.
    private val activeFiles = MaterializedCachePins()
    private val metadataCache = android.util.LruCache<String, LocalMetadata>(32)
    private val cacheDir get() = File(context.cacheDir, "smart-local-content").also { it.mkdirs() }

    open suspend fun materialize(root: Node, node: Node): File = withContext(Dispatchers.IO) {
        check(documents.contains(root, node)) { "Content outside selected root" }
        val uri = node.uri.toUri()
        if (uri.scheme == ContentResolver.SCHEME_FILE) return@withContext uri.toFile()
        cacheMutex.withLock {
            val key = MessageDigest.getInstance("SHA-256").digest("${node.key}:${node.modified}:${node.size}".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val target = File(cacheDir, "$key.${LocalTreeScanner.extension(node.name)}")
            if (!target.isFile) {
                val temporary = File.createTempFile("content-", ".partial", cacheDir)
                try {
                    documents.input(node).also { pdfMeasurement.get()?.let { sourceOpened(it.plan, it.index) } }.use { input -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    } }
                    if (node.size > 0 && temporary.length() != node.size) throw IOException("Incomplete document: ${node.name}")
                    check(temporary.renameTo(target)) { "Cannot prepare local content" }
                } finally { temporary.delete() }
            }
            // Reader page cache identity includes backing-file mtime. Touch only an unpinned
            // artifact; reopening it for a cover must not invalidate an active Reader marker.
            if (!activeFiles.isPinned(target.name)) target.setLastModified(System.currentTimeMillis())
            activeFiles.acquire(target.name)
            trimMaterializedCacheLocked()
            target
        }
    }

    private suspend fun releaseMaterialized(file: File) {
        if (file.parentFile != cacheDir) return
        cacheMutex.withLock {
            activeFiles.release(file.name)
            // A cover-only copy is temporary. A concurrent/retained Reader reference wins.
            if (!activeFiles.isPinned(file.name)) file.delete()
            trimMaterializedCacheLocked()
        }
    }

    private fun trimMaterializedCacheLocked() {
        var total = cacheDir.listFiles().orEmpty().sumOf { it.length() }
        for (old in cacheDir.listFiles().orEmpty().sortedBy { it.lastModified() }) {
            if (total <= MATERIALIZED_CACHE_MAX_BYTES) break
            if (activeFiles.isPinned(old.name)) continue
            val size = old.length()
            if (old.delete()) total -= size
        }
    }

    suspend fun pages(root: Node, chapter: LocalChapter, original: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        check(documents.exists(root, chapter.node)) { "Local chapter is unavailable" }
        if (chapter.node.directory) {
            chapter.pages.map { page ->
                check(documents.exists(root, page)) { "Local page is unavailable: ${page.name}" }
                MangaPage(id = page.key.longHashCode(), url = page.uri, preview = null, source = LocalMangaSource)
            }
        } else if (LocalTreeScanner.extension(chapter.node.name) == "epub") {
            listOf(MangaPage(id = chapter.id, url = chapter.url, preview = null, source = LocalMangaSource))
        } else {
            val file = materialize(root, chapter.node)
            val urls = if (LocalTreeScanner.extension(chapter.node.name) == "pdf") {
                LocalPdfCache.renderPages(file).map { it.toUri().toString() }
            } else ZipFile(file).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && LocalTreeScanner.isImage(it.name) && safeEntry(it.name) }
                    .sortedWith(compareBy(LocalTreeScanner.NATURAL) { it.name })
                    .map { file.toZipUri(it.name).toString() }.toList()
            }
            check(urls.isNotEmpty()) { "No readable pages in ${chapter.node.name}" }
            urls.mapIndexed { i, url -> MangaPage(id = "${original.id}:$i".longHashCode(), url = url,
                preview = null, source = LocalMangaSource) }
        }
    }

    /** Enrich only when a title is opened. Scanning thousands of archives never copies/opens each book. */
    internal suspend fun metadata(root: Node, chapter: LocalChapter): LocalMetadata = withContext(Dispatchers.IO) {
        val key = "${chapter.node.key}:${chapter.node.modified}:${chapter.node.size}"
        metadataCache.get(key)?.let { return@withContext it }
        val ext = LocalTreeScanner.extension(chapter.node.name)
        val metadata = when (ext) {
            "epub" -> EpubParser.parse(materialize(root, chapter.node)).let { LocalMetadata(it.title, it.authors, it.description) }
            "cbz", "zip" -> ZipFile(materialize(root, chapter.node)).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.equals("index.json", true) }
                    ?: zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.equals("ComicInfo.xml", true) }
                entry?.let { zip.getInputStream(it).use { input ->
                    if (it.name.equals("index.json", true)) LocalMetadata.readJson(input) else LocalMetadata.readXml(input)
                } } ?: LocalMetadata()
            }
            else -> LocalMetadata()
        }
        metadataCache.put(key, metadata)
        metadata
    }

    suspend fun epubHtml(root: Node, chapter: LocalChapter): String = withContext(Dispatchers.IO) {
        val file = materialize(root, chapter.node)
        val book = EpubParser.parse(file)
        check(book.spine.isNotEmpty()) { "EPUB has no readable content" }
        ZipFile(file).use { zip ->
            buildString {
                append("<html><body>")
                for (item in book.spine.filter { chapter.epubSection == null || it.href == chapter.epubSection }) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.getEntry(item.href) ?: throw IOException("Missing EPUB section: ${item.href}")
                    val html = zip.getInputStream(entry).use { it.readBytesLimited(16 * 1024 * 1024).toString(Charsets.UTF_8) }
                    val doc = Jsoup.parse(html)
                    doc.select("script, iframe, object").remove()
                    for (image in doc.select("img[src]")) {
                        val path = EpubParser.resolveHref(item.href.substringBeforeLast('/', ""), image.attr("src"))
                        if (safeEntry(path)) image.attr("src", "local-epub:$path") else image.remove()
                    }
                    val entries = book.toc.filter { it.href == item.href }
                    if (entries.isEmpty()) {
                        doc.body().prependChild(org.jsoup.nodes.Element("miyorare-section").attr("data-title", item.title))
                    } else for (section in entries.asReversed()) {
                        val marker = org.jsoup.nodes.Element("miyorare-section").attr("data-title", section.title)
                        val target = section.fragment?.let { fragment ->
                            val id = runCatching { java.net.URLDecoder.decode(fragment.replace("+", "%2B"), "UTF-8") }.getOrDefault(fragment)
                            doc.getElementById(id)
                        }
                        if (target != null) target.before(marker) else doc.body().prependChild(marker)
                    }
                    append(doc.body().html())
                    check(length <= 32 * 1024 * 1024) { "EPUB volume is too large" }
                }
                append("</body></html>")
            }
        }
    }

    suspend fun epubImage(root: Node, chapter: LocalChapter, source: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!source.startsWith("local-epub:")) return@withContext null
        val path = source.removePrefix("local-epub:")
        if (!safeEntry(path) || !LocalTreeScanner.isImage(path)) return@withContext null
        ZipFile(materialize(root, chapter.node)).use { zip ->
            zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytesLimited(32 * 1024 * 1024) } }
        }
    }

    internal open suspend fun cover(
        plan: LocalCoverPlan, publish: suspend (GeneratedLocalCover) -> ByteArray,
    ): ByteArray? = withContext(Dispatchers.IO) {
        var selectedRoot: Node? = null
        for ((index, node) in plan.candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            val extension = LocalTreeScanner.extension(node.name)
            val result = coverWork.source(extension) {
                // Resolve the authorized root only on a miss and inside the source limit.
                val root = selectedRoot ?: documents.root(plan.rootUri).also { selectedRoot = it }
                val generated = runCatchingCancellable {
                    // PDF descriptor/materialization entry points enforce containment themselves.
                    if (extension != "pdf") check(documents.contains(root, node)) { "Content outside selected root" }
                    val source = when (extension) {
                        "pdf" -> withContext(pdfMeasurement.asContextElement(CoverMeasurement(plan, index))) { pdfCover(root, node) }
                        "cbz", "zip", "epub" -> {
                            val coroutine = currentCoroutineContext()
                            documents.input(node).also { sourceOpened(plan, index) }.use { input ->
                                BoundedArchiveCoverReader.read(
                                    input = input,
                                    isSafeImage = { path -> safeEntry(path) && LocalTreeScanner.isImage(path) },
                                    isPreferred = { path ->
                                        val name = path.substringAfterLast('/')
                                        LocalTreeScanner.isSidecar(name) || name.contains("cover", ignoreCase = true)
                                    },
                                    isValid = { candidate ->
                                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        android.graphics.BitmapFactory.decodeByteArray(candidate, 0, candidate.size, options)
                                        options.outWidth > 0 && options.outHeight > 0
                                    },
                                    checkActive = { coroutine.ensureActive() },
                                )
                            }
                        }
                        else -> {
                            documents.input(node).also { sourceOpened(plan, index) }.use { it.readBytesLimited(BoundedArchiveCoverReader.MAX_CANDIDATE_BYTES) }
                        }
                    }
                    source?.let {
                        if (extension == "pdf") GeneratedLocalCover(it, index)
                        else coverWork.bitmap {
                            val started = System.nanoTime()
                            LocalCoverThumbnail.prepare(it, index).also { prepared ->
                                diagnostics.record(measurement(plan, index, Reason.IMAGE_PREPARE, System.nanoTime() - started,
                                    prepared?.bytes?.size?.toLong() ?: 0))
                            }
                        }
                    }
                }.getOrNull()
                // Retain source admission through publication; do not accumulate unbounded
                // completed images waiting for the cache file mutex. Publication errors propagate.
                generated?.let { publish(it) }
            }
            if (result != null) return@withContext result
        }
        null
    }

    private suspend fun pdfCover(root: Node, node: Node): ByteArray? {
        val direct = try {
            documents.withReadDescriptor(root, node, ::renderPdfCover, onOpened = { elapsed ->
                pdfStage("PDF_DESCRIPTOR_OPEN", elapsed)
                pdfMeasurement.get()?.let { sourceOpened(it.plan, it.index) }
            })
        } catch (_: IOException) { null
        } catch (_: IllegalArgumentException) { null
        } catch (_: UnsupportedOperationException) { null
        } catch (_: SecurityException) { null }
        currentCoroutineContext().ensureActive()
        if (direct != null) return direct
        val file = materialize(root, node)
        try { return renderPdfCover(file) }
        finally { withContext(NonCancellable) { releaseMaterialized(file) } }
    }

    // Kept separate from materialization so lifecycle tests can count real renderer calls.
    protected open fun renderPdfCover(file: File): ByteArray? = LocalPdfCache.renderCoverThumbnail(file, ::pdfStage)
    protected open fun renderPdfCover(descriptor: ParcelFileDescriptor): ByteArray? = LocalPdfCache.renderCoverThumbnail(descriptor, ::pdfStage)

    private data class CoverMeasurement(val plan: LocalCoverPlan, val index: Int)

    private fun sourceOpened(plan: LocalCoverPlan, index: Int) = diagnostics.record(measurement(plan, index, Reason.SOURCE_OPEN))

    private fun pdfStage(stage: String, elapsed: Long) {
        val current = pdfMeasurement.get() ?: return
        diagnostics.record(measurement(current.plan, current.index, Reason.valueOf(stage), elapsed))
    }

    private fun measurement(plan: LocalCoverPlan, index: Int, reason: Reason, elapsed: Long = 0, bytes: Long = 0): Event {
        val node = plan.candidates[index]
        val kind = when (LocalTreeScanner.extension(node.name)) {
            "pdf" -> "PDF"
            "epub" -> "EPUB"
            "cbz", "zip" -> "ARCHIVE"
            else -> if (LocalTreeScanner.isSidecar(node.name)) "SIDECAR" else "IMAGE"
        }
        return Event(reason, plan.mangaId?.let { coverDigest(it.toString().toByteArray()) }, kind, index,
            entryBytes = bytes, elapsedNanos = elapsed, sourceOpened = true)
    }

    private fun safeEntry(path: String) = path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
        path.split('/').none { it == ".." } && ':' !in path

    private companion object {
        const val MATERIALIZED_CACHE_MAX_BYTES = 512L * 1024 * 1024
    }
}

