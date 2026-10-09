package org.koitharu.kotatsu.local.library

import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** Resolve the indexed version before Coil's memory lookup, for every Smart Local request caller. */
class LocalCoverVersionInterceptor @Inject constructor(private val library: Provider<SmartLocalLibrary>) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val uri = when (data) {
            is String -> android.net.Uri.parse(data)
            is CoilUri -> data.toAndroidUri()
            is android.net.Uri -> data
            else -> return chain.proceed()
        }
        if (uri.scheme != LOCAL_LIBRARY_SCHEME || uri.host != "cover") return chain.proceed()
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: return chain.proceed()
        val owner = library.get()
        while (true) {
            currentCoroutineContext().ensureActive()
            val fingerprint = owner.coverFingerprint(id)
            val builder = chain.request.newBuilder()
            if (fingerprint == null) builder.memoryCachePolicy(CachePolicy.DISABLED)
            else builder.memoryCacheKeyExtra("smart-local-source-version", fingerprint)
            val started = System.nanoTime()
            val result = chain.withRequest(builder.build()).proceed()
            // Refresh can publish between version resolution and an engine memory hit.
            if (owner.coverFingerprint(id) == fingerprint) {
                owner.recordCoverPresentation(id, result is SuccessResult && result.dataSource == DataSource.MEMORY_CACHE,
                    System.nanoTime() - started)
                return result
            }
        }
    }
}

