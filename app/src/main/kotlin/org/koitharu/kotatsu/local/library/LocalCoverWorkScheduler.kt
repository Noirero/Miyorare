package org.koitharu.kotatsu.local.library

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Resource limits belong to extraction, not persistent-cache ownership or same-key flights. */
internal class LocalCoverWorkScheduler {
    private val pdf = Semaphore(SOURCE_PARALLELISM)
    private val archive = Semaphore(SOURCE_PARALLELISM)
    private val image = Semaphore(SOURCE_PARALLELISM)
    private val bitmap = Semaphore(BITMAP_PARALLELISM)

    suspend fun <T> source(extension: String, block: suspend () -> T): T = when (extension) {
        "pdf" -> pdf
        "cbz", "zip", "epub" -> archive
        else -> image
    }.withPermit { block() }

    suspend fun <T> bitmap(block: suspend () -> T): T = bitmap.withPermit { block() }

    companion object {
        // Each source class retains its permit through publication: queued encoded buffers are
        // bounded too. FIFO coroutine semaphores prevent starvation within each class. Acquisition
        // is always source -> bitmap; PDF uses LocalPdfCache's existing app-wide render limit.
        const val SOURCE_PARALLELISM = 2
        const val BITMAP_PARALLELISM = 2
    }
}
