package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BoundedArchiveCoverReaderTest {

    @Test
    fun `preferred cover wins inside bounded window`() {
        val first = byteArrayOf(1, 2, 3)
        val cover = byteArrayOf(9, 8, 7)
        val archive = zipOf("001.jpg" to first, "cover.jpg" to cover, "002.jpg" to byteArrayOf(4))

        val result = read(archive)

        assertArrayEquals(cover, result)
    }

    @Test
    fun `first valid image is fallback when no explicit cover exists`() {
        val first = byteArrayOf(1, 2, 3)
        val archive = zipOf("001.jpg" to first, "002.jpg" to byteArrayOf(4, 5))

        assertArrayEquals(first, read(archive))
    }

    @Test
    fun `entries after traversal budget are never inspected`() {
        val entries = buildList {
            repeat(BoundedArchiveCoverReader.MAX_ENTRIES) { add("note-$it.txt" to byteArrayOf(0)) }
            add("cover.jpg" to byteArrayOf(9))
        }
        var inspected = 0
        val result = BoundedArchiveCoverReader.read(
            input = ByteArrayInputStream(zipOf(*entries.toTypedArray())),
            isSafeImage = { inspected++; it.endsWith(".jpg") },
            isPreferred = { true },
            isValid = { true },
        )

        assertNull(result)
        assertEquals(BoundedArchiveCoverReader.MAX_ENTRIES, inspected)
    }

    @Test
    fun `oversized candidate is rejected without hiding later bounded candidate`() {
        val oversized = ByteArray(BoundedArchiveCoverReader.MAX_CANDIDATE_BYTES + 1) { 1 }
        val fallback = byteArrayOf(5, 6, 7)
        val archive = zipOf("huge.jpg" to oversized, "002.jpg" to fallback)

        assertArrayEquals(fallback, read(archive))
    }

    @Test
    fun `non image traversal is byte bounded and preserves only a complete earlier fallback`() {
        val first = byteArrayOf(7)
        val archive = zipOf("001.jpg" to first, "unrelated.bin" to ByteArray(MAX_TRAVERSAL_PLUS_ONE), "cover.jpg" to byteArrayOf(9))
        var checks = 0
        var candidates = 0
        val result = BoundedArchiveCoverReader.read(ByteArrayInputStream(archive),
            isSafeImage = { if (it.endsWith(".jpg")) { candidates++; true } else false },
            isPreferred = { it.startsWith("cover") }, isValid = { true }, checkActive = { checks++ })
        assertArrayEquals(first, result)
        assertEquals(1, candidates)
        assertTrue(checks > 1000)
    }

    @Test
    fun `cancellation interrupts discarded payload and closes its source`() {
        val archive = zipOf("unrelated.bin" to ByteArray(1024 * 1024), "cover.jpg" to byteArrayOf(9))
        var closed = false
        val input = object : ByteArrayInputStream(archive) { override fun close() { closed = true; super.close() } }
        var checks = 0
        val error = runCatching { BoundedArchiveCoverReader.read(input, { it.endsWith(".jpg") }, { true }, { true },
            checkActive = { if (++checks == 5) throw java.util.concurrent.CancellationException("cancel") }) }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertTrue(closed)
    }

    @Test
    fun `corrupt entry never publishes partially decompressed image`() {
        val archive = zipOf("cover.jpg" to byteArrayOf(1, 2, 3))
        // ZipOutputStream emits a data descriptor containing CRC at signature + 4.
        val descriptor = (0..archive.size - 16).first {
            archive[it] == 0x50.toByte() && archive[it + 1] == 0x4b.toByte() && archive[it + 2] == 7.toByte() && archive[it + 3] == 8.toByte()
        }
        archive[descriptor + 4] = (archive[descriptor + 4].toInt() xor 1).toByte()
        assertTrue(runCatching { read(archive) }.exceptionOrNull() is java.io.IOException)
    }

    private fun read(bytes: ByteArray): ByteArray? = BoundedArchiveCoverReader.read(
        input = ByteArrayInputStream(bytes),
        isSafeImage = { it.endsWith(".jpg") },
        isPreferred = { it.substringAfterLast('/').startsWith("cover", ignoreCase = true) },
        isValid = { it.isNotEmpty() },
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private companion object { const val MAX_TRAVERSAL_PLUS_ONE = BoundedArchiveCoverReader.MAX_TRAVERSED_BYTES + 1 }
}

