package org.koitharu.kotatsu.local.library

import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Immutable cover candidates from the last successfully published discovery snapshot. No I/O. */
internal class LocalCoverPlan(val rootUri: String, val candidates: List<Node>, private val scanVersion: Long, val mangaId: Long? = null) {
    val diagnosticKey: String? by lazy { mangaId?.let { coverDigest(it.toString().toByteArray()) } }
    val fingerprint: String by lazy { fingerprintThrough(candidates.lastIndex) }

    // Include failed higher-priority candidates: replacing a broken sidecar must supersede a
    // previously successful archive fallback. Later chapters do not invalidate an earlier winner.
    fun fingerprintThrough(index: Int): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(THUMBNAIL_VERSION)
            out.writeUTF(LocalCoverRecipe.IDENTITY)
            out.writeUTF(rootUri)
            for (node in candidates.take(index + 1)) {
                out.writeUTF(node.key)
                out.writeUTF(node.uri)
                out.writeUTF(node.name)
                out.writeLong(node.size)
                out.writeLong(node.modified)
                // Providers with unknown metadata cannot establish a new version reliably. An
                // explicit rescan invalidates those candidates, without breaking restart reuse.
                if (node.size <= 0 || node.modified <= 0) out.writeLong(scanVersion)
            }
        }
        return coverDigest(bytes.toByteArray())
    }

    companion object {
        // One controlled invalidation of the old 768px/PNG recipe. Source/chapter IDs are unchanged.
        const val THUMBNAIL_VERSION = LocalCoverRecipe.VERSION

        fun from(book: LocalBook): LocalCoverPlan {
            val nodes = (book.sidecars + book.chapters.flatMap { it.pages }).associateBy { it.uri }
            val explicit = (listOfNotNull(book.cover) + book.sidecars.filter { LocalTreeScanner.isImage(it.name) }
                .sortedByDescending { LocalTreeScanner.isSidecar(it.name) }.map { it.uri }).distinct()
                .mapNotNull(nodes::get)
            val fallback = book.chapters.flatMap { chapter ->
                if (chapter.pages.isNotEmpty()) chapter.pages.take(BoundedArchiveCoverReader.MAX_IMAGE_CANDIDATES)
                else if (LocalTreeScanner.extension(chapter.node.name) in setOf("pdf", "cbz", "zip", "epub")) listOf(chapter.node)
                else emptyList()
            }
            return LocalCoverPlan(book.rootUri, explicit + fallback, book.scannedAt, book.id)
        }
    }
}

internal data class GeneratedLocalCover(val bytes: ByteArray, val candidateIndex: Int, val cacheable: Boolean = true)

internal fun coverDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

