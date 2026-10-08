package org.koitharu.kotatsu.local.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SmartLocalCoverCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun coldReopenWarmAndChangedSourceUsePersistentAuthority() = runBlocking {
        val directory = temporary.newFolder()
        val count = AtomicInteger()
        val firstPlan = plan(node())
        val generate = suspend { GeneratedLocalCover(byteArrayOf(count.incrementAndGet().toByte()), 0) }
        val first = SmartLocalCoverCache(directory).getOrGenerate(42, firstPlan, generate = generate)
        assertEquals(1, count.get())
        val reopened = SmartLocalCoverCache(directory)
        assertArrayEquals(first, reopened.getOrGenerate(42, firstPlan, generate = generate))
        assertEquals(1, count.get())
        val changed = reopened.getOrGenerate(42, plan(node(modified = 2)), generate = generate)
        assertEquals(2, count.get())
        assertFalse(first!!.contentEquals(changed!!))
        assertArrayEquals(changed, SmartLocalCoverCache(directory).getOrGenerate(42, plan(node(modified = 2)), generate = generate))
        assertEquals(2, count.get())
    }

    @Test fun onlyWinningSourceAndHigherPriorityFallbacksInvalidate() = runBlocking {
        val cache = SmartLocalCoverCache(temporary.newFolder())
        var count = 0
        suspend fun request(plan: LocalCoverPlan) = cache.getOrGenerate(42, plan) {
            count++; GeneratedLocalCover(byteArrayOf(count.toByte()), 1)
        }
        val sidecar = node("cover.jpg")
        val pdf = node("first.pdf")
        val later = node("later.pdf")
        val initial = request(plan(sidecar, pdf, later))
        assertArrayEquals(initial, request(plan(sidecar, pdf, node("later.pdf", modified = 2))))
        assertEquals(1, count)
        request(plan(node("cover.jpg", modified = 2), pdf, later))
        assertEquals(2, count)
        request(plan(node("cover.jpg", modified = 2), node("first.pdf", size = 200), later))
        assertEquals(3, count)
    }

    @Test fun simultaneousRequestsGenerateOnce() = runBlocking {
        val cache = SmartLocalCoverCache(temporary.newFolder())
        val count = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val requests = List(20) { async {
            cache.getOrGenerate(42, plan(node())) {
                count.incrementAndGet(); started.complete(Unit); proceed.await()
                GeneratedLocalCover(byteArrayOf(9), 0)
            }
        } }
        started.await(); proceed.complete(Unit)
        requests.awaitAll().forEach { assertArrayEquals(byteArrayOf(9), it) }
        assertEquals(1, count.get())
    }

    @Test fun generationConcurrencyIsBoundedAcrossDifferentSources() = runBlocking {
        val cache = SmartLocalCoverCache(temporary.newFolder())
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val twoStarted = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val jobs = List(30) { id -> async {
            cache.getOrGenerate(id.toLong(), plan(node("$id.pdf"))) {
                val current = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, current) }
                if (current == 2) twoStarted.complete(Unit)
                try { proceed.await(); GeneratedLocalCover(byteArrayOf(1), 0) }
                finally { active.decrementAndGet() }
            }
        } }
        twoStarted.await(); proceed.complete(Unit); jobs.awaitAll()
        assertEquals(SmartLocalCoverCache.GENERATION_PARALLELISM, peak.get())
    }

    @Test fun cancelledGenerationAndCrashLeftoverAreNeverValidEntries() = runBlocking {
        val directory = temporary.newFolder()
        val cache = SmartLocalCoverCache(directory)
        val started = CompletableDeferred<Unit>()
        val job = async {
            cache.getOrGenerate(42, plan(node())) {
                started.complete(Unit); CompletableDeferred<Unit>().await()
                GeneratedLocalCover(byteArrayOf(1), 0)
            }
        }
        started.await(); job.cancel(); job.join()
        assertEquals(0L, cache.size())
        File(directory, "crash.partial").writeBytes(byteArrayOf(5))
        SmartLocalCoverCache(directory).getOrGenerate(42, plan(node())) { GeneratedLocalCover(byteArrayOf(2), 0) }
        assertFalse(File(directory, "crash.partial").exists())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun corruptPayloadIsAMissAndIsRegenerated() = runBlocking {
        val directory = temporary.newFolder()
        val cache = SmartLocalCoverCache(directory)
        cache.getOrGenerate(42, plan(node())) { GeneratedLocalCover(byteArrayOf(1, 2, 3), 0) }
        val entry = directory.listFiles()!!.single()
        val bytes = entry.readBytes(); bytes[bytes.lastIndex] = 9; entry.writeBytes(bytes)
        var calls = 0
        assertArrayEquals(byteArrayOf(4), SmartLocalCoverCache(directory).getOrGenerate(42, plan(node())) {
            calls++; GeneratedLocalCover(byteArrayOf(4), 0)
        })
        assertEquals(1, calls)
    }

    @Test fun manyLargeSourceVersionsRetainOnlyBoundedSmallArtifacts() = runBlocking {
        val directory = temporary.newFolder()
        val cache = SmartLocalCoverCache(directory, maxBytes = 4096, maxEntries = 3)
        repeat(100) { id -> cache.getOrGenerate(id.toLong(), plan(node("$id.pdf", size = 55L * 1024 * 1024))) {
            GeneratedLocalCover(ByteArray(500), 0)
        } }
        assertTrue(cache.size() <= 4096)
        assertTrue(directory.listFiles()!!.size <= 3)
        assertTrue(directory.listFiles()!!.all { it.length() < 1024 })
        // Reopening retains the newest thumbnail and only lazy-prunes idle or over-budget entries.
        SmartLocalCoverCache(directory, 4096, 3).getOrGenerate(99, plan(node("99.pdf", size = 55L * 1024 * 1024))) {
            fail("Newest thumbnail was unnecessarily deleted on restart"); null
        }
    }

    @Test fun explicitClearDoesNotResurrectAnInflightThumbnailOrTouchOtherOwnership() = runBlocking {
        val parent = temporary.newFolder()
        val directory = File(parent, "thumbnails")
        val reader = File(parent, "smart-local-content/book.pdf").apply { parentFile!!.mkdirs(); writeText("reader") }
        val cache = SmartLocalCoverCache(directory)
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val job = async { cache.getOrGenerate(42, plan(node())) {
            started.complete(Unit); proceed.await(); GeneratedLocalCover(byteArrayOf(1), 0)
        } }
        started.await(); cache.clear(); proceed.complete(Unit); job.await()
        assertEquals(0L, cache.size())
        assertEquals("reader", reader.readText())
    }

    @Test fun unknownSourceMetadataInvalidatesOnRefreshButReusesAcrossRestart() = runBlocking {
        val directory = temporary.newFolder()
        val source = node(modified = 0, size = 0)
        val first = LocalCoverPlan("root", listOf(source), 10)
        SmartLocalCoverCache(directory).getOrGenerate(42, first) { GeneratedLocalCover(byteArrayOf(1), 0) }
        SmartLocalCoverCache(directory).getOrGenerate(42, first) { fail("Restart must reuse indexed version"); null }
        var calls = 0
        SmartLocalCoverCache(directory).getOrGenerate(42, LocalCoverPlan("root", listOf(source), 11)) {
            calls++; GeneratedLocalCover(byteArrayOf(2), 0)
        }
        assertEquals(1, calls)
    }

    private fun node(name: String = "book.pdf", modified: Long = 1, size: Long = 100) =
        Node(name, "content://test/$name", name, false, size, modified)
    private fun plan(vararg nodes: Node) = LocalCoverPlan("root", nodes.toList(), 1)
}
