package org.koitharu.kotatsu.local.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toFile
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

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
        return query(document).singleOrNull() ?: throw IOException("Folder is unavailable")
    }

    fun access(root: Node, cancelled: () -> Unit): LocalTreeScanner.Access = object : LocalTreeScanner.Access {
        override fun children(directory: Node): MutableList<Node> = children(root, directory).toMutableList()
        override fun contains(selectedRoot: Node, child: Node): Boolean = this@LocalDocuments.contains(selectedRoot, child)
        override fun checkCancelled() = cancelled()
    }

    fun children(root: Node, directory: Node): List<Node> {
        check(contains(root, directory)) { "Document outside selected folder" }
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
            // Providers with opaque IDs must support the standard descendant query. Fail closed if
            // they cannot prove ownership, rather than interpreting document IDs as filesystem paths.
            DocumentsContract.isChildDocument(resolver, r, c)
        }
    }

    fun input(node: Node): InputStream {
        val uri = node.uri.toUri()
        return if (uri.scheme == ContentResolver.SCHEME_FILE) uri.toFile().inputStream()
        else resolver.openInputStream(uri) ?: throw IOException("File is unavailable: ${node.name}")
    }

    fun delete(root: Node, node: Node) {
        check(root.key != node.key && contains(root, node)) { "Refusing to delete a root or unrelated document" }
        val uri = node.uri.toUri()
        // Directory deletion is never recursive. Unknown files/new external additions are retained.
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
