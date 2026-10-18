package org.koitharu.kotatsu.local.data.index

/**
 * Persistent, non-destructive link to an existing physical download. Chapter redirects are written
 * only by lossless migration from its approved P1 plan; ordinary aliases keep their legacy format.
 */
internal data class DownloadPathAlias(
    val localMangaId: Long,
    val path: String,
    val chapterIds: Map<Long, Long> = emptyMap(),
) {
    fun sameContainer(other: DownloadPathAlias): Boolean = localMangaId == other.localMangaId && path == other.path

    fun forMigration(targets: Map<Long, Long>): DownloadPathAlias = copy(
        chapterIds = if (chapterIds.isEmpty()) targets.toMap() else chapterIds.mapValues { (_, current) -> targets[current] ?: current },
    )

    fun serialize(): String {
        val header = if (chapterIds.isEmpty()) localMangaId.toString() else {
            "m1:" + localMangaId + ":" + chapterIds.toSortedMap().entries.joinToString(",") { (from, to) -> from.toString() + ">" + to }
        }
        return header + "|" + path
    }

    companion object {
        fun parse(raw: String?): DownloadPathAlias? {
            if (raw.isNullOrEmpty()) return null
            val separator = raw.indexOf('|')
            if (separator <= 0 || separator == raw.lastIndex) return null
            val header = raw.substring(0, separator)
            val path = raw.substring(separator + 1).takeIf { it.isNotBlank() } ?: return null
            if (!header.startsWith("m1:")) return header.toLongOrNull()?.let { DownloadPathAlias(it, path) }
            val parts = header.removePrefix("m1:").split(':', limit = 2)
            if (parts.size != 2 || parts[1].isEmpty()) return null
            val localId = parts[0].toLongOrNull() ?: return null
            val chapters = LinkedHashMap<Long, Long>()
            for (entry in parts[1].split(',')) {
                val pair = entry.split('>', limit = 2)
                if (pair.size != 2) return null
                val from = pair[0].toLongOrNull() ?: return null
                val to = pair[1].toLongOrNull() ?: return null
                if (chapters.put(from, to) != null) return null
            }
            return DownloadPathAlias(localId, path, chapters)
        }
    }
}
