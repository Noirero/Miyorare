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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.mangaSourceKey
import org.koitharu.kotatsu.kotatsu.KotatsuMangaRepository
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import java.io.IOException
import javax.inject.Provider
import coil3.Uri as CoilUri

/**
 * Fetches cover and thumbnail images for Kotatsu runtime sources with the parser's own
 * request headers (see [KotatsuMangaRepository.getCoverHeaders]).
 *
 * The generic Coil network fetcher cannot do this: CommonHeadersInterceptor only merges
 * source headers for Mihon repositories, so Kotatsu covers went out without the parser's
 * Referer and hotlink-protected image hosts rejected them with HTTP 403 — LectorHentai
 * covers stayed blank while its chapters, which travel the reader path and do carry the
 * headers, loaded fine. This fetcher restores parity with [MihonImageFetcher] and
 * [TsukiImageFetcher]: same headers as the read path.
 */
class KotatsuImageFetcher(
	private val source: KotatsuMangaSource,
	private val url: String,
	private val options: Options,
	private val imageLoader: ImageLoader,
	private val repositoryFactoryProvider: Provider<MangaRepository.Factory>,
	private val httpClientProvider: Provider<OkHttpClient>,
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

		val repository = repositoryFactoryProvider.get().create(source) as? KotatsuMangaRepository
			?: throw IOException("Kotatsu runtime is not available for $source")
		val request = Request.Builder()
			.url(url)
			.headers(repository.getCoverHeaders())
			.header(CommonHeaders.MANGA_SOURCE, source.name)
			.build()
		val response = withContext(Dispatchers.IO) {
			httpClientProvider.get().newCall(request).execute()
		}
		if (!response.isSuccessful) {
			val code = response.code
			response.close()
			throw IOException("HTTP $code while loading Kotatsu image")
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
		private val repositoryFactoryProvider: Provider<MangaRepository.Factory>,
		private val httpClientProvider: Provider<OkHttpClient>,
	) : Fetcher.Factory<CoilUri> {

		override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
			val scheme = data.scheme
			if (scheme != "http" && scheme != "https") return null
			val source = options.extras[mangaSourceKey]?.unwrap() as? KotatsuMangaSource ?: return null
			return KotatsuImageFetcher(
				source = source,
				url = data.toString(),
				options = options,
				imageLoader = imageLoader,
				repositoryFactoryProvider = repositoryFactoryProvider,
				httpClientProvider = httpClientProvider,
				diskCacheKeyLazy = lazy { options.diskCacheKey ?: imageLoader.components.key(data, options) },
			)
		}
	}
}
