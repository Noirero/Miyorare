package org.koitharu.kotatsu.local.library

import android.content.ContentResolver
import android.content.Context
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
    private val recentFiles = ArrayDeque<String>()
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
            recentFiles.remove(target.name); recentFiles.addLast(target.name)
            while (recentFiles.size > 4) recentFiles.removeFirst()
            var total = cacheDir.listFiles().orEmpty().sumOf { it.length() }
            for (old in cacheDir.listFiles().orEmpty().sortedBy { it.lastModified() }) {
                if (total <= 512L * 1024 * 1024) break
                if (old.name in recentFiles) continue
                val size = old.length()
                if (old.delete()) total -= size
            }
            target
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

    suspend fun epubHtml(root: Node, chapter: LocalChapter): String = withContext(Dispatchers.IO) {
        val file = materialize(root, chapter.node)
        val book = EpubParser.parse(file)
        check(book.spine.isNotEmpty()) { "EPUB has no readable content" }
        ZipFile(file).use { zip ->
            buildString {
                append("<html><body>")
                for (item in book.spine) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.getEntry(item.href) ?: throw IOException("Missing EPUB section: ${item.href}")
                    val html = zip.getInputStream(entry).use { it.readBytesLimited(16 * 1024 * 1024).toString(Charsets.UTF_8) }
                    val doc = Jsoup.parse(html)
                    doc.select("script, iframe, object").remove()
                    for (image in doc.select("img[src]")) {
                        val path = EpubParser.resolveHref(item.href.substringBeforeLast('/', ""), image.attr("src"))
                        if (safeEntry(path)) image.attr("src", "local-epub:$path") else image.remove()
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
        val chapter = book.chapters.firstOrNull() ?: return@withContext null
        if (chapter.pages.isNotEmpty()) {
            val page = chapter.pages.first()
            check(documents.contains(root, page))
            return@withContext documents.input(page).use { it.readBytesLimited(32 * 1024 * 1024) }
        }
        val file = materialize(root, chapter.node)
        when (LocalTreeScanner.extension(chapter.node.name)) {
            "pdf" -> LocalPdfCache.renderCover(file)?.readBytes()
            "epub" -> ZipFile(file).use { zip ->
                val cover = EpubParser.parse(file).coverHref?.takeIf(::safeEntry)
                    ?.let { zip.getEntry(it) }
                    ?: zip.entries().asSequence().filter { !it.isDirectory && LocalTreeScanner.isImage(it.name) && safeEntry(it.name) }
                        .sortedWith(compareBy(LocalTreeScanner.NATURAL) { it.name }).firstOrNull()
                cover?.let { zip.getInputStream(it).use { input -> input.readBytesLimited(32 * 1024 * 1024) } }
            }
            else -> ZipFile(file).use { zip ->
                val image = zip.entries().asSequence().filter { !it.isDirectory && LocalTreeScanner.isImage(it.name) && safeEntry(it.name) }
                    .sortedWith(compareBy(LocalTreeScanner.NATURAL) { it.name }).firstOrNull()
                image?.let { zip.getInputStream(it).use { input -> input.readBytesLimited(32 * 1024 * 1024) } }
            }
        }
    }

    private fun safeEntry(path: String) = path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
        path.split('/').none { it == ".." } && ':' !in path
}
