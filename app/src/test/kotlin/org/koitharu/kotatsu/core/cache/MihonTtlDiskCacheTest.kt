package org.koitharu.kotatsu.core.cache

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.ZERO

class MihonTtlDiskCacheTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun hitMissExpiryAndIndexReopen() = runBlocking {
        val root = temp.newFolder("chapter")
        val entries = root.resolve("entries")
        val index = root.resolve("indexes/pages.index")
        var clock = 1_000_000L
        fun cache() = MihonTtlDiskCache(
            directory = entries,
            indexFile = index,
            ttl = 1.hours,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            saveDelay = ZERO,
            now = { clock },
        )
        val first = cache()
        assertNull(first.get("chapter-1"))
        first.put("chapter-1") { it.write("page-list".toByteArray()) }
        assertNotNull(first.get("chapter-1"))
        assertEquals("page-list", first.open("chapter-1")!!.bufferedReader().use { it.readText() })
        val reopened = cache()
        assertNotNull(reopened.get("chapter-1"))
        clock += 3_600_000L
        assertNull(reopened.get("chapter-1"))
        assertEquals(0L, reopened.totalSize())
    }

    @Test fun failedWriteLeavesPreviousEntryIntact() = runBlocking {
        val root = temp.newFolder("atomic")
        val cache = MihonTtlDiskCache(
            directory = root.resolve("entries"),
            indexFile = root.resolve("index/cache.index"),
            ttl = 1.hours,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            saveDelay = ZERO,
        )
        cache.put("image") { it.write(byteArrayOf(1, 2, 3)) }
        try {
            cache.put("image") {
                it.write(byteArrayOf(4))
                throw java.io.IOException("interrupted download")
            }
            throw AssertionError("Expected write failure")
        } catch (expected: java.io.IOException) {
            assertEquals("interrupted download", expected.message)
        }
        assertTrue(cache.get("image")!!.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(cache.remove("image"))
        assertFalse(cache.remove("image"))
    }
}
