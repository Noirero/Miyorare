package org.koitharu.kotatsu.local.data

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException

/**
 * A directly readable local image needs a seekable document descriptor because the Reader's
 * region decoder may revisit arbitrary pixel regions. Pipe-only SAF providers continue to use
 * the existing bounded disk-page fallback instead.
 */
internal object SeekableLocalPage {
    fun canOpenDirectly(resolver: ContentResolver, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        return try {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: return false
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val channel = input.channel
                val position = channel.position()
                channel.position(position)
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
