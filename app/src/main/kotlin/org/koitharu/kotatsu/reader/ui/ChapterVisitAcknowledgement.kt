package org.koitharu.kotatsu.reader.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * A visit token is stable across page events but changes for each explicit chapter opening.
 * It distinguishes a fresh post-unread visit from events still queued by an older reader.
 */
internal class ChapterVisitAcknowledgement {
    private var lastChapter = Long.MIN_VALUE
    private var visitToken = 0L

    @Synchronized
    fun capture(chapterId: Long): Pair<Boolean, Long> {
        val isNewVisit = lastChapter != chapterId
        if (isNewVisit) {
            lastChapter = chapterId
            visitToken = nextVisitToken.incrementAndGet()
        }
        return isNewVisit to visitToken
    }

    fun shouldAcknowledge(chapterId: Long): Boolean = capture(chapterId).first

    @Synchronized
    fun reset() {
        lastChapter = Long.MIN_VALUE
    }

    private companion object {
        val nextVisitToken = AtomicLong(0L)
    }
}
