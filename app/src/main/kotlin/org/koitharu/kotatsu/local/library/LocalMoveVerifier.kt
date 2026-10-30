package org.koitharu.kotatsu.local.library

import java.io.InputStream
import java.security.MessageDigest

/**
 * Verifies the live source against the completed destination immediately before
 * destructive cleanup. Propagates read failures and cancellation to the caller.
 */
internal object LocalMoveVerifier {
    fun verify(source: () -> InputStream, destination: () -> InputStream, checkCancelled: () -> Unit = {}) {
        fun fingerprint(open: () -> InputStream): Pair<Long, ByteArray> {
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            open().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    hash.update(buffer, 0, count)
                    size += count
                }
            }
            return size to hash.digest()
        }
        val original = fingerprint(source)
        val copy = fingerprint(destination)
        check(original.first == copy.first && original.second.contentEquals(copy.second)) {
            "Source and destination differ; refusing source deletion"
        }
    }
}
