package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
