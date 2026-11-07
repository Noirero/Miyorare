package org.koitharu.kotatsu.reader.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * A manual-unread action remains authoritative for the rest of the current visit.
 * A subsequent explicit normal chapter opening or transition starts another visit.
 *
 * Thread-safe because page changes, Activity lifecycle, and loading jobs can overlap.
 */
internal class ChapterVisitAcknowledgement {
    private val lastAcknowledgedChapter = AtomicLong(Long.MIN_VALUE)

    fun shouldAcknowledge(chapterId: Long): Boolean =
        lastAcknowledgedChapter.getAndSet(chapterId) != chapterId

    fun reset() {
        lastAcknowledgedChapter.set(Long.MIN_VALUE)
    }
}
