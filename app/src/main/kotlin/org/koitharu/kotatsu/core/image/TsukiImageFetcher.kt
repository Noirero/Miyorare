package org.koitharu.kotatsu.core.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.util.ext.mangaSourceKey
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.runtime.TsukiPluginRuntime
import java.io.IOException
import javax.inject.Provider
import coil3.Uri as CoilUri

/**
 * Fetches Tsuki/Usagi cover and thumbnail images through the plugin runtime.
 *
 * This is intentionally lazy: the runtime provider is not resolved until Coil actually fetches an
 * image whose request carries a [TsukiMangaSource]. The request is tagged through
 * [TsukiPluginRuntime.createTaggedRequest], so parser headers and parser.intercept(...) are applied
 * exactly like the reader/download path instead of falling through to Miyorare's generic client.
 */
class TsukiImageFetcher(
	private val source: TsukiMangaSource,
	private val url: String,
	private val options: Options,
	private val imageLoader: ImageLoader,
	private val runtimeProvider: Provider<TsukiPluginRuntime>,
	private val diskCacheKeyLazy: Lazy<String?>,
) : Fetcher {

	override suspend fun fetch(): FetchResult {
		val diskCacheKey = diskCacheKeyLazy.value
		readFromDiskCache(diskCacheKey)?.let { snapshot ->
			return SourceFetchResult(
				source = snapshot.toImageSource(diskCacheKey!!),
				mimeType = null,
				dataSource = DataSource.DISK,
			)
		}

		val runtime = runtimeProvider.get()
		val request = runtime.createTaggedRequest(source, url)
		val response = withContext(Dispatchers.IO) {
			runtime.getHttpClient(source).newCall(request).execute()
		}
		if (!response.isSuccessful) {
			val code = response.code
			response.close()
			throw IOException("HTTP $code while loading Tsuki image")
		}
		try {
			writeToDiskCache(response, diskCacheKey)?.let { snapshot ->
				val mimeType = response.body.contentType()?.toString()
				response.close()
				return SourceFetchResult(
					source = snapshot.toImageSource(diskCacheKey!!),
					mimeType = mimeType,
					dataSource = DataSource.NETWORK,
				)
			}
			return SourceFetchResult(
				source = ImageSource(response.body.source(), options.fileSystem),
				mimeType = response.body.contentType()?.toString(),
				dataSource = DataSource.NETWORK,
			)
		} catch (error: Throwable) {
			response.close()
			throw error
		}
	}

	private fun readFromDiskCache(key: String?): DiskCache.Snapshot? {
		if (key == null || !options.diskCachePolicy.readEnabled) return null
		return imageLoader.diskCache?.openSnapshot(key)
	}

	private fun writeToDiskCache(response: Response, key: String?): DiskCache.Snapshot? {
		if (key == null || !options.diskCachePolicy.writeEnabled) return null
		response.body.contentType()?.let { type ->
			if (type.type.equals("text", true) || type.subtype.equals("json", true)) return null
		}
		val diskCache = imageLoader.diskCache ?: return null
		val editor = diskCache.openEditor(key) ?: return null
		return try {
			diskCache.fileSystem.write(editor.data) {
				response.body.source().readAll(this)
			}
			editor.commitAndOpenSnapshot()
		} catch (error: Exception) {
			runCatching { editor.abort() }
			throw error
		}
	}

	private fun DiskCache.Snapshot.toImageSource(key: String): ImageSource = ImageSource(
		file = data,
		fileSystem = checkNotNull(imageLoader.diskCache).fileSystem,
		diskCacheKey = key,
		closeable = this,
	)

	class Factory(
		private val runtimeProvider: Provider<TsukiPluginRuntime>,
	) : Fetcher.Factory<CoilUri> {

		override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
			val scheme = data.scheme
			if (scheme != "http" && scheme != "https") return null
			val source = options.extras[mangaSourceKey]?.unwrap() as? TsukiMangaSource ?: return null
			return TsukiImageFetcher(
				source = source,
				url = data.toString(),
				options = options,
				imageLoader = imageLoader,
				runtimeProvider = runtimeProvider,
				diskCacheKeyLazy = lazy { imageLoader.components.key(data, options) },
			)
		}
	}
}
