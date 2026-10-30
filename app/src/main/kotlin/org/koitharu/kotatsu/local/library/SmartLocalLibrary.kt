package org.koitharu.kotatsu.local.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Selected-root index. Download paths/aliases and their scans remain in LocalMangaIndex. */
@Singleton
class SmartLocalLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documents: LocalDocuments, private val contentReader: LocalContentReader,
    private val dataRepository: MangaDataRepository, private val db: MangaDatabase,
    private val coverCache: SmartLocalCoverCache,
) {
    // Disposable, independently budgeted chapter-gallery thumbnails. Never store reader pages.
    private val chapterGalleryCache by lazy {
        SmartLocalCoverCache(File(context.cacheDir, "smart-local-chapter-gallery"),
            maxBytes = 48L * 1024 * 1024, maxEntries = 512)
    }
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
            }.orEmpty().filter { it.rootUri in roots.map { r -> r.uri } && it.node.key !in exclusions }.map { book ->
                val overrides = JSONObject(prefs.getString("author_overrides", "{}").orEmpty())
                val withAuthors = if (!overrides.has(book.node.key)) book else book.copy(authors = overrides.optString(book.node.key).split(',').map { it.trim() }.filterTo(LinkedHashSet()) { it.isNotEmpty() })
                applyTextOverrides(withAuthors)
            }
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

    suspend fun refreshSelected(ids: Set<Long>) = scan(selectedIds = ids)

    suspend fun scan(selectedRoots: Set<String>? = null, selectedIds: Set<Long>? = null) = withContext(Dispatchers.IO) {
        initialize()
        if (selectedRoots != null && selectedRoots.isEmpty()) return@withContext
        if (selectedIds != null && selectedIds.isEmpty()) return@withContext
        mutex.withLock {
            val previous = state.value
            val previousByKey = previous.books.associateBy { it.node.key }
            val exclusions = readExclusions().keys
            val authorOverrides = JSONObject(prefs.getString("author_overrides", "{}").orEmpty())
            val confirmed = prefs.getStringSet("confirmed", emptySet()).orEmpty().toSet()
            val targets = if (selectedIds == null) emptyList() else previous.books.filter { it.id in selectedIds }
            require(selectedIds == null || targets.size == selectedIds.size) { "Selected collection is no longer indexed" }
            val activeRoots = previous.roots.filter { root ->
                (selectedRoots == null || root.uri in selectedRoots) &&
                    (selectedIds == null || targets.any { it.rootUri == root.uri })
            }
            val books = LinkedHashMap<String, LocalBook>()
            previous.books.filter { book -> activeRoots.none { it.uri == book.rootUri } ||
                (selectedIds != null && book.id !in selectedIds) }.forEach { books[it.node.key] = it }
            val diagnoses = ArrayList<LocalDiagnosis>()
            diagnoses += previous.diagnoses.filter { issue -> activeRoots.none { it.uri == issue.rootUri } || selectedIds != null }
            for (root in activeRoots) {
                currentCoroutineContext().ensureActive()
                val result = runCatchingCancellable {
                    if (selectedIds == null) {
                        documents.withAccess(root.uri) { node, access -> LocalTreeScanner().scan(node, access, confirmed, exclusions) }
                    } else {
                        val result = LocalTreeScanner.Result()
                        for (target in targets.filter { it.rootUri == root.uri }) {
                            currentCoroutineContext().ensureActive()
                            val rootNode = documents.root(root.uri)
                            check(documents.contains(rootNode, target.node)) { "Selected collection outside its root" }
                            val coroutine = currentCoroutineContext()
                            val scanned = if (target.node.directory) {
                                val access = documents.access(target.node) { coroutine.ensureActive() }
                                LocalTreeScanner().scan(target.node, access, confirmed + target.node.key, exclusions)
                            } else {
                                LocalTreeScanner().scanFile(documents.stat(rootNode, target.node))
                            }
                            result.entries.addAll(scanned.entries)
                            result.issues.addAll(scanned.issues)
                        }
                        result
                    }
                }
                val scanned = result.getOrNull()
                if (scanned == null) {
                    previous.books.filter { it.rootUri == root.uri && it.node.key !in exclusions &&
                        (selectedIds == null || it.id in selectedIds) }.forEach { books[it.node.key] = it }
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
                    val override = authorOverrides.optString(book.node.key).takeIf { authorOverrides.has(book.node.key) }
                    val withAuthors = if (override != null) book.copy(authors = override.split(',').map { it.trim() }.filterTo(LinkedHashSet()) { it.isNotEmpty() }) else book
                    books.putIfAbsent(book.node.key, applyTextOverrides(withAuthors))
                }
                scanned.issues.forEach { diagnoses += LocalDiagnosis(root.uri, it.node, it.reason, it.candidates) }
            }
            val snapshot = previous.copy(books = books.values.toList(), diagnoses = diagnoses, excludedCount = exclusions.size)
            // One Room transaction avoids paying transaction setup/commit cost once per title in
            // large Smart Local collections. Nested storeManga() calls reuse this transaction.
            db.withTransaction {
                for (book in snapshot.books) {
                    currentCoroutineContext().ensureActive()
                    dataRepository.storeManga(book.toManga(showExtensions, true), replaceExisting = true)
                }
            }
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

    /** Bulk author override is kept separate from scanner metadata so rescans preserve user edits. */
    suspend fun setAuthors(ids: Set<Long>, authors: Set<String>) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val selected = state.value.books.filter { it.id in ids }
            if (selected.isEmpty()) return@withLock
            val overrides = JSONObject(prefs.getString("author_overrides", "{}").orEmpty())
            val encoded = authors.joinToString(",")
            selected.forEach { overrides.put(it.node.key, encoded) }
            check(prefs.edit().putString("author_overrides", overrides.toString()).commit())
            val updated = state.value.books.map { if (it.id in ids) it.copy(authors = authors) else it }
            db.withTransaction {
                for (book in updated.filter { it.id in ids }) {
                    dataRepository.storeManga(book.toManga(showExtensions, true), replaceExisting = true)
                }
            }
            publishLocked(state.value.copy(books = updated))
        }
        mutableChanges.emit(Unit)
    }

    /** Persist manual title/description overrides independently of scanner sidecars. */
    private fun applyTextOverrides(book: LocalBook): LocalBook {
        val overrides = JSONObject(prefs.getString("text_overrides", "{}").orEmpty())
        val entry = overrides.optJSONObject(book.node.key) ?: return book
        return book.copy(
            title = if (entry.has("title")) entry.optString("title").ifBlank { book.title } else book.title,
            description = if (entry.has("description")) entry.optString("description") else book.description,
        )
    }

    suspend fun setTextMetadata(ids: Set<Long>, title: String?, description: String?) = withContext(Dispatchers.IO) {
        require(title != null || description != null) { "No metadata fields supplied" }
        require(title == null || title.length <= 300) { "Title too long" }
        require(description == null || description.length <= 20_000) { "Description too long" }
        initialize()
        mutex.withLock {
            val selected = state.value.books.filter { it.id in ids }
            if (selected.isEmpty()) return@withLock
            val overrides = JSONObject(prefs.getString("text_overrides", "{}").orEmpty())
            for (book in selected) {
                val entry = overrides.optJSONObject(book.node.key) ?: JSONObject()
                if (title != null) entry.put("title", title)
                if (description != null) entry.put("description", description)
                overrides.put(book.node.key, entry)
            }
            check(prefs.edit().putString("text_overrides", overrides.toString()).commit())
            val updated = state.value.books.map { if (it.id in ids) applyTextOverrides(it) else it }
            db.withTransaction {
                for (book in updated.filter { it.id in ids }) {
                    currentCoroutineContext().ensureActive()
                    dataRepository.storeManga(book.toManga(showExtensions, true), replaceExisting = true)
                }
            }
            publishLocked(state.value.copy(books = updated))
        }
        mutableChanges.emit(Unit)
    }

    /** Remove manual text overrides; rescan restores scanner-derived metadata. */
    suspend fun clearTextMetadata(ids: Set<Long>) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val selected = state.value.books.filter { it.id in ids }
            if (selected.isEmpty()) return@withLock
            val overrides = JSONObject(prefs.getString("text_overrides", "{}").orEmpty())
            selected.forEach { overrides.remove(it.node.key) }
            check(prefs.edit().putString("text_overrides", overrides.toString()).commit())
            // Clear the in-memory fallback too: scan() otherwise reuses old.title and
            // old.description when no metadata sidecar provides replacements.
            val cleared = state.value.books.map { book ->
                if (book.id in ids) book.copy(title = null, description = null) else book
            }
            publishLocked(state.value.copy(books = cleared))
        }
        scan()
    }

    /** Virtual groups never move or modify physical documents. */
    fun groups(): Map<String, Set<String>> {
        val json = JSONObject(prefs.getString("virtual_groups", "{}").orEmpty())
        return json.keys().asSequence().associateWith { name ->
            val members = json.optJSONArray(name) ?: JSONArray()
            (0 until members.length()).mapTo(LinkedHashSet()) { members.getString(it) }
        }
    }

    suspend fun createGroup(name: String, ids: Set<Long>) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 80) { "Invalid group name" }
        initialize()
        mutex.withLock {
            val json = JSONObject(prefs.getString("virtual_groups", "{}").orEmpty())
            val normalized = name.trim()
            require(!json.has(normalized)) { "Group already exists" }
            val members = state.value.books.filter { it.id in ids }.map { it.node.key }
            require(members.isNotEmpty()) { "Select at least one collection" }
            json.put(normalized, JSONArray(members))
            check(prefs.edit().putString("virtual_groups", json.toString()).commit())
            publishLocked(state.value.copy(displayRevision = state.value.displayRevision + 1))
        }
    }

    suspend fun addToGroup(name: String, ids: Set<Long>) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val json = JSONObject(prefs.getString("virtual_groups", "{}").orEmpty())
            require(json.has(name)) { "Group no longer exists" }
            val existing = json.getJSONArray(name)
            val members = (0 until existing.length()).mapTo(LinkedHashSet()) { existing.getString(it) }
            members += state.value.books.filter { it.id in ids }.map { it.node.key }
            json.put(name, JSONArray(members.toList()))
            check(prefs.edit().putString("virtual_groups", json.toString()).commit())
            publishLocked(state.value.copy(displayRevision = state.value.displayRevision + 1))
        }
    }

    suspend fun ungroup(ids: Set<Long>) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val keys = state.value.books.filter { it.id in ids }.mapTo(HashSet()) { it.node.key }
            val json = JSONObject(prefs.getString("virtual_groups", "{}").orEmpty())
            for (name in json.keys().asSequence().toList()) {
                val old = json.getJSONArray(name)
                json.put(name, JSONArray((0 until old.length()).map { old.getString(it) }.filterNot { it in keys }))
            }
            check(prefs.edit().putString("virtual_groups", json.toString()).commit())
            publishLocked(state.value.copy(displayRevision = state.value.displayRevision + 1))
        }
    }

    suspend fun renameGroup(oldName: String, newName: String) = withContext(Dispatchers.IO) {
        val normalized = newName.trim()
        require(normalized.isNotEmpty() && normalized.length <= 80)
        initialize()
        mutex.withLock {
            val json = JSONObject(prefs.getString("virtual_groups", "{}").orEmpty())
            require(json.has(oldName)) { "Group no longer exists" }
            require(oldName == normalized || !json.has(normalized)) { "Group already exists" }
            val members = json.getJSONArray(oldName)
            json.remove(oldName)
            json.put(normalized, members)
            check(prefs.edit().putString("virtual_groups", json.toString()).commit())
            publishLocked(state.value.copy(displayRevision = state.value.displayRevision + 1))
        }
    }

    suspend fun deleteGroup(name: String) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val json = JSONObject(prefs.getString("virtual_groups", "{}").orEmpty())
            json.remove(name)
            check(prefs.edit().putString("virtual_groups", json.toString()).commit())
            publishLocked(state.value.copy(displayRevision = state.value.displayRevision + 1))
        }
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
            require(targets.size == ids.size) { "Selected collections changed" }
            // Calculate ownership once for the complete batch, including selected books.
            // A shared file must never be removed while another indexed book refers to it.
            val ownership = HashMap<String, Int>()
            for (indexedBook in state.value.books) {
                val ownedKeys = (indexedBook.chapters.flatMap { c -> c.pages + c.node } + indexedBook.sidecars)
                    .mapTo(HashSet()) { it.key }
                for (key in ownedKeys) ownership[key] = (ownership[key] ?: 0) + 1
            }
            // Validate the entire batch before touching any physical document. A later
            // shared-file or unsafe-path failure must not delete earlier selections.
            for (book in targets) {
                currentCoroutineContext().ensureActive()
                val root = documents.root(book.rootUri)
                val files = (book.chapters.flatMap { chapter ->
                    if (chapter.node.directory) chapter.pages else listOf(chapter.node)
                } + book.sidecars).distinctBy { it.key }
                val ownKeys = (book.chapters.flatMap { chapter -> chapter.pages + chapter.node } + book.sidecars)
                    .mapTo(HashSet()) { it.key }
                check(files.none { (ownership[it.key] ?: 0) > (if (it.key in ownKeys) 1 else 0) }) {
                    "A document belongs to another manga"
                }
                for (file in files) {
                    currentCoroutineContext().ensureActive()
                    check(!file.directory && file.key != root.key && documents.contains(root, file)) {
                        "Unsafe deletion target"
                    }
                }
            }
            // I/O can still fail after preflight; the recovery scan below handles that case.
            for (book in targets) {
                val root = documents.root(book.rootUri)
                val files = (book.chapters.flatMap { c -> if (c.node.directory) c.pages else listOf(c.node) } + book.sidecars).distinctBy { it.key }
                // Preflight above already validated the entire batch. Do not repeat a
                // stale ownership check after earlier selections have been removed.
                for (file in files) {
                    currentCoroutineContext().ensureActive()
                    if (documents.exists(root, file)) documents.delete(root, file)
                }
                // Never hide a collection while any owned physical file remains.
                check(files.none { documents.exists(root, it) }) {
                    "Deletion incomplete for ${book.node.name}; collection remains visible"
                }
                for (folder in (book.chapters.map { it.node }.filter { it.directory } +
                    listOf(book.node).filter { it.directory }).distinctBy { it.key }) {
                    if (folder.key != root.key && documents.exists(root, folder)) {
                        // Empty-only SAF and filesystem cleanup. Unknown/new files prevent
                        // a misleading success and remain visible after recovery scan.
                        documents.deleteEmptyDirectory(root, folder)
                    }
                }
                val exclusions = readExclusions().toMutableMap().also { it[book.node.key] = book.node.name }
                saveExclusions(exclusions)
                publishLocked(state.value.copy(books = state.value.books.filterNot { it.id == book.id }, excludedCount = exclusions.size))
            }
        } } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { scan() } }
            throw error
        }
        mutableChanges.emit(Unit)
    }

    /** Copy, independently verify, then remove sources. Never merge into an existing tree. */
    suspend fun moveSelectedFiles(ids: Set<Long>, destinationUri: Uri): Int = withContext(Dispatchers.IO) {
        initialize()
        if (destinationUri.scheme == "content") {
            context.contentResolver.takePersistableUriPermission(destinationUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val moved = try {
            mutex.withLock {
                val selected = state.value.books.filter { it.id in ids }
                require(selected.isNotEmpty() && selected.size == ids.size) { "Selected collections changed" }
                val destination = documents.root(destinationUri.toString())
                check(destination.directory) { "Choose a destination folder" }
                check(state.value.roots.any { it.uri == destinationUri.toString() }) {
                    "Add the destination folder to the local library before moving"
                }
                val ownership = state.value.books.flatMap { book ->
                    (book.chapters.flatMap { it.pages + it.node } + book.sidecars).distinctBy { it.key }
                        .map { it.key to book.id }
                }.groupBy({ it.first }, { it.second })
                data class SourceTree(val root: Node, val top: Node, val files: List<Node>, val folders: List<Node>)
                val trees = selected.map { book ->
                    currentCoroutineContext().ensureActive()
                    val root = documents.root(book.rootUri)
                    check(book.node.key != root.key && documents.contains(root, book.node)) { "Unsafe source" }
                    check(!documents.contains(root, destination) && !documents.contains(destination, root)) {
                        "Destination must not overlap source library root"
                    }
                    val files = ArrayList<Node>()
                    val folders = ArrayList<Node>()
                    val pending = java.util.ArrayDeque<Node>()
                    val visited = HashSet<String>()
                    pending.add(book.node)
                    while (!pending.isEmpty()) {
                        currentCoroutineContext().ensureActive()
                        val node = pending.removeFirst()
                        check(visited.add(node.key) && visited.size <= 100_000) { "Cyclic or oversized collection" }
                        check(documents.contains(root, node)) { "Document outside source root" }
                        check(ownership[node.key].orEmpty().distinct().all { it == book.id }) {
                            "Shared document cannot be moved"
                        }
                        if (node.directory) {
                            folders += node
                            documents.children(root, node).forEach { pending.add(it) }
                        } else files += node
                    }
                    check(files.isNotEmpty()) { "Cannot move an empty collection" }
                    SourceTree(root, book.node, files, folders)
                }
                val names = trees.map { it.top.name.lowercase(java.util.Locale.ROOT) }
                check(names.size == names.toSet().size) { "Selected collections have conflicting names" }
                val existing = documents.children(destination, destination)
                    .mapTo(HashSet()) { it.name.lowercase(java.util.Locale.ROOT) }
                check(names.none { it in existing }) { "Destination filename collision" }
                val created = ArrayList<Node>()
                val copiedFiles = ArrayList<Pair<Node, Node>>()
                suspend fun copyTree(source: Node, targetParent: Node, sourceRoot: Node) {
                    currentCoroutineContext().ensureActive()
                    if (source.directory) {
                        val folder = documents.createDirectory(targetParent, source.name)
                        created += folder
                        check(folder.name == source.name) { "Provider renamed destination folder" }
                        for (child in documents.children(sourceRoot, source)) {
                            check(documents.contains(sourceRoot, child)) { "Source escaped its root" }
                            copyTree(child, folder, sourceRoot)
                        }
                    } else {
                        val copy = documents.createFile(targetParent, source.name)
                        created += copy
                        check(copy.name == source.name) { "Provider renamed destination file" }
                        val originalDigest = java.security.MessageDigest.getInstance("SHA-256")
                        var originalSize = 0L
                        documents.input(source).use { input ->
                            documents.output(copy).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    originalDigest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                    originalSize += count
                                }
                            }
                        }
                        val copyDigest = java.security.MessageDigest.getInstance("SHA-256")
                        var copySize = 0L
                        documents.input(copy).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copyDigest.update(buffer, 0, count)
                                copySize += count
                            }
                        }
                        check(originalSize == copySize && originalDigest.digest().contentEquals(copyDigest.digest())) {
                            "Destination verification failed for ${source.name}"
                        }
                        copiedFiles += source to copy
                    }
                }
                try {
                    for (tree in trees) copyTree(tree.top, destination, tree.root)
                    check(copiedFiles.size == trees.sumOf { it.files.size }) { "Copy incomplete" }
                } catch (error: Exception) {
                    // Source remains untouched until all destination files are verified.
                    for (node in created.asReversed()) runCatching {
                        if (node.directory) documents.deleteEmptyDirectory(destination, node)
                        else documents.delete(destination, node)
                    }
                    throw error
                }
                // Recheck source/destination equality immediately before any deletion.
                // An external writer may have modified a source after its initial copy.
                val verificationContext = currentCoroutineContext()
                try {
                    for ((source, copy) in copiedFiles) {
                        LocalMoveVerifier.verify(
                            source = { documents.input(source) },
                            destination = { documents.input(copy) },
                            checkCancelled = { verificationContext.ensureActive() },
                        )
                    }
                } catch (error: Exception) {
                    // Verification failed before deletion: original files are intact.
                    for (node in created.asReversed()) runCatching {
                        if (node.directory) documents.deleteEmptyDirectory(destination, node)
                        else documents.delete(destination, node)
                    }
                    throw error
                }
                var movedCount = 0
                for (tree in trees) {
                    currentCoroutineContext().ensureActive()
                    for (file in tree.files) {
                        currentCoroutineContext().ensureActive()
                        documents.delete(tree.root, file)
                    }
                    // Deepest first, empty-only; never recursively delete unknown additions.
                    for (folder in tree.folders.asReversed()) {
                        documents.deleteEmptyDirectory(tree.root, folder)
                    }
                    movedCount++
                }
                movedCount
            }
        } catch (error: Exception) {
            // Once source deletion starts, verified copies must be retained.
            withContext(NonCancellable) { runCatching { scan() } }
            throw error
        }
        scan()
        moved
    }

    suspend fun exportSelected(ids: Set<Long>): List<File> = withContext(Dispatchers.IO) {
        initialize()
        val selected = state.value.books.filter { it.id in ids }
        require(selected.isNotEmpty()) { "No collections selected" }
        val directory = File(context.cacheDir, "smart-local-exports").apply { mkdirs() }
        val exported = ArrayList<File>()
        try {
            for (book in selected) {
                currentCoroutineContext().ensureActive()
                val root = documents.root(book.rootUri)
                val output = File.createTempFile("local-", ".zip", directory)
                try {
                    var bytes = 0L
                    val entries = (book.chapters.flatMap { chapter ->
                        if (chapter.node.directory) chapter.pages.map { chapter.node.name to it }
                        else listOf("chapters" to chapter.node)
                    } + book.sidecars.map { "metadata" to it }).distinctBy { it.second.key }
                    ZipOutputStream(output.outputStream().buffered()).use { zip ->
                        for ((index, item) in entries.withIndex()) {
                            val (folder, node) = item
                            currentCoroutineContext().ensureActive()
                            check(!node.directory && documents.contains(root, node)) { "Export source is outside selected root" }
                            val safeFolder = folder.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(60).ifBlank { "files" }
                            val safeName = node.name.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(100).ifBlank { "file" }
                            zip.putNextEntry(ZipEntry("$safeFolder/$index-$safeName"))
                            documents.input(node).use { input ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    bytes += read
                                    check(bytes <= 1024L * 1024 * 1024) { "Export exceeds 1 GiB safety limit" }
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                    exported += output
                } catch (error: Exception) {
                    output.delete()
                    throw error
                }
            }
            exported.toList()
        } catch (error: Exception) {
            exported.forEach { it.delete() }
            throw error
        }
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
                if (chapter.node.directory && chapter.node.uri.toUri().scheme == "file" && chapter.node.key != root.key && documents.exists(root, chapter.node) && documents.children(root, chapter.node).isEmpty()) documents.delete(root, chapter.node)
            }
        } } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { scan() } }
            throw error
        }
        scan()
    }

    suspend fun setDisplayOptions(extensions: Boolean, filter: LocalReadingFilter = readingFilter, order: LocalLibrarySort = sort) = withContext(Dispatchers.IO) {
        check(prefs.edit().putBoolean("extensions", extensions).putString("filter", filter.name).putString("sort", order.name).commit())
        db.withTransaction {
            for (book in state.value.books) {
                currentCoroutineContext().ensureActive()
                dataRepository.storeManga(book.toManga(extensions, true), replaceExisting = true)
            }
        }
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

    /**
     * Cover-sized chapter preview only. Never enumerate reader pages, render every PDF page,
     * or populate the reader's page cache. Reuses bounded archive/PDF cover extraction.
     */
    suspend fun chapterGalleryThumbnail(chapter: MangaChapter): ByteArray? = withContext(Dispatchers.IO) {
        val (book, localChapter) = requireChapter(chapter.url)
        val candidates = if (localChapter.node.directory) localChapter.pages.take(1)
            else listOf(localChapter.node)
        if (candidates.isEmpty()) return@withContext null
        val plan = LocalCoverPlan(book.rootUri, candidates, book.scannedAt)
        val cacheId = "${book.id}:${localChapter.id}".longHashCode()
        val isCurrent = {
            state.value.books.any { current ->
                current.id == book.id && current.rootUri == book.rootUri &&
                    current.chapters.any { it.id == localChapter.id && it.node == localChapter.node }
            }
        }
        chapterGalleryCache.getOrGenerateScoped(cacheId, plan, isCurrent) { publish ->
            contentReader.cover(plan, publish)
        }
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
    internal suspend fun coverFingerprint(id: Long): String? = coverPlan(id)?.second?.fingerprint?.let {
        "$it:${coverCache.clearEpoch}"
    }

    suspend fun coverCacheReport(): SmartLocalCoverCacheReport = coverCache.report()
    suspend fun clearCoverCache() = coverCache.clear()
    fun resetCoverDiagnostics() = coverCache.resetDiagnostics()
    internal fun recordCoverPresentation(id: Long, memoryHit: Boolean, elapsedNanos: Long) {
        coverCache.diagnostics.record(SmartLocalCoverDiagnostics.Event(
            if (memoryHit) SmartLocalCoverDiagnostics.Reason.PRESENTATION_MEMORY_HIT else SmartLocalCoverDiagnostics.Reason.PRESENTATION_LOAD,
            cacheKey = coverDigest(id.toString().toByteArray()), elapsedNanos = elapsedNanos,
        ))
    }

    private suspend fun coverPlan(id: Long): Pair<LocalBook, LocalCoverPlan>? {
        val book = book(id) ?: return null
        return book to book.coverPlan
    }

    suspend fun cover(id: Long): ByteArray? = withContext(Dispatchers.IO) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val (book, plan) = coverPlan(id) ?: return@withContext null
            val isCurrent = { state.value.books.firstOrNull { it.id == id } === book }
            val bytes = coverCache.getOrGenerateScoped(id, plan, isCurrent) { publish ->
                contentReader.cover(plan, publish)
            }
            if (isCurrent()) return@withContext bytes
            // Refresh/detach raced this request. Never return a superseded source version.
        }
        @Suppress("UNREACHABLE_CODE") null
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

