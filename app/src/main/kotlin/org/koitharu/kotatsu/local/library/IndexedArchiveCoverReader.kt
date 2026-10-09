package org.koitharu.kotatsu.local.library

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Read-only positional ZIP access over an already authorized, scoped seekable descriptor.
 * No paths are extracted and no whole archive is copied. Unsupported ZIP64/multidisk sources
 * retain the bounded streaming fallback. Field offsets follow OpenJDK ZipConstants.
 */
internal class IndexedArchiveCoverReader(private val channel: FileChannel, private val checkActive: () -> Unit) {
    private data class Entry(val name: String, val rawName: ByteArray, val flags: Int, val method: Int,
        val crc: Long, val compressedSize: Long, val size: Long, val offset: Long)
    private val entries: List<Entry>
    private val directoryOffset: Long
    var bytesRead = 0L
        private set

    init {
        checkActive()
        channel.position(0) // A pipe must fail before any positional read can block.
        val length = channel.size()
        if (length < 22) throw IOException("Source is not a seekable ZIP")
        val tail = read(length - minOf(length, MAX_END_BYTES.toLong()), minOf(length, MAX_END_BYTES.toLong()).toInt())
        val end = (tail.size - 22 downTo 0).firstOrNull {
            tail.u32(it) == END_SIGNATURE && it + 22 + tail.u16(it + 20) == tail.size
        } ?: throw IOException("Missing ZIP directory")
        val count = tail.u16(end + 10)
        val size = tail.u32(end + 12)
        directoryOffset = tail.u32(end + 16)
        val endOffset = length - tail.size + end
        if (tail.u16(end + 4) != 0 || tail.u16(end + 6) != 0 || tail.u16(end + 8) != count ||
            count > MAX_INDEX_ENTRIES || size > MAX_DIRECTORY_BYTES || directoryOffset + size != endOffset) {
            throw IOException("Unsupported or oversized ZIP directory")
        }
        val directory = read(directoryOffset, size.toInt())
        var position = 0
        val result = ArrayList<Entry>(count)
        repeat(count) {
            checkActive()
            if (directory.size - position < 46 || directory.u32(position) != CENTRAL_SIGNATURE) throw IOException("Invalid ZIP directory record")
            val nameLength = directory.u16(position + 28)
            val next = position + 46 + nameLength + directory.u16(position + 30) + directory.u16(position + 32)
            if (next > directory.size || directory.u16(position + 34) != 0) throw IOException("Invalid ZIP directory bounds")
            val flags = directory.u16(position + 8)
            val rawName = directory.copyOfRange(position + 46, position + 46 + nameLength)
            val charset = if (flags and 0x800 != 0) Charsets.UTF_8 else Charset.forName("CP437")
            val name = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(rawName)).toString()
            val entry = Entry(name, rawName, flags, directory.u16(position + 10), directory.u32(position + 16),
                directory.u32(position + 20), directory.u32(position + 24), directory.u32(position + 42))
            if (entry.compressedSize == 0xffffffffL || entry.size == 0xffffffffL || entry.offset >= directoryOffset) throw IOException("Unsupported ZIP64 or entry offset")
            result += entry
            position = next
        }
        if (position != directory.size || result.map { it.name }.distinct().size != result.size) throw IOException("Ambiguous ZIP directory")
        entries = result.sortedBy { it.offset } // Preserve local-entry order, rather than directory order.
    }

    fun readCover(isSafeImage: (String) -> Boolean, isPreferred: (String) -> Boolean,
        isValid: (ByteArray) -> Boolean, explicitCover: String? = null): ByteArray? {
        var total = 0
        val startBytes = bytesRead
        var fallback: ByteArray? = null
        val explicit = explicitCover?.takeIf(isSafeImage)?.let { path -> entries.firstOrNull { it.name == path } }
        val preferred = entries.firstOrNull { !it.name.endsWith('/') && isSafeImage(it.name) && isPreferred(it.name) }
        val window = entries.take(BoundedArchiveCoverReader.MAX_ENTRIES).filter { !it.name.endsWith('/') && isSafeImage(it.name) }
        // An explicit/cover-like entry can be selected from the bounded index without inflating
        // preceding pages. Generic first-valid fallback retains the old local-entry window.
        // Both kinds of direct selection consume the same image/output/read budgets.
        val candidates = (listOfNotNull(explicit, preferred) + window).distinctBy { it.name }.take(BoundedArchiveCoverReader.MAX_IMAGE_CANDIDATES)
        for (entry in candidates) {
            checkActive()
            val limit = minOf(BoundedArchiveCoverReader.MAX_CANDIDATE_BYTES, BoundedArchiveCoverReader.MAX_TOTAL_CANDIDATE_BYTES - total)
            if (limit <= 0) break
            if (entry.size !in 1..limit.toLong()) continue
            val compressedRemaining = BoundedArchiveCoverReader.MAX_TOTAL_CANDIDATE_BYTES - (bytesRead - startBytes)
            if (entry.compressedSize + 30 + entry.rawName.size > compressedRemaining) break
            // Reserve declared output for failed candidates too, and reject expansion beyond that
            // declaration. Malformed candidates cannot reset either byte budget.
            total += entry.size.toInt()
            val bytes = try { readEntry(entry, entry.size.toInt()) } catch (_: IOException) { null } ?: continue
            if (!isValid(bytes)) continue
            if (entry === explicit || isPreferred(entry.name)) return bytes
            if (fallback == null) fallback = bytes
        }
        return fallback
    }

    /** Bounded metadata reads for container/OPF discovery; this never parses EPUB Reader content. */
    fun metadata(name: String): ByteArray? = entries.firstOrNull { it.name == name }?.let {
        try { if (it.compressedSize > MAX_METADATA_BYTES + 1024) null else readEntry(it, MAX_METADATA_BYTES) } catch (_: IOException) { null }
    }

    private fun readEntry(entry: Entry, limit: Int): ByteArray? {
        if (entry.flags and 0x41 != 0 || entry.method !in setOf(0, 8) || entry.size !in 1..limit.toLong() ||
            entry.compressedSize !in 1..BoundedArchiveCoverReader.MAX_CANDIDATE_BYTES.toLong()) return null
        val header = read(entry.offset, 30)
        if (header.u32(0) != LOCAL_SIGNATURE || header.u16(6) != entry.flags || header.u16(8) != entry.method) throw IOException("Inconsistent ZIP local header")
        if (entry.flags and 8 == 0 && (header.u32(14) != entry.crc || header.u32(18) != entry.compressedSize || header.u32(22) != entry.size)) throw IOException("Inconsistent ZIP entry version")
        val nameLength = header.u16(26)
        if (nameLength != entry.rawName.size) throw IOException("Inconsistent ZIP entry name")
        val offset = entry.offset + 30 + nameLength + header.u16(28)
        if (offset + entry.compressedSize > directoryOffset || !read(entry.offset + 30, nameLength).contentEquals(entry.rawName)) throw IOException("Invalid ZIP entry range")
        val range = RangeInput(offset, entry.compressedSize)
        val inflater = if (entry.method == 8) Inflater(true) else null
        try {
            val input = if (inflater == null) range else InflaterInputStream(range, inflater)
            return input.use { stream ->
                val output = ByteArrayOutputStream(minOf(entry.size.toInt(), DEFAULT_BUFFER_SIZE))
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val crc = CRC32()
                var total = 0
                while (true) {
                    checkActive()
                    val count = stream.read(buffer, 0, minOf(buffer.size, minOf(limit.toLong(), entry.size).toInt() - total + 1))
                    if (count < 0) break
                    if (count == 0) throw IOException("Stalled ZIP entry")
                    total += count
                    if (total.toLong() > minOf(limit.toLong(), entry.size)) throw IOException("Oversized ZIP entry")
                    crc.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                if (total.toLong() != entry.size || crc.value != entry.crc ||
                    (inflater != null && (!inflater.finished() || inflater.bytesRead != entry.compressedSize))) throw IOException("Corrupt ZIP entry")
                output.toByteArray()
            }
        } finally { inflater?.end() }
    }

    private fun read(offset: Long, size: Int): ByteArray {
        if (offset < 0 || size < 0 || offset + size > channel.size()) throw IOException("ZIP read outside source")
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            checkActive()
            val count = channel.read(buffer, offset + buffer.position())
            if (count <= 0) throw IOException("Incomplete ZIP source")
            bytesRead += count
        }
        return bytes
    }

    private inner class RangeInput(private var offset: Long, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining == 0L) return -1
            checkActive()
            val buffer = ByteBuffer.wrap(bytes, off, minOf(len.toLong(), remaining).toInt())
            val count = channel.read(buffer, offset)
            if (count <= 0) throw IOException("Incomplete ZIP payload")
            offset += count; remaining -= count; bytesRead += count
            return count
        }
    }

    private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(offset: Int): Long = (u16(offset).toLong() or (u16(offset + 2).toLong() shl 16))

    internal companion object {
        const val MAX_DIRECTORY_BYTES = 2 * 1024 * 1024
        const val MAX_INDEX_ENTRIES = 4096
        const val MAX_METADATA_BYTES = 256 * 1024
        private const val MAX_END_BYTES = 65535 + 22
        private const val END_SIGNATURE = 0x06054b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val LOCAL_SIGNATURE = 0x04034b50L
    }
}
