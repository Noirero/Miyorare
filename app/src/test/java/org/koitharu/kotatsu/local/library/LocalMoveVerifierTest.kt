package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class LocalMoveVerifierTest {
    @Test fun identicalCopiesPass() {
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        LocalMoveVerifier.verify({ ByteArrayInputStream(bytes) }, { ByteArrayInputStream(bytes) })
    }

    @Test fun sameLengthModifiedSourceIsRejected() {
        val before = byteArrayOf(1, 2, 3, 4)
        val changed = byteArrayOf(1, 2, 9, 4)
        assertThrows(IllegalStateException::class.java) {
            LocalMoveVerifier.verify({ ByteArrayInputStream(changed) }, { ByteArrayInputStream(before) })
        }
    }

    @Test fun truncatedSafDestinationIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            LocalMoveVerifier.verify({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                { ByteArrayInputStream(byteArrayOf(1, 2)) })
        }
    }

    @Test fun providerReadFailurePropagatesBeforeDeletion() {
        assertThrows(IOException::class.java) {
            LocalMoveVerifier.verify({ ByteArrayInputStream(byteArrayOf(1)) },
                { object : InputStream() {
                    override fun read(): Int = throw IOException("SAF read failed")
                } })
        }
    }

    @Test fun sourceMutationDuringVerificationIsRejected() {
        val original = byteArrayOf(1, 2, 3, 4)
        val mutableSource = original.copyOf()
        assertThrows(IllegalStateException::class.java) {
            LocalMoveVerifier.verify(
                source = {
                    object : ByteArrayInputStream(mutableSource) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            // Simulate an external writer changing bytes during the read.
                            mutableSource[2] = 99
                            return super.read(b, off, len)
                        }
                    }
                },
                destination = { ByteArrayInputStream(original) },
            )
        }
    }

    @Test fun cancellationPropagatesBeforeDeletion() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            LocalMoveVerifier.verify({ ByteArrayInputStream(byteArrayOf(1)) },
                { ByteArrayInputStream(byteArrayOf(1)) },
                { throw java.util.concurrent.CancellationException("cancelled") })
        }
    }
}
