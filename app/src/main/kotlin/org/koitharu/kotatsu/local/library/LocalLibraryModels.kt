package org.koitharu.kotatsu.local.library

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.longHashCode

const val LOCAL_LIBRARY_SCHEME = "smart-local"

data class LocalFolder(val uri: String, val name: String)
data class LocalDiagnosis(val rootUri: String, val node: Node?, val reason: String, val candidates: List<Node> = emptyList())
data class LocalChapter(val node: Node, val pages: List<Node>, val metadataTitle: String? = null) {
    val id get() = "local-chapter:${node.key}".longHashCode()
    val url get() = "$LOCAL_LIBRARY_SCHEME://chapter/$id/${android.net.Uri.encode(node.name)}"
}
data class LocalBook(
    val rootUri: String, val node: Node, val chapters: List<LocalChapter>, val sidecars: List<Node>,
    val title: String?, val authors: Set<String>, val description: String?, val cover: String?,
    val ignored: Int, val addedAt: Long, val scannedAt: Long, val newChapters: Int,
) {
    // File-backed identity is compatible with LocalMangaParser. SAF identity ignores tree-URI wrappers.
    val id get() = node.key.longHashCode()
    val size get() = chapters.sumOf { c -> if (c.node.directory) c.pages.sumOf { it.size } else c.node.size } + sidecars.sumOf { it.size }
    val latestChapterAt get() = chapters.maxOfOrNull { it.node.modified } ?: 0L
    fun toManga(showExtensions: Boolean, withDetails: Boolean = false): Manga {
        val isText = chapters.isNotEmpty() && chapters.all { LocalTreeScanner.extension(it.node.name) == "epub" }
        val url = "$LOCAL_LIBRARY_SCHEME://manga/$id/${if (isText) "book.epub" else "book"}"
        return Manga(id = id, title = title ?: LocalTreeScanner.displayName(node.name, showExtensions, node.directory),
            altTitles = emptySet(), url = url, publicUrl = url, source = LocalMangaSource,
            coverUrl = cover ?: "$LOCAL_LIBRARY_SCHEME://cover/$id", largeCoverUrl = null, rating = -1f,
            contentRating = null, tags = emptySet(), state = null, authors = authors, description = description,
            chapters = if (withDetails) chapters.mapIndexed { index, c ->
                MangaChapter(id = c.id, title = c.metadataTitle ?: LocalTreeScanner.displayName(c.node.name, showExtensions, c.node.directory),
                    number = (index + 1).toFloat(), volume = 0, url = c.url, scanlator = null,
                    uploadDate = 0L, branch = null, source = LocalMangaSource)
            } else null)
    }
}
data class LocalLibrarySnapshot(
    val roots: List<LocalFolder> = emptyList(), val books: List<LocalBook> = emptyList(),
    val diagnoses: List<LocalDiagnosis> = emptyList(), val excludedCount: Int = 0,
    val initialized: Boolean = false, val displayRevision: Long = 0L,
)
enum class LocalReadingFilter { ALL, UNREAD, READING, COMPLETED }
enum class LocalLibrarySort { LAST_READ, ADDED, TITLE_ASC, TITLE_DESC, CHAPTER_UPDATED }

internal fun Node.toJson() = JSONObject().put("key", key).put("uri", uri).put("name", name)
    .put("directory", directory).put("size", size).put("modified", modified)
internal fun JSONObject.toNode() = Node(getString("key"), getString("uri"), getString("name"),
    getBoolean("directory"), optLong("size"), optLong("modified"))
internal fun LocalBook.toJson() = JSONObject().put("root", rootUri).put("node", node.toJson())
    .put("chapters", JSONArray(chapters.map { c -> JSONObject().put("node", c.node.toJson())
        .put("pages", JSONArray(c.pages.map { it.toJson() })).put("title", c.metadataTitle) }))
    .put("sidecars", JSONArray(sidecars.map { it.toJson() })).put("title", title).put("authors", JSONArray(authors))
    .put("description", description).put("cover", cover).put("ignored", ignored).put("added", addedAt)
    .put("scanned", scannedAt).put("new", newChapters)
internal fun JSONObject.toLocalBook() = LocalBook(getString("root"), getJSONObject("node").toNode(),
    getJSONArray("chapters").objects().map { c -> LocalChapter(c.getJSONObject("node").toNode(),
        c.getJSONArray("pages").objects().map { it.toNode() }, c.stringOrNull("title")) },
    getJSONArray("sidecars").objects().map { it.toNode() }, stringOrNull("title"),
    optJSONArray("authors")?.let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) } }.orEmpty(),
    stringOrNull("description"), stringOrNull("cover"), optInt("ignored"), optLong("added"), optLong("scanned"), optInt("new"))
internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
