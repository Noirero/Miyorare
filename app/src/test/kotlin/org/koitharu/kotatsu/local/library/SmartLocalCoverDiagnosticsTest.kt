package org.koitharu.kotatsu.local.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import org.koitharu.kotatsu.local.library.SmartLocalCoverDiagnostics.Event
import org.koitharu.kotatsu.local.library.SmartLocalCoverDiagnostics.Reason
import java.io.File

class SmartLocalCoverDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun plan(modified: Long = 1) = LocalCoverPlan("root", listOf(Node("book", "content://test/book", "book.pdf", false, 100, modified)), 1)

    @Test fun reasonsSeparatePersistentDecodeFromSourceRegenerationAndCorruption() = runBlocking {
        val directory = temporary.newFolder()
        val cache = SmartLocalCoverCache(directory)
        var generates = 0
        suspend fun request(plan: LocalCoverPlan = plan()) = cache.getOrGenerate(42, plan) {
            generates++; GeneratedLocalCover(byteArrayOf(generates.toByte()), 0)
        }
        request(); request(); request(plan(2))
        val entry = directory.listFiles()!!.single()
        entry.writeBytes(byteArrayOf(0))
        request(plan(2))
        val snapshot = cache.diagnostics.snapshot()
        assertEquals(1L, snapshot.count(Reason.MISS_ABSENT))
        assertEquals(1L, snapshot.count(Reason.CACHE_HIT))
        assertEquals(1L, snapshot.count(Reason.MISS_FINGERPRINT))
        assertEquals(1L, snapshot.count(Reason.MISS_CORRUPT))
        assertEquals(3L, snapshot.count(Reason.GENERATED))
        assertEquals(3, generates)
        assertFalse(snapshot.recent.single { it.reason == Reason.CACHE_HIT }.sourceOpened)
        assertTrue(snapshot.recent.single { it.reason == Reason.MISS_FINGERPRINT }.fingerprintMismatch)
        assertTrue(snapshot.recent.filter { it.reason == Reason.PUBLISHED }.all { it.sourceKind == "PDF" && it.candidateIndex == 0 })
    }

    @Test fun byteAndCountEvictionHaveDistinctReasonsAndNewestEntrySurvives() = runBlocking {
        for ((budget, entries, reason) in listOf(Triple(500L, 10, Reason.EVICT_BYTES), Triple(10_000L, 2, Reason.EVICT_ENTRY_COUNT))) {
            val directory = temporary.newFolder()
            val cache = SmartLocalCoverCache(directory, budget, entries)
            repeat(10) { id -> cache.getOrGenerate(id.toLong(), plan()) { GeneratedLocalCover(ByteArray(100), 0) } }
            assertTrue(cache.diagnostics.snapshot().count(reason) > 0)
            assertTrue(cache.stats().bytes <= budget)
            assertTrue(cache.stats().entries <= entries)
            SmartLocalCoverCache(directory, budget, entries).getOrGenerate(9, plan()) { fail("Newest entry lost"); null }
        }
    }

    @Test fun clearEpochAndDiagnosticsResetHaveIndependentScope() = runBlocking {
        val parent = temporary.newFolder()
        val reader = File(parent, "local_pdf_pages/page.png").apply { parentFile!!.mkdirs(); writeText("Reader") }
        val cache = SmartLocalCoverCache(File(parent, "smart-local-covers"))
        cache.getOrGenerate(42, plan()) { GeneratedLocalCover(byteArrayOf(1), 0) }
        cache.resetDiagnostics()
        assertEquals(1, cache.stats().entries)
        assertEquals(0L, cache.diagnostics.snapshot().count(Reason.PUBLISHED))
        val before = cache.clearEpoch
        cache.clear()
        assertEquals(before + 1, cache.clearEpoch)
        assertEquals(0, cache.stats().entries)
        assertEquals("Reader", reader.readText())
        assertEquals(1L, cache.diagnostics.snapshot().count(Reason.CLEAR))
        cache.getOrGenerate(42, plan()) { GeneratedLocalCover(byteArrayOf(2), 0) }
        assertEquals(1L, cache.diagnostics.snapshot().count(Reason.MISS_ABSENT))
    }

    @Test fun failedAtomicPublicationPropagatesAndNeverLeavesAPartialOrPoisonedEntry() = runBlocking {
        val directory = temporary.newFolder()
        val cache = SmartLocalCoverCache(directory)
        cache.stats()
        val target = File(directory, "${coverDigest("42".toByteArray())}.thumb").apply { mkdir() }
        val error = runCatching { cache.getOrGenerate(42, plan()) { GeneratedLocalCover(byteArrayOf(1), 0) } }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".partial") })
        assertEquals(0, cache.stats().entries)
        assertEquals(1L, cache.diagnostics.snapshot().count(Reason.PUBLICATION_FAILED))
        assertTrue(target.delete())
        assertArrayEquals(byteArrayOf(2), cache.getOrGenerate(42, plan()) { GeneratedLocalCover(byteArrayOf(2), 0) })
        assertEquals(1, cache.stats().entries)
    }

    @Test fun ringIsBoundedAndResetDoesNotReturnMutableInternalState() {
        val diagnostics = SmartLocalCoverDiagnostics()
        repeat(1000) { diagnostics.record(Event(Reason.CACHE_HIT, entryBytes = it.toLong())) }
        val before = diagnostics.snapshot()
        assertEquals(1000L, before.count(Reason.CACHE_HIT))
        assertEquals(256, before.recent.size)
        diagnostics.reset()
        assertEquals(0L, diagnostics.snapshot().count(Reason.CACHE_HIT))
        assertEquals(1000L, before.count(Reason.CACHE_HIT))
    }
}
