package org.koitharu.kotatsu.favourites.domain

import java.io.File
import java.io.IOException
import org.koitharu.kotatsu.sources.compat.EhentaiLegacyDownloadResolver

/**
 * Filesystem-presence discovery for ExHentai. This does not open ZIPs, write ownership
 * receipts, or grant delete authorization. Call from a background IO dispatcher.
 */
internal class ExhentaiPresenceScanner(recognizedSourceFolders: Set<String>) {
    private val recognized = recognizedSourceFolders.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    init { require(recognized.isNotEmpty()) }

    data class Snapshot(
        val activeRoot: String,
        val candidatesByTitle: Map<String, List<String>>,
        val cachedContainers: Map<String, ContainerCache> = emptyMap(),
    ) {
        fun hasDownloaded(title: String): Boolean = candidatesFor(title).isNotEmpty()
        fun candidatesFor(title: String): List<String> = candidatesByTitle[titleKey(title)].orEmpty()
    }

    data class ContainerCache(val modifiedAt: Long, val candidates: List<String>)

    enum class Availability { AVAILABLE, UNAVAILABLE }

    data class Result(
        val snapshot: Snapshot,
        val availability: Availability,
        val examinedEntries: Int,
        val changedTitles: Set<String>,
    )

    fun scan(
        activeRoot: File,
        otherSpaceRoot: File? = null,
        previous: Snapshot? = null,
        full: Boolean = false,
        onProgress: (examined: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val root = try { activeRoot.canonicalFile }
            catch (_: IOException) { activeRoot.absoluteFile }
            catch (_: SecurityException) { activeRoot.absoluteFile }
        val old = previous?.takeIf { it.activeRoot == root.path }
        fun unavailable() = Result(
            old ?: Snapshot(root.path, emptyMap()),
            Availability.UNAVAILABLE, 0, emptySet(),
        )
        if (!root.isDirectory || !root.canRead()) return unavailable()
        return try {
            scanReadableRoot(root, otherSpaceRoot, old, full, onProgress)
        } catch (_: IOException) {
            unavailable()
        } catch (_: SecurityException) {
            unavailable()
        }
    }

    private fun scanReadableRoot(
        root: File, otherSpaceRoot: File?, old: Snapshot?,
        full: Boolean, onProgress: (Int, Int) -> Unit,
    ): Result {
        val other = otherSpaceRoot?.canonicalFile?.path
        fun visible(file: File): Boolean {
            val path = file.canonicalPath
            if (!path.isInside(root.path)) return false
            return other == null || other == root.path || !other.isInside(root.path) ||
                !path.isInside(other)
        }
        fun unavailable() = Result(
            old ?: Snapshot(root.path, emptyMap()),
            Availability.UNAVAILABLE, 0, emptySet(),
        )

        val entries = ArrayList<Pair<String, File>>()
        for (contentRoot in listOf(File(root, "downloads"), root)) {
            if (!contentRoot.exists()) continue
            if (!contentRoot.isDirectory || !contentRoot.canRead()) return unavailable()
            for (source in contentRoot.listFiles() ?: return unavailable()) {
                if (!source.isDirectory || source.isHidden || !visible(source) ||
                    source.name.lowercase(Locale.ROOT) !in recognized) continue
                for (entry in source.listFiles() ?: return unavailable()) {
                    if (!entry.isHidden && visible(entry) &&
                        (entry.isDirectory || (entry.isFile && entry.isCbz()))) {
                        entries += (if (entry.isDirectory) entry.name else entry.nameWithoutExtension) to entry
                    }
                }
            }
        }

        val found = LinkedHashMap<String, LinkedHashSet<String>>()
        val cachedContainers = LinkedHashMap<String, ContainerCache>()
        for ((index, entry) in entries.withIndex()) {
            val (title, container) = entry
            val key = titleKey(title)
            if (key.isNotBlank()) {
                val path = container.canonicalPath
                val modified = container.lastModified()
                val cached = if (full) null else old?.cachedContainers?.get(path)
                // Directory mtime alone is not reliable: some filesystems have coarse timestamp
                // resolution, and copied files may preserve directory timestamps. Always compare
                // the shallow CBZ listing, without opening or hashing archive contents.
                val files = if (container.isDirectory) container.listFiles()
                    ?: return unavailable() else arrayOf(container)
                val current = files.filter { it.isFile && it.isCbz() && !it.isHidden && visible(it) }
                    .map { it.canonicalPath }.sorted()
                val candidates = if (cached != null && cached.modifiedAt == modified &&
                    cached.candidates == current) cached.candidates else current
                cachedContainers[path] = ContainerCache(modified, candidates)
                found.getOrPut(key) { LinkedHashSet() }.addAll(candidates)
            }
            onProgress(index + 1, entries.size)
        }
        if (entries.isEmpty()) onProgress(0, 0)

        val next = found.mapValues { (_, paths) -> paths.sorted() }
        val previous = old?.candidatesByTitle.orEmpty()
        val changed = (previous.keys + next.keys).filterTo(LinkedHashSet()) {
            previous[it] != next[it]
        }
        return Result(
            if (old != null && changed.isEmpty() && old.cachedContainers == cachedContainers) old
                else Snapshot(root.path, next, cachedContainers),
            Availability.AVAILABLE, entries.size, changed,
        )
    }

    private fun File.isCbz(): Boolean = extension.equals("cbz", ignoreCase = true)
    private fun String.isInside(root: String): Boolean =
        this == root || startsWith(root.trimEnd(File.separatorChar) + File.separator)

    companion object {
        private val duplicateSuffix = Regex("_\\d+$")
        // Match the same sanitized ExHentai titles accepted by the legacy read resolver.
        fun titleKey(title: String): String = EhentaiLegacyDownloadResolver.normalizedTitle(
            title.trim().replace(duplicateSuffix, ""),
        )
    }
}
