package org.koitharu.kotatsu.local.library

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.net.toFile
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

/** Reuses archive/PDF/EPUB backends. Seekable copies are lazy cache artifacts, never user imports. */
@Singleton
class LocalContentReader @Inject constructor(
    @ApplicationContext private val context: Context, private val documents: LocalDocuments,
) {
    private val cacheMutex = Mutex()
    // Returned archive URIs remain in Reader state. Pin them for this process so cache
    // maintenance cannot invalidate a still-open/previous chapter. Temporary cover users acquire
    // their own reference and release it as soon as extraction finishes.
    private val activeFiles = HashMap<String, Int>()
    private val metadataCache = android.util.LruCache<String, LocalMetadata>(32)
    private val cacheDir get() = File(context.cacheDir, "smart-local-content").also { it.mkdirs() }

    suspend fun materialize(root: Node, node: Node): File = withContext(Dispatchers.IO) {
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
                    documents.input(node).use { input -> temporary.outputStream().use { output ->
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
            target.setLastModified(System.currentTimeMillis())
            activeFiles[target.name] = (activeFiles[target.name] ?: 0) + 1
            trimMaterializedCacheLocked()
            target
        }
    }

    private suspend fun releaseMaterialized(file: File) {
        if (file.parentFile != cacheDir) return
        cacheMutex.withLock {
            val count = activeFiles[file.name] ?: return@withLock
            if (count <= 1) activeFiles.remove(file.name) else activeFiles[file.name] = count - 1
            trimMaterializedCacheLocked()
        }
    }

    private fun trimMaterializedCacheLocked() {
        var total = cacheDir.listFiles().orEmpty().sumOf { it.length() }
        for (old in cacheDir.listFiles().orEmpty().sortedBy { it.lastModified() }) {
            if (total <= MATERIALIZED_CACHE_MAX_BYTES) break
            if ((activeFiles[old.name] ?: 0) > 0) continue
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

    suspend fun cover(root: Node, book: LocalBook): ByteArray? = withContext(Dispatchers.IO) {
        fun valid(bytes: ByteArray?): ByteArray? {
            if (bytes == null) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            return bytes.takeIf { options.outWidth > 0 && options.outHeight > 0 }
        }
        val candidates = (listOfNotNull(book.cover) + book.sidecars.filter { LocalTreeScanner.isImage(it.name) }
            .sortedByDescending { LocalTreeScanner.isSidecar(it.name) }.map { it.uri }).distinct()
        for (uri in candidates) {
            val node = (book.sidecars + book.chapters.flatMap { it.pages }).firstOrNull { it.uri == uri }
            if (node != null && documents.contains(root, node)) {
                runCatchingCancellable { valid(documents.input(node).use { it.readBytesLimited(8 * 1024 * 1024) }) }
                    .getOrNull()?.let { return@withContext it }
            }
        }
        for (chapter in book.chapters) {
            currentCoroutineContext().ensureActive()
            val bytes = runCatchingCancellable {
                if (chapter.pages.isNotEmpty()) {
                    for (page in chapter.pages.take(BoundedArchiveCoverReader.MAX_IMAGE_CANDIDATES)) {
                        check(documents.contains(root, page))
                        runCatchingCancellable {
                            valid(documents.input(page).use { it.readBytesLimited(BoundedArchiveCoverReader.MAX_CANDIDATE_BYTES) })
                        }.getOrNull()?.let { return@runCatchingCancellable it }
                    }
                    null
                } else {
                    check(documents.contains(root, chapter.node)) { "Content outside selected root" }
                    when (LocalTreeScanner.extension(chapter.node.name)) {
                        "pdf" -> {
                            val file = materialize(root, chapter.node)
                            try {
                                LocalPdfCache.renderCover(file)?.readBytes()
                            } finally {
                                releaseMaterialized(file)
                            }
                        }
                        "cbz", "zip", "epub" -> {
                            val coroutineContext = currentCoroutineContext()
                            documents.input(chapter.node).use { input ->
                                BoundedArchiveCoverReader.read(
                                    input = input,
                                    isSafeImage = { path -> safeEntry(path) && LocalTreeScanner.isImage(path) },
                                    isPreferred = { path ->
                                        val name = path.substringAfterLast('/')
                                        LocalTreeScanner.isSidecar(name) || name.contains("cover", ignoreCase = true)
                                    },
                                    isValid = { candidate -> valid(candidate) != null },
                                    checkActive = { coroutineContext.ensureActive() },
                                )
                            }
                        }
                        else -> null
                    }
                }
            }.getOrNull()
            valid(bytes)?.let { return@withContext it }
        }
        null // Existing Coil/UI default artwork handles wholly unreadable/missing covers.
    }

    private fun safeEntry(path: String) = path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
        path.split('/').none { it == ".." } && ':' !in path

    private companion object {
        const val MATERIALIZED_CACHE_MAX_BYTES = 512L * 1024 * 1024
    }
}
