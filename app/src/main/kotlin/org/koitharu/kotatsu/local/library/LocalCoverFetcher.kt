package org.koitharu.kotatsu.local.library

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import okio.Buffer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import coil3.Uri as CoilUri

class LocalCoverFetcher(private val id: Long, private val library: SmartLocalLibrary, private val options: Options) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        val bytes = library.cover(id) ?: throw IOException("Local cover is unavailable")
        return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), null, DataSource.DISK)
    }
    class Factory @Inject constructor(private val library: Provider<SmartLocalLibrary>) : Fetcher.Factory<CoilUri> {
        override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val uri = data.toAndroidUri()
            if (uri.scheme != LOCAL_LIBRARY_SCHEME || uri.host != "cover") return null
            val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: return null
            return LocalCoverFetcher(id, library.get(), options)
        }
    }
}
