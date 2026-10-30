package org.koitharu.kotatsu.local.library

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri

/**
 * Internal Reader page identity for random-access SAF archives.
 * The document URI is used with the existing persisted tree grant; it is not a filesystem path.
 */
internal object SmartLocalArchivePageUri {
    const val SCHEME = "smart-local-archive"
    private const val HOST = "page"
    private const val DOCUMENT = "document"

    fun create(documentUri: String, entry: String): String =
        Uri.Builder().scheme(SCHEME).authority(HOST)
            .appendQueryParameter(DOCUMENT, documentUri)
            .fragment(entry)
            .build().toString()

    fun parse(uri: Uri): Pair<Uri, String> {
        require(uri.scheme == SCHEME && uri.authority == HOST) { "Invalid local archive page" }
        val document = requireNotNull(uri.getQueryParameter(DOCUMENT)) { "Missing archive document" }.toUri()
        require(document.scheme == ContentResolver.SCHEME_CONTENT) { "Invalid archive document scheme" }
        val entry = requireNotNull(uri.fragment) { "Missing archive entry" }
        require(entry.isNotBlank() && !entry.startsWith('/') && '\\' !in entry &&
            ':' !in entry && entry.split('/').none { it == ".." }) { "Unsafe archive entry" }
        return document to entry
    }
}
