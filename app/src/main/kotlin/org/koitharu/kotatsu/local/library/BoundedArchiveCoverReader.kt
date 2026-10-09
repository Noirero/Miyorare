package org.koitharu.kotatsu.local.library

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Bounded streaming fallback. Entry traversal, including discarded/non-image payloads, is
 * explicitly read under cancellation and byte limits. ZipInputStream.closeEntry() would drain
 * an arbitrary skipped payload outside those checks; close() itself does not drain an entry.
 */
internal object BoundedArchiveCoverReader {
    internal const val MAX_ENTRIES = 64
    internal const val MAX_IMAGE_CANDIDATES = 8
    internal const val MAX_CANDIDATE_BYTES = 8 * 1024 * 1024
    internal const val MAX_TOTAL_CANDIDATE_BYTES = 32 * 1024 * 1024
    internal const val MAX_TRAVERSED_BYTES = 32 * 1024 * 1024

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
        var traversed = 0
        var fallback: ByteArray? = null
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        ZipInputStream(input.buffered()).use { zip ->
            while (entriesSeen < MAX_ENTRIES && candidatesSeen < MAX_IMAGE_CANDIDATES &&
                candidateBytesRead < MAX_TOTAL_CANDIDATE_BYTES && traversed < MAX_TRAVERSED_BYTES
            ) {
                checkActive()
                val entry = zip.nextEntry ?: break
                entriesSeen++
                val candidate = !entry.isDirectory && isSafeImage(entry.name)
                if (candidate) candidatesSeen++
                val limit = minOf(MAX_CANDIDATE_BYTES, MAX_TOTAL_CANDIDATE_BYTES - candidateBytesRead)
                var output = if (candidate) ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, limit)) else null
                var entryBytes = 0
                while (true) {
                    checkActive()
                    val remaining = MAX_TRAVERSED_BYTES - traversed
                    // An entry stopped before EOF/CRC validation cannot be published. Keep only
                    // an earlier complete/valid fallback, and close without draining more bytes.
                    if (remaining <= 0) return fallback
                    val count = zip.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    if (count == 0) throw java.io.IOException("Stalled ZIP entry")
                    traversed += count; entryBytes += count
                    if (entryBytes > limit) output = null
                    output?.write(buffer, 0, count)
                }
                if (!candidate) continue
                candidateBytesRead += entryBytes
                val bytes = output?.toByteArray() ?: continue
                if (!isValid(bytes)) continue
                if (isPreferred(entry.name)) return bytes
                if (fallback == null) fallback = bytes
            }
        }
        return fallback
    }
}
