package org.koitharu.kotatsu.local.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.core.util.AlphanumComparator
import org.koitharu.kotatsu.core.util.ext.toZipUri
import org.koitharu.kotatsu.local.data.input.EpubParser
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Selected-root index. Download paths/aliases and their scans remain in LocalMangaIndex. */
@Singleton
class SmartLocalLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documents: LocalDocuments, private val contentReader: LocalContentReader,
    private val dataRepository: MangaDataRepository, private val db: MangaDatabase,
) {
    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences("smart_local_library", Context.MODE_PRIVATE) }
    private val indexFile get() = AtomicFile(File(context.filesDir, "smart-local-index.json"))
    private val mutableState = MutableStateFlow(LocalLibrarySnapshot())
    val state: StateFlow<LocalLibrarySnapshot> get() = mutableState
    private val mutableChanges = MutableSharedFlow<Unit>()
    val changes = mutableChanges.asSharedFlow()
    val showExtensions get() = prefs.getBoolean("extensions", false)
    val readingFilter get() = enumValue(prefs.getString("filter", null), LocalReadingFilter.ALL)
    val selectedContentType get() = LocalContentType.entries.firstOrNull { it.name == prefs.getString("content_type", null) }
    suspend fun setContentType(type: LocalContentType?) = withContext(Dispatchers.IO) {
        check(prefs.edit().putString("content_type", type?.name).commit())
    }
    val sort get() = enumValue(prefs.getString("sort", null), LocalLibrarySort.TITLE_ASC)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (state.value.initialized) return@withLock
            val roots = readRoots()
            val exclusions = readExclusions().keys
            val saved = runCatchingCancellable {
                indexFile.openRead().use { input -> JSONObject(input.bufferedReader().readText()) }
            }.getOrNull()
            val books = saved?.optJSONArray("books")?.objects()?.mapNotNull { json ->
                runCatching { json.toLocalBook() }.getOrNull()
            }.orEmpty().filter { it.rootUri in roots.map { r -> r.uri } && it.node.key !in exclusions }
            val diagnoses = saved?.optJSONArray("diagnoses")?.objects()?.mapNotNull { json ->
                runCatching { LocalDiagnosis(json.getString("root"), json.optJSONObject("node")?.toNode(),
                    json.getString("reason"), json.optJSONArray("candidates")?.objects()?.map { it.toNode() }.orEmpty()) }.getOrNull()
            }.orEmpty().filter { it.rootUri in roots.map { r -> r.uri } }
            mutableState.value = LocalLibrarySnapshot(roots, books, diagnoses, exclusions.size, true)
        }
    }

    suspend fun addRoot(uri: Uri) = withContext(Dispatchers.IO) {
        initialize()
        val selected = documents.root(uri.toString())
        check(selected.directory) { "Choose a folder" }
        if (uri.scheme == "content") {
            val resolver = context.contentResolver
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.getOrElse { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        mutex.withLock {
            for (root in state.value.roots) {
                val existing = runCatchingCancellable { documents.root(root.uri) }.getOrNull() ?: continue
                if (existing.key == selected.key) return@withLock
                check(!documents.contains(existing, selected) && !documents.contains(selected, existing)) {
                    "This folder overlaps an existing Local Folder"
                }
            }
            val roots = state.value.roots + LocalFolder(uri.toString(), selected.name)
            saveRoots(roots)
            publishLocked(state.value.copy(roots = roots))
        }
        scan()
    }

    suspend fun removeRoot(uri: String) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val roots = state.value.roots.filterNot { it.uri == uri }
            saveRoots(roots)
            publishLocked(state.value.copy(roots = roots, books = state.value.books.filterNot { it.rootUri == uri },
                diagnoses = state.value.diagnoses.filterNot { it.rootUri == uri }))
        }
        mutableChanges.emit(Unit)
    }

    suspend fun scan() = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val previous = state.value
            val previousByKey = previous.books.associateBy { it.node.key }
            val exclusions = readExclusions().keys
            val confirmed = prefs.getStringSet("confirmed", emptySet()).orEmpty().toSet()
            val books = LinkedHashMap<String, LocalBook>()
            val diagnoses = ArrayList<LocalDiagnosis>()
            for (root in previous.roots) {
                currentCoroutineContext().ensureActive()
                val result = runCatchingCancellable {
                    documents.withAccess(root.uri) { node, access -> LocalTreeScanner().scan(node, access, confirmed, exclusions) }
                }
                val scanned = result.getOrNull()
                if (scanned == null) {
                    previous.books.filter { it.rootUri == root.uri && it.node.key !in exclusions }.forEach { books[it.node.key] = it }
                    diagnoses += LocalDiagnosis(root.uri, null, "unavailable")
                    continue
                }
                for (entry in scanned.entries) {
                    currentCoroutineContext().ensureActive()
                    val old = previousByKey[entry.node.key]
                    val metadata = entry.sidecars.filter { it.name.endsWith(".xml", true) || it.name.equals("index.json", true) }
                        .map { LocalMetadata.read(it, documents) }
                    val explicitCover = entry.sidecars.firstOrNull { LocalTreeScanner.isImage(it.name) && LocalTreeScanner.isSidecar(it.name) }?.uri
                    val metadataCover = metadata.firstNotNullOfOrNull { it.coverName }?.let { name -> entry.sidecars.firstOrNull { it.name == name }?.uri }
                    val discoveredChapters = entry.chapters.map { c -> LocalChapter(c.node, c.pages) }
                    val preserved = old?.chapters?.groupBy { it.node.key }.orEmpty()
                    val chapters = if (old != null) discoveredChapters.flatMap { chapter ->
                        preserved[chapter.node.key]?.map { it.copy(node = chapter.node, pages = chapter.pages) } ?: listOf(chapter)
                    } else migrateLegacyChapters(root.uri, entry.node, discoveredChapters)
                    val oldKeys = old?.chapters?.mapTo(HashSet()) { it.node.key }.orEmpty()
                    val discovered = if (old == null) discoveredChapters.size else discoveredChapters.count { it.node.key !in oldKeys }
                    val book = LocalBook(root.uri, entry.node, chapters, entry.sidecars,
                        metadata.firstNotNullOfOrNull { it.title } ?: old?.takeIf { it.node.modified == entry.node.modified && it.node.size == entry.node.size }?.title,
                        metadata.flatMapTo(LinkedHashSet()) { it.authors }.ifEmpty { old?.authors.orEmpty() },
                        metadata.firstNotNullOfOrNull { it.description } ?: old?.description, explicitCover ?: metadataCover ?: chapters.firstOrNull()?.pages?.firstOrNull()?.uri,
                        entry.ignored, old?.addedAt ?: System.currentTimeMillis(), System.currentTimeMillis(),
                        pendingLocalDiscoveries(old?.newChapters ?: 0, discovered, chapters.size))
                    books.putIfAbsent(book.node.key, book)
                }
                scanned.issues.forEach { diagnoses += LocalDiagnosis(root.uri, it.node, it.reason, it.candidates) }
            }
            val snapshot = previous.copy(books = books.values.toList(), diagnoses = diagnoses, excludedCount = exclusions.size)
            for (book in snapshot.books) dataRepository.storeManga(book.toManga(showExtensions, true), replaceExisting = true)
            publishLocked(snapshot)
        }
        mutableChanges.emit(Unit)
    }

    suspend fun confirmManga(rootUri: String, node: Node) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val diagnosis = state.value.diagnoses.firstOrNull { it.rootUri == rootUri && it.candidates.any { c -> c.key == node.key } }
            checkNotNull(diagnosis) { "Only a reviewed single-folder chain can be resolved" }
            val confirmed = prefs.getStringSet("confirmed", emptySet()).orEmpty() + node.key
            check(prefs.edit().putStringSet("confirmed", confirmed).commit())
        }
        scan()
    }

    suspend fun hide(ids: Set<Long>) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val targets = state.value.books.filter { it.id in ids }
            val exclusions = readExclusions().toMutableMap()
            targets.forEach { exclusions[it.node.key] = it.node.name }
            saveExclusions(exclusions)
            publishLocked(state.value.copy(books = state.value.books.filterNot { it.id in ids }, excludedCount = exclusions.size))
        }
        mutableChanges.emit(Unit)
    }

    suspend fun acknowledgeDiscoveries(ids: Set<Long>? = null) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock { publishLocked(state.value.copy(books = state.value.books.map { if (ids == null || it.id in ids) it.copy(newChapters = 0) else it })) }
    }

    suspend fun excluded(): Map<String, String> = withContext(Dispatchers.IO) { readExclusions() }
    suspend fun restore(key: String) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val exclusions = readExclusions().toMutableMap().also { it.remove(key) }
            saveExclusions(exclusions)
            publishLocked(state.value.copy(excludedCount = exclusions.size))
        }
        scan()
    }

    suspend fun deleteFromDevice(ids: Set<Long>) = withContext(Dispatchers.IO) {
        initialize()
        try { mutex.withLock {
            val targets = state.value.books.filter { it.id in ids }
            for (book in targets) {
                val root = documents.root(book.rootUri)
                val files = (book.chapters.flatMap { c -> if (c.node.directory) c.pages else listOf(c.node) } + book.sidecars).distinctBy { it.key }
                val otherOwned = state.value.books.filter { it.id != book.id }
                    .flatMap { b -> b.chapters.flatMap { c -> c.pages + c.node } + b.sidecars }.mapTo(HashSet()) { it.key }
                check(files.none { it.key in otherOwned }) { "A document belongs to another manga" }
                files.forEach { node ->
                    currentCoroutineContext().ensureActive()
                    check(!node.directory && node.key != root.key && documents.contains(root, node)) { "Unsafe deletion target" }
                }
                for (file in files) if (documents.exists(root, file)) documents.delete(root, file)
                for (folder in (book.chapters.map { it.node }.filter { it.directory } + listOf(book.node).filter { it.directory }).distinctBy { it.key }) {
                    if (folder.uri.toUri().scheme == "file" && folder.key != root.key && documents.exists(root, folder) && documents.children(root, folder).isEmpty()) documents.delete(root, folder)
                }
                val exclusions = readExclusions().toMutableMap().also { it[book.node.key] = book.node.name }
                saveExclusions(exclusions)
                publishLocked(state.value.copy(books = state.value.books.filterNot { it.id == book.id }, excludedCount = exclusions.size))
            }
        } } catch (error: Exception) {
            runCatchingCancellable { scan() }
            throw error
        }
        mutableChanges.emit(Unit)
    }

    suspend fun deleteChapters(mangaId: Long, ids: Set<Long>) = withContext(Dispatchers.IO) {
        initialize()
        try { mutex.withLock {
            val book = state.value.books.firstOrNull { it.id == mangaId } ?: error("Local manga is unavailable")
            val root = documents.root(book.rootUri)
            val chapters = book.chapters.filter { it.id in ids }
            for (chapter in chapters) {
                val owned = if (chapter.node.directory) chapter.pages else listOf(chapter.node)
                val others = state.value.books.flatMap { otherBook -> otherBook.chapters
                    .filter { otherBook.id != mangaId || it.id !in ids }
                    .flatMap { c -> if (c.node.directory) c.pages else listOf(c.node) } }.mapTo(HashSet()) { it.key }
                check(owned.none { it.key in others }) { "A document is shared by another chapter" }
                check(owned.all { !it.directory && it.key != root.key && documents.contains(root, it) })
                for (page in owned) if (documents.exists(root, page)) documents.delete(root, page)
                if (chapter.node.directory && chapter.node.uri.toUri().scheme == "file" && chapter.node.key != root.key && documents.children(root, chapter.node).isEmpty()) documents.delete(root, chapter.node)
            }
        } } catch (error: Exception) {
            runCatchingCancellable { scan() }
            throw error
        }
        scan()
    }

    suspend fun setDisplayOptions(extensions: Boolean, filter: LocalReadingFilter = readingFilter, order: LocalLibrarySort = sort) = withContext(Dispatchers.IO) {
        check(prefs.edit().putBoolean("extensions", extensions).putString("filter", filter.name).putString("sort", order.name).commit())
        for (book in state.value.books) dataRepository.storeManga(book.toManga(extensions, true), replaceExisting = true)
        mutex.withLock { publishLocked(state.value.copy(displayRevision = state.value.displayRevision + 1)) }
        mutableChanges.emit(Unit)
    }

    suspend fun book(id: Long): LocalBook? { initialize(); return state.value.books.firstOrNull { it.id == id } }
    suspend fun details(id: Long): Manga? = withContext(Dispatchers.IO) {
        var book = book(id) ?: return@withContext null
        if (book.chapters.size == 1 && !book.chapters.single().node.directory) {
            val metadata = runCatchingCancellable { contentReader.metadata(documents.root(book.rootUri), book.chapters.single()) }.getOrNull()
            if (metadata != null) {
                val enriched = book.copy(title = book.title ?: metadata.title, authors = book.authors.ifEmpty { metadata.authors }, description = book.description ?: metadata.description)
                if (enriched != book) mutex.withLock {
                    val current = state.value.books.firstOrNull { it.id == id }
                    if (current == book) {
                        dataRepository.storeManga(enriched.toManga(showExtensions, true), replaceExisting = true)
                        publishLocked(state.value.copy(books = state.value.books.map { if (it.id == id) enriched else it }))
                        book = enriched
                    }
                }
            }
        }
        book.toManga(showExtensions, true)
    }

    suspend fun list(query: String?, type: LocalContentType? = null): List<Manga> = withContext(Dispatchers.IO) {
        initialize()
        val snapshot = state.value.books
        val histories = snapshot.map { it.id }.chunked(500).flatMap { db.getHistoryDao().findByIds(it) }.associateBy { it.mangaId }
        val historiesByKey = snapshot.mapNotNull { book -> histories[book.id]?.let { book.node.key to it } }.toMap()
        projectLocalCollection(snapshot, historiesByKey, query, type, readingFilter, sort, showExtensions)
            .map { it.toManga(showExtensions) }
    }

    suspend fun pages(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        val (book, localChapter) = requireChapter(chapter.url)
        readWithDiagnosis(book, localChapter) { contentReader.pages(documents.root(book.rootUri), localChapter, chapter) }
    }
    suspend fun chapterHtml(chapter: MangaChapter): String = withContext(Dispatchers.IO) {
        val (book, localChapter) = requireChapter(chapter.url)
        check(LocalTreeScanner.extension(localChapter.node.name) == "epub")
        readWithDiagnosis(book, localChapter) { contentReader.epubHtml(documents.root(book.rootUri), localChapter) }
    }
    suspend fun chapterImage(url: String, source: String): ByteArray? = withContext(Dispatchers.IO) {
        val (book, chapter) = requireChapter(url)
        return@withContext contentReader.epubImage(documents.root(book.rootUri), chapter, source)
    }
    suspend fun cover(id: Long): ByteArray? = withContext(Dispatchers.IO) {
        val book = book(id) ?: return@withContext null
        return@withContext contentReader.cover(documents.root(book.rootUri), book)
    }
    private suspend fun requireChapter(url: String): Pair<LocalBook, LocalChapter> {
        initialize()
        val id = url.toUri().pathSegments.firstOrNull()?.toLongOrNull() ?: error("Invalid local chapter")
        for (book in state.value.books) book.chapters.firstOrNull { it.id == id }?.let { return book to it }
        error("Local chapter is no longer indexed")
    }

    private suspend fun <T> readWithDiagnosis(book: LocalBook, chapter: LocalChapter, read: suspend () -> T): T {
        val result = runCatchingCancellable { read() }
        if (result.isFailure) mutex.withLock {
            val issue = LocalDiagnosis(book.rootUri, chapter.node, "unreadable")
            val existing = state.value.diagnoses.filterNot { it.rootUri == issue.rootUri && it.node?.key == issue.node?.key }
            publishLocked(state.value.copy(diagnoses = existing + issue))
        }
        return result.getOrThrow()
    }

    private suspend fun migrateLegacyChapters(rootUri: String, node: Node, chapters: List<LocalChapter>): List<LocalChapter> {
        if (node.uri.toUri().scheme != "file") return chapters
        val history = db.getHistoryDao().find(node.mangaIdentity()) ?: return chapters
        val mapped = runCatchingCancellable {
            if (chapters.all { LocalTreeScanner.extension(it.node.name) == "epub" }) {
                val root = documents.root(rootUri)
                chapters.flatMap { c ->
                    val file = contentReader.materialize(root, c.node)
                    EpubParser.parse(file).spine.map { section -> c.copy(metadataTitle = section.title, preservedId = file.toZipUri(section.href).toString().longHashCode(), epubSection = section.href) }
                }
            } else if (!node.directory) {
                val file = contentReader.materialize(documents.root(rootUri), node)
                val legacy = LocalMangaParser(file).getManga(true).manga.chapters.orEmpty()
                if (legacy.size == 1) listOf(chapters.single().copy(preservedId = legacy.single().id)) else chapters
            } else {
                val byKey = chapters.sortedWith(compareBy(AlphanumComparator()) { it.node.key })
                    .mapIndexed { index, c -> c.node.key to "$index${c.node.key.removePrefix(node.key + "/")}".longHashCode() }.toMap()
                chapters.map { it.copy(preservedId = byKey[it.node.key]) }
            }
        }.getOrNull() ?: return chapters
        return mapped.takeIf { list -> list.any { it.id == history.chapterId } } ?: chapters
    }

    private fun readRoots() = JSONArray(prefs.getString("roots", "[]")).objects().map { LocalFolder(it.getString("uri"), it.getString("name")) }
    private fun saveRoots(roots: List<LocalFolder>) {
        check(prefs.edit().putString("roots", JSONArray(roots.map { JSONObject().put("uri", it.uri).put("name", it.name) }).toString()).commit())
    }
    private fun readExclusions(): Map<String, String> {
        val json = JSONObject(prefs.getString("exclusions", "{}").orEmpty())
        return json.keys().asSequence().associateWith { json.getString(it) }
    }
    private fun saveExclusions(exclusions: Map<String, String>) { check(prefs.edit().putString("exclusions", JSONObject(exclusions).toString()).commit()) }
    private fun publishLocked(snapshot: LocalLibrarySnapshot) {
        val json = JSONObject().put("version", 1).put("books", JSONArray(snapshot.books.map { it.toJson() }))
            .put("diagnoses", JSONArray(snapshot.diagnoses.map { d -> JSONObject().put("root", d.rootUri).put("node", d.node?.toJson()).put("reason", d.reason).put("candidates", JSONArray(d.candidates.map { it.toJson() })) }))
        val file = indexFile
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray()); file.finishWrite(output)
        } catch (e: Throwable) { file.failWrite(output); throw e }
        mutableState.value = snapshot
    }
    private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T = enumValues<T>().firstOrNull { it.name == value } ?: default
}
