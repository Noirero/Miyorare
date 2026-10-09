package org.koitharu.kotatsu.local.library

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IndexedArchiveCoverReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    private fun <T> read(bytes: ByteArray, block: (IndexedArchiveCoverReader) -> T): T {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        return RandomAccessFile(file, "r").use { block(IndexedArchiveCoverReader(it.channel) {}) }
    }
    private fun cover(reader: IndexedArchiveCoverReader, explicit: String? = null) = reader.readCover(
        { !it.startsWith('/') && it.split('/').none { segment -> segment == ".." } && it.endsWith(".jpg") },
        { it.substringAfterLast('/').startsWith("cover") }, { it.isNotEmpty() }, explicit,
    )

    @Test fun lateCoverSkipsLargeUnrelatedPayloadAndReadsOnlyBoundedIndexAndSelectedEntry() {
        val unrelated = ByteArray(4 * 1024 * 1024).also { java.util.Random(41).nextBytes(it) }
        read(zip("unrelated.bin" to unrelated, "cover.jpg" to byteArrayOf(1, 2, 3))) { reader ->
            assertArrayEquals(byteArrayOf(1, 2, 3), cover(reader))
            assertTrue("Unrelated payload was traversed: ${reader.bytesRead}", reader.bytesRead < 100_000)
            println("SYNTHETIC indexed ZIP selected late cover sourceBytes=${reader.bytesRead} skippedPayloadBytes=${unrelated.size}")
        }
    }

    @Test fun existingFirstValidAndPreferredPolicyAndDiscoveryWindowRemainBounded() {
        read(zip("001.jpg" to byteArrayOf(1), "cover.jpg" to byteArrayOf(2))) { assertArrayEquals(byteArrayOf(2), cover(it)) }
        read(zip("001.jpg" to byteArrayOf(1), "002.jpg" to byteArrayOf(2))) { assertArrayEquals(byteArrayOf(1), cover(it)) }
        val entries = List(BoundedArchiveCoverReader.MAX_ENTRIES) { "note-$it.txt" to byteArrayOf(0) } + ("cover.jpg" to byteArrayOf(3))
        read(zip(*entries.toTypedArray())) { assertArrayEquals(byteArrayOf(3), cover(it)) }
        val generic = entries.dropLast(1) + ("001.jpg" to byteArrayOf(4))
        read(zip(*generic.toTypedArray())) { assertNull(cover(it)) }
    }

    @Test fun declaredEpubCoverCanBeAccessedWithoutTraversingUnrelatedEntries() {
        val entries = List(100) { "note-$it.txt" to byteArrayOf(0) } + ("art.jpg" to byteArrayOf(3))
        read(zip(*entries.toTypedArray())) { assertArrayEquals(byteArrayOf(3), cover(it, "art.jpg")) }
    }

    @Test fun unsafeEntryAndCorruptCrcNeverBecomeASelectedPayload() {
        read(zip("../cover.jpg" to byteArrayOf(1), "002.jpg" to byteArrayOf(2))) { assertArrayEquals(byteArrayOf(2), cover(it)) }
        val corrupt = zip("cover.jpg" to byteArrayOf(1, 2, 3))
        val central = (0..corrupt.size - 4).first { corrupt[it] == 0x50.toByte() && corrupt[it + 1] == 0x4b.toByte() && corrupt[it + 2] == 1.toByte() && corrupt[it + 3] == 2.toByte() }
        corrupt[central + 16] = (corrupt[central + 16].toInt() xor 1).toByte()
        read(corrupt) { assertNull(cover(it)) }
    }

    @Test fun metadataHasItsOwnHardBoundAndMalformedDirectoriesFailClosed() {
        read(zip("META-INF/container.xml" to ByteArray(IndexedArchiveCoverReader.MAX_METADATA_BYTES + 1))) {
            assertNull(it.metadata("META-INF/container.xml"))
        }
        assertTrue(runCatching { read(byteArrayOf(1, 2, 3)) { cover(it) } }.exceptionOrNull() is java.io.IOException)
        val truncated = zip("cover.jpg" to byteArrayOf(1)).dropLast(1).toByteArray()
        assertTrue(runCatching { read(truncated) { cover(it) } }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun forgedExpansionAndBrokenExplicitCoverDoNotPoisonFallback() {
        val archive = zip("bad.jpg" to ByteArray(40_000) { 3 }, "001.jpg" to byteArrayOf(7), "meta.xml" to ByteArray(40_000) { 4 })
        val records = (0..archive.size - 46).filter {
            archive[it] == 0x50.toByte() && archive[it + 1] == 0x4b.toByte() && archive[it + 2] == 1.toByte() && archive[it + 3] == 2.toByte()
        }
        for (record in listOf(records.first(), records.last())) {
            // Data-descriptor entries: a forged central-directory output length must not
            // make inflation/read-loop work unbounded or throw an unchecked length error.
            archive[record + 24] = 1
            archive[record + 25] = 0; archive[record + 26] = 0; archive[record + 27] = 0
        }
        read(archive) { reader ->
            assertArrayEquals(byteArrayOf(7), cover(reader, "bad.jpg"))
            assertNull(reader.metadata("meta.xml"))
        }
    }

    @Test fun cancellationDuringIndexWorkPropagatesWithoutBeingTreatedAsAFormatFallback() {
        val file = temporary.newFile().apply { writeBytes(zip("cover.jpg" to byteArrayOf(1))) }
        var checks = 0
        val error = runCatching { RandomAccessFile(file, "r").use { source ->
            IndexedArchiveCoverReader(source.channel) { if (++checks == 3) throw CancellationException("cancel") }
        } }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
