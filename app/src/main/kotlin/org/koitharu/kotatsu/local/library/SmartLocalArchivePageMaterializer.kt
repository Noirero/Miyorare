package org.koitharu.kotatsu.local.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okio.source
import okio.use
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.local.data.LocalStorageCache
import java.io.File
import java.io.InterruptedIOException

/** Shared Reader and gallery path for one verified, bounded SAF archive image. */
internal object SmartLocalArchivePageMaterializer {
    suspend fun materialize(
        context: Context,
        pageUri: Uri,
        cacheKey: String,
        cache: LocalStorageCache,
    ): File {
        val (document, entry) = SmartLocalArchivePageUri.parse(pageUri)
        val bytes = runInterruptible(Dispatchers.IO) {
            val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(document, "r")) {
                "Local archive document is unavailable"
            }
            descriptor.use {
                ParcelFileDescriptor.AutoCloseInputStream(
                    ParcelFileDescriptor.dup(it.fileDescriptor)
                ).use { input ->
                    IndexedArchiveCoverReader(input.channel) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("Archive page read cancelled")
                        }
                    }.readReaderPage(entry)
                }
            }
        }
        return bytes.inputStream().source().use { source ->
            cache.set(cacheKey, source, MimeTypes.getMimeTypeFromExtension(entry))
        }
    }
}
