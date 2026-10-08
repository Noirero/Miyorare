package org.koitharu.kotatsu.local.library

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real seekable SAF fixtures in the test APK only. Counts source opens and metadata queries. */
class CoverFixtureDocumentsProvider : DocumentsProvider() {
    private val queries = AtomicInteger()
    private val opens = AtomicInteger()
    private var revision = System.currentTimeMillis()
    private val directory get() = File(requireNotNull(context).filesDir, "cover-fixtures").apply { mkdirs() }
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
            val row = newRow()
            for (column in columnNames) row.add(when (column) {
                Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> "root"
                Root.COLUMN_TITLE -> "Cover fixtures"
                Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_IS_CHILD
                else -> null
            })
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        queries.incrementAndGet()
        return cursor(projection).apply { val file = file(documentId); if (file.exists()) add(file, documentId) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        queries.incrementAndGet()
        return cursor(projection).apply {
            file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                add(child, child.relativeTo(directory).invariantSeparatorsPath)
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + File.separator)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode == "r")
        opens.incrementAndGet()
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        "fixture-reset" -> { directory.deleteRecursively(); queries.set(0); opens.set(0); Bundle() }
        "fixture-metrics-reset" -> { queries.set(0); opens.set(0); Bundle() }
        "fixture-counts" -> Bundle().apply { putInt("queries", queries.get()); putInt("opens", opens.get()) }
        "fixture-put" -> {
            val file = file(requireNotNull(arg)); file.parentFile!!.mkdirs()
            file.outputStream().use { output ->
                val data = requireNotNull(extras)
                val bytes = requireNotNull(data.getByteArray("bytes"))
                var remaining = data.getInt("padding")
                // Grow a valid PDF comment before its footer, preserving existing xref offsets.
                // Appending megabytes after %%EOF would instead create a malformed fixture.
                val footer = if (remaining > 0) bytes.toString(Charsets.ISO_8859_1).lastIndexOf("startxref") else -1
                if (footer >= 0) {
                    output.write(bytes, 0, footer)
                    output.write("\n%".toByteArray())
                    val padding = ByteArray(8192) { 'x'.code.toByte() }
                    while (remaining > 0) { val n = minOf(padding.size, remaining); output.write(padding, 0, n); remaining -= n }
                    output.write('\n'.code)
                    output.write(bytes, footer, bytes.size - footer)
                } else output.write(bytes)
            }
            file.setLastModified(++revision)
            Bundle()
        }
        else -> super.call(method, arg, extras)
    }

    private fun file(id: String): File {
        val file = if (id == "root") directory else File(directory, id)
        check(file.canonicalPath == directory.canonicalPath || file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
        return file
    }

    private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    ))

    private fun MatrixCursor.add(file: File, id: String) {
        val row = newRow()
        for (column in columnNames) row.add(when (column) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> file.name
            Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else when (file.extension) {
                "pdf" -> "application/pdf"
                "epub" -> "application/epub+zip"
                else -> "application/octet-stream"
            }
            Document.COLUMN_SIZE -> if (file.isFile) file.length() else 0L
            Document.COLUMN_LAST_MODIFIED -> file.lastModified()
            Document.COLUMN_FLAGS -> 0
            else -> null
        })
    }
}
