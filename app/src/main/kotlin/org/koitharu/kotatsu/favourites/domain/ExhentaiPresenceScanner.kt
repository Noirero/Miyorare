package org.koitharu.kotatsu.favourites.domain

import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

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
    ) {
        fun hasDownloaded(title: String): Boolean = candidatesFor(title).isNotEmpty()
        fun candidatesFor(title: String): List<String> = candidatesByTitle[titleKey(title)].orEmpty()
    }

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
            scanReadableRoot(root, otherSpaceRoot, old, onProgress)
        } catch (_: IOException) {
            unavailable()
        } catch (_: SecurityException) {
            unavailable()
        }
    }

    private fun scanReadableRoot(
        root: File, otherSpaceRoot: File?, old: Snapshot?,
        onProgress: (Int, Int) -> Unit,
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
        for ((index, entry) in entries.withIndex()) {
            val (title, container) = entry
            val key = titleKey(title)
            if (key.isNotBlank()) {
                val candidates = if (container.isDirectory) container.listFiles()
                    ?: return unavailable() else arrayOf(container)
                for (candidate in candidates) {
                    if (candidate.isFile && candidate.isCbz() && !candidate.isHidden && visible(candidate)) {
                        found.getOrPut(key) { LinkedHashSet() }.add(candidate.canonicalPath)
                    }
                }
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
            if (old != null && changed.isEmpty()) old else Snapshot(root.path, next),
            Availability.AVAILABLE, entries.size, changed,
        )
    }

    private fun File.isCbz(): Boolean = extension.equals("cbz", ignoreCase = true)
    private fun String.isInside(root: String): Boolean =
        this == root || startsWith(root.trimEnd(File.separatorChar) + File.separator)

    companion object {
        private val duplicateSuffix = Regex("_\\d+$")
        fun titleKey(title: String): String = Normalizer.normalize(
            title.trim().replace(duplicateSuffix, ""), Normalizer.Form.NFC,
        ).lowercase(Locale.ROOT)
    }
}
