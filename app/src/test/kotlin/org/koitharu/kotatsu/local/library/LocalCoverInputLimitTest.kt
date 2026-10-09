package org.koitharu.kotatsu.local.library

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException

class LocalCoverInputLimitTest {
    @Test fun cancellationIsCheckedDuringChunkedImageReadAndTheScopedSourceCloses() {
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(128 * 1024)) {
            override fun close() { closed = true; super.close() }
        }
        var checks = 0
        val error = runCatching { input.use { source -> source.readBytesLimited(256 * 1024) {
            if (++checks == 3) throw CancellationException("cancel")
        } } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(closed)
        assertTrue(input.available() > 0)
    }

    @Test fun exactInputCeilingIsAcceptedAndLargerSourceFailsBeforeBecomingACandidate() {
        val limit = BoundedArchiveCoverReader.MAX_CANDIDATE_BYTES
        val bytes = ByteArray(limit)
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).use { it.readBytesLimited(limit) })
        val error = runCatching { ByteArrayInputStream(ByteArray(limit + 1)).use { it.readBytesLimited(limit) } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
