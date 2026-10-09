package org.koitharu.kotatsu.local.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalCoverWorkSchedulerTest {
    @Test fun blockedPdfAndArchivesDoNotBlockDirectImagesOrSidecars() = runTest {
        val scheduler = LocalCoverWorkScheduler()
        val release = CompletableDeferred<Unit>()
        val heavy = listOf("pdf", "pdf", "cbz", "epub").map { extension -> async {
            scheduler.source(extension) { release.await() }
        } }
        runCurrent()
        val light = listOf("jpg", "png").map { extension -> async {
            scheduler.source(extension) { scheduler.bitmap { extension } }
        } }
        runCurrent()
        assertTrue(light.all { it.isCompleted })
        assertTrue(heavy.none { it.isCompleted })
        release.complete(Unit)
        heavy.awaitAll()
    }

    @Test fun sourceClassesAndSharedBitmapStageHaveExplicitBounds() = runTest {
        val scheduler = LocalCoverWorkScheduler()
        val release = CompletableDeferred<Unit>()
        val active = IntArray(3)
        val peak = IntArray(3)
        val jobs = listOf("pdf", "cbz", "png").flatMapIndexed { lane, extension ->
            List(12) { async {
                scheduler.source(extension) {
                    active[lane]++
                    peak[lane] = maxOf(peak[lane], active[lane])
                    try { release.await() } finally { active[lane]-- }
                }
            } }
        }
        runCurrent()
        assertArrayEquals(IntArray(3) { LocalCoverWorkScheduler.SOURCE_PARALLELISM }, active)
        release.complete(Unit)
        jobs.awaitAll()
        assertArrayEquals(IntArray(3) { LocalCoverWorkScheduler.SOURCE_PARALLELISM }, peak)

        val bitmapRelease = CompletableDeferred<Unit>()
        var bitmaps = 0
        var bitmapPeak = 0
        val preparations = listOf("cbz", "zip", "epub", "png", "jpg", "gif").map { extension -> async {
            scheduler.source(extension) { scheduler.bitmap {
                bitmaps++; bitmapPeak = maxOf(bitmapPeak, bitmaps)
                try { bitmapRelease.await() } finally { bitmaps-- }
            } }
        } }
        runCurrent()
        assertEquals(LocalCoverWorkScheduler.BITMAP_PARALLELISM, bitmaps)
        bitmapRelease.complete(Unit)
        preparations.awaitAll()
        assertEquals(LocalCoverWorkScheduler.BITMAP_PARALLELISM, bitmapPeak)
    }

    @Test fun cancelledWaiterCannotLeakPermitsOrStarveQueuedHeavyWork() = runTest {
        val scheduler = LocalCoverWorkScheduler()
        val release = CompletableDeferred<Unit>()
        val active = List(2) { async { scheduler.source("pdf") { release.await() } } }
        runCurrent()
        val cancelled = async { scheduler.source("pdf") { fail("Cancelled waiter entered") } }
        val order = ArrayList<Int>()
        val queued = List(6) { id -> async { scheduler.source("pdf") { order += id } } }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(order.isEmpty())
        release.complete(Unit)
        active.awaitAll(); queued.awaitAll()
        assertEquals((0..5).toList(), order)
    }
}
