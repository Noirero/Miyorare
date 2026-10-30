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
    private const val VERSION = "version"
    private const val SOURCE_SIZE = "sourceSize"

    fun create(documentUri: String, entry: String, version: String, sourceSize: Long = -1L): String =
        Uri.Builder().scheme(SCHEME).authority(HOST)
            .appendQueryParameter(DOCUMENT, documentUri)
            .appendQueryParameter(VERSION, version)
            .apply { if (sourceSize > 0L) appendQueryParameter(SOURCE_SIZE, sourceSize.toString()) }
            .fragment(entry)
            .build().toString()

    /** Size is only a cache-budget hint; an absent/untrusted size uses the established cache. */
    fun sourceSize(uri: Uri): Long? =
        uri.getQueryParameter(SOURCE_SIZE)?.toLongOrNull()?.takeIf { it > 0L }

    fun version(uri: Uri): String {
        require(uri.scheme == SCHEME && uri.authority == HOST) { "Invalid local archive page" }
        val version = requireNotNull(uri.getQueryParameter(VERSION)) { "Missing archive version" }
        require(version.length == 64 && version.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid archive version"
        }
        return version
    }

    fun parse(uri: Uri): Pair<Uri, String> {
        version(uri) // Reject unversioned page identities that could alias stale cache entries.
        require(uri.scheme == SCHEME && uri.authority == HOST) { "Invalid local archive page" }
        val document = requireNotNull(uri.getQueryParameter(DOCUMENT)) { "Missing archive document" }.toUri()
        require(document.scheme == ContentResolver.SCHEME_CONTENT) { "Invalid archive document scheme" }
        val entry = requireNotNull(uri.fragment) { "Missing archive entry" }
        require(entry.isNotBlank() && !entry.startsWith('/') && '\\' !in entry &&
            ':' !in entry && entry.split('/').none { it == ".." }) { "Unsafe archive entry" }
        return document to entry
    }
}
