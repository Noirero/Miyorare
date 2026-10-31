package org.koitharu.kotatsu.core.cache

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Mihon chapter cache adapted to Miyorare's MangaPage model.
 * Entries are disposable; download files and database records are never owned by this class.
 *
 * This adapter is intentionally not a second active Reader cache until routing is switched.
 */
@Singleton
class MihonChapterCache @Inject constructor(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(application.cacheDir, "mihon_chapter_cache")
    private val indexes = File(application.noBackupFilesDir, "mihon_chapter_cache")
    private val pageLists = MihonTtlDiskCache(
        File(root, "pages"), File(indexes, "pages.index"), 1.hours, scope,
    )
    private val images = MihonTtlDiskCache(
        File(root, "images"), File(indexes, "images.index"), 1.days, scope,
    )

    suspend fun getPageList(source: MangaSource, chapter: MangaChapter): List<MangaPage>? {
        val key = chapterKey(source, chapter)
        val input = pageLists.open(key) ?: return null
        return try {
            val json = withContext(Dispatchers.IO) { input.bufferedReader().use { JSONArray(it.readText()) } }
            List(json.length()) { i ->
                val page = json.getJSONObject(i)
                MangaPage(
                    id = page.getLong("id"),
                    url = page.getString("url"),
                    preview = if (page.isNull("preview")) null else page.getString("preview"),
                    source = source,
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            pageLists.remove(key)
            null
        }
    }

    suspend fun putPageList(source: MangaSource, chapter: MangaChapter, pages: List<MangaPage>) {
        val payload = JSONArray().apply {
            pages.forEach { page ->
                put(JSONObject().put("id", page.id).put("url", page.url)
                    .put("preview", page.preview ?: JSONObject.NULL))
            }
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            pageLists.put(chapterKey(source, chapter)) { it.write(payload) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Disposable cache failure must not turn a successful source response into a Reader failure.
        }
    }

    suspend fun invalidatePageList(source: MangaSource, chapter: MangaChapter) {
        pageLists.remove(chapterKey(source, chapter))
    }

    suspend fun getImageFile(url: String): File? = images.get(url)

    @Throws(IOException::class)
    suspend fun putImage(url: String, input: java.io.InputStream): File =
        images.put(url) { output -> input.copyTo(output) }

    suspend fun removeImage(url: String) { images.remove(url) }

    suspend fun totalSize(): Long = pageLists.totalSize() + images.totalSize()

    suspend fun clear(): Int = pageLists.clear() + images.clear()

    private fun chapterKey(source: MangaSource, chapter: MangaChapter): String =
        "${source.name}:${chapter.id}:${chapter.url}"
}
