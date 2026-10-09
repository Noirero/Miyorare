package org.koitharu.kotatsu.local.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toFile
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Tree grants stay tree grants. No broad-storage permission, real-path conversion or parent walk. */
class LocalDocuments @Inject constructor(@ApplicationContext private val context: Context) {
    private val resolver get() = context.contentResolver
    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun root(uri: String): Node {
        val u = uri.toUri()
        if (u.scheme == ContentResolver.SCHEME_FILE) return fileNode(u.toFile())
        require(u.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(u))
        val document = DocumentsContract.buildDocumentUriUsingTree(u, DocumentsContract.getTreeDocumentId(u))
        // Most providers expose the selected root through its document URI. A few valid SAF
        // providers only answer the original tree URI, so accept that representation as a
        // compatibility fallback without converting the grant to a filesystem path.
        return query(document).singleOrNull()
            ?: runCatching { query(u).singleOrNull() }.getOrNull()
            ?: throw IOException("Folder is unavailable")
    }

    fun access(root: Node, cancelled: () -> Unit): LocalTreeScanner.Access = object : LocalTreeScanner.Access {
        // A node returned by children() is already proven to be inside the selected tree by
        // traversal from an authorized directory. Keep that proof instead of asking SAF
        // providers to implement isChildDocument(), which is optional/inconsistent in practice.
        private val traversed = HashSet<String>().apply { add(root.key) }

        override fun children(directory: Node): MutableList<Node> {
            check(directory.key in traversed) { "Document outside selected folder" }
            return this@LocalDocuments.childrenUnchecked(directory).also { children ->
                children.forEach { traversed += it.key }
            }.toMutableList()
        }
        override fun contains(selectedRoot: Node, child: Node): Boolean =
            selectedRoot.key == root.key && child.key in traversed
        override fun checkCancelled() = cancelled()
        override fun metadataCoverNames(children: MutableList<Node>): MutableSet<String> = children
            .filter { !it.directory && (it.name.endsWith(".xml", true) || it.name.equals("index.json", true)) }
            .mapNotNull { node -> cancelled(); LocalMetadata.read(node, this@LocalDocuments).coverName }
            .filter { it.isNotBlank() && '/' !in it && '\\' !in it && it != ".." }
            .toMutableSet()
    }

    fun children(root: Node, directory: Node): List<Node> {
        check(contains(root, directory)) { "Document outside selected folder" }
        return childrenUnchecked(directory)
    }

    private fun childrenUnchecked(directory: Node): List<Node> {
        val u = directory.uri.toUri()
        return if (u.scheme == ContentResolver.SCHEME_FILE) {
            val files = u.toFile().listFiles() ?: throw IOException("Folder cannot be read: ${directory.name}")
            files.map(::fileNode)
        } else {
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(u, DocumentsContract.getDocumentId(u))
            query(childUri)
        }
    }

    fun contains(root: Node, child: Node): Boolean {
        val r = root.uri.toUri()
        val c = child.uri.toUri()
        if (root.key == child.key) return true
        if (r.scheme != c.scheme) return false
        return if (r.scheme == ContentResolver.SCHEME_FILE) {
            val parent = r.toFile().canonicalFile.toPath()
            c.toFile().canonicalFile.toPath().startsWith(parent)
        } else {
            if (r.authority != c.authority) return false
            // Outside scanner traversal (for example destructive actions), keep the stricter
            // provider-backed ownership check and fail closed.
            DocumentsContract.isChildDocument(resolver, r, c)
        }
    }

    fun input(node: Node): InputStream {
        val uri = node.uri.toUri()
        return if (uri.scheme == ContentResolver.SCHEME_FILE) uri.toFile().inputStream()
        else resolver.openInputStream(uri) ?: throw IOException("File is unavailable: ${node.name}")
    }

    /** Scoped descriptor capability. A provider may return null, a pipe, or reject this access.
     * The consumer decides compatibility; this layer retains grant checks and closes even when
     * opening/rendering fails or cancellation races the synchronous provider operation.
     * Run on an I/O dispatcher. No live descriptor crosses a suspension/dispatcher boundary.
     */
    internal suspend fun <T> withReadDescriptor(root: Node, node: Node, block: (ParcelFileDescriptor) -> T, onOpened: ((Long) -> Unit)? = null): T? {
        check(contains(root, node)) { "Content outside selected root" }
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                continuation.context.ensureActive()
                val uri = node.uri.toUri()
                val started = System.nanoTime()
                val descriptor = if (uri.scheme == ContentResolver.SCHEME_FILE) {
                    ParcelFileDescriptor.open(uri.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
                } else resolver.openFileDescriptor(uri, "r", signal)
                val result = descriptor?.use {
                    onOpened?.invoke(System.nanoTime() - started)
                    continuation.context.ensureActive()
                    block(it).also { continuation.context.ensureActive() }
                }
                continuation.resume(result)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }

    fun delete(root: Node, node: Node) {
        check(root.key != node.key && contains(root, node)) { "Refusing to delete a root or unrelated document" }
        val uri = node.uri.toUri()
        // Directory deletion is never recursive. Unknown files/new external additions are retained.
        check(!node.directory || uri.scheme == ContentResolver.SCHEME_FILE) {
            "A SAF provider cannot guarantee non-recursive directory deletion"
        }
        if (node.directory && children(root, node).isNotEmpty()) throw IOException("Folder is not empty: ${node.name}")
        val deleted = if (uri.scheme == ContentResolver.SCHEME_FILE) uri.toFile().delete()
        else DocumentsContract.deleteDocument(resolver, uri)
        if (!deleted) throw IOException("Cannot delete: ${node.name}")
    }

    fun exists(root: Node, node: Node): Boolean {
        if (!contains(root, node)) return false
        val uri = node.uri.toUri()
        return if (uri.scheme == ContentResolver.SCHEME_FILE) uri.toFile().exists() else query(uri).isNotEmpty()
    }

    private fun query(uri: Uri): List<Node> {
        val result = ArrayList<Node>()
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw IOException("Folder provider is unavailable")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                result += Node("${uri.authority}:$id", docUri.toString(), c.getString(1) ?: id,
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4))
            }
        }
        return result
    }

    private fun fileNode(file: File): Node = Node(file.canonicalPath, file.toUri().toString(), file.name,
        file.isDirectory, if (file.isFile) file.length() else 0L, file.lastModified())

    suspend fun withAccess(uri: String, block: (Node, LocalTreeScanner.Access) -> LocalTreeScanner.Result): LocalTreeScanner.Result =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            val selected = root(uri)
            block(selected, access(selected) { coroutine.ensureActive() })
        }
}

