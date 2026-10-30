package org.koitharu.kotatsu.local.data

import android.net.Uri
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.local.library.SmartLocalArchivePageUri
import org.koitharu.kotatsu.parsers.model.MangaPage
import java.io.File

/**
 * Only change cache retention for the small local books in scope.
 * Large local archives retain the established shared page-cache behavior.
 */
internal object LocalReaderPageCachePolicy {
    const val MAX_SMALL_ARCHIVE_BYTES = 100L * 1024 * 1024

    fun usesSmallCache(page: MangaPage, url: Uri): Boolean {
        if (page.source != LocalMangaSource) return false
        return when {
            url.scheme == SmartLocalArchivePageUri.SCHEME ->
                SmartLocalArchivePageUri.sourceSize(url)?.let { it in 1..MAX_SMALL_ARCHIVE_BYTES } == true

            url.isZipUri() -> {
                val path = url.schemeSpecificPart ?: return false
                val archive = File(path)
                archive.isFile && archive.length() in 1..MAX_SMALL_ARCHIVE_BYTES
            }

            else -> true // Standalone images, including SAF folder pages, and PDF target files.
        }
    }
}
