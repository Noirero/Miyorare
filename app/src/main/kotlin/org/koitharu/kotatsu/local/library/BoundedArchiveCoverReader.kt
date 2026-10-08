package org.koitharu.kotatsu.local.library

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Finds a usable archive cover without materializing or exhaustively traversing the archive.
 *
 * The budgets are deliberately hard: collection browsing must never turn a missing/late cover into
 * an unbounded scan of a large CBZ/ZIP/EPUB. A first valid image is retained as fallback while an
 * explicitly cover-like entry is preferred if it appears inside the bounded discovery window.
 */
internal object BoundedArchiveCoverReader {
    internal const val MAX_ENTRIES = 64
    internal const val MAX_IMAGE_CANDIDATES = 8
    internal const val MAX_CANDIDATE_BYTES = 8 * 1024 * 1024
    internal const val MAX_TOTAL_CANDIDATE_BYTES = 32 * 1024 * 1024

    fun read(
        input: InputStream,
        isSafeImage: (String) -> Boolean,
        isPreferred: (String) -> Boolean,
        isValid: (ByteArray) -> Boolean,
        checkActive: () -> Unit = {},
    ): ByteArray? {
        var entriesSeen = 0
        var candidatesSeen = 0
        var candidateBytesRead = 0
        var fallback: ByteArray? = null

        ZipInputStream(input.buffered()).use { zip ->
            while (entriesSeen < MAX_ENTRIES && candidatesSeen < MAX_IMAGE_CANDIDATES &&
                candidateBytesRead < MAX_TOTAL_CANDIDATE_BYTES
            ) {
                checkActive()
                val entry = zip.nextEntry ?: break
                entriesSeen++
                try {
                    if (entry.isDirectory || !isSafeImage(entry.name)) continue
                    candidatesSeen++
                    val remaining = MAX_TOTAL_CANDIDATE_BYTES - candidateBytesRead
                    if (remaining <= 0) break
                    val limit = minOf(MAX_CANDIDATE_BYTES, remaining)
                    val candidate = readEntry(zip, limit)
                    candidateBytesRead += candidate.bytesRead
                    val bytes = candidate.bytes ?: continue
                    if (!isValid(bytes)) continue
                    if (isPreferred(entry.name)) return bytes
                    if (fallback == null) fallback = bytes
                } finally {
                    runCatching { zip.closeEntry() }
                }
            }
        }
        return fallback
    }

    private fun readEntry(input: InputStream, limit: Int): CandidateRead {
        val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, limit))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val allowed = minOf(buffer.size, limit - total + 1)
            if (allowed <= 0) return CandidateRead(null, total)
            val count = input.read(buffer, 0, allowed)
            if (count < 0) return CandidateRead(output.toByteArray(), total)
            total += count
            if (total > limit) return CandidateRead(null, total)
            output.write(buffer, 0, count)
        }
    }

    private data class CandidateRead(val bytes: ByteArray?, val bytesRead: Int)
}
