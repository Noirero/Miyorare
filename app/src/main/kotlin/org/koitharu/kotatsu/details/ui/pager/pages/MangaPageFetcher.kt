package org.koitharu.kotatsu.details.ui.pager.pages

import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.request.Options
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.fetch
import org.koitharu.kotatsu.core.util.ext.isNetworkUri
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.LocalReaderPageCache
import org.koitharu.kotatsu.local.data.LocalReaderPageCachePolicy
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.local.data.input.LocalPdfCache
import org.koitharu.kotatsu.local.library.SmartLocalArchivePageUri
import org.koitharu.kotatsu.local.library.SmartLocalArchivePageMaterializer
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.mimeType
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.PageLoader
import javax.inject.Inject

class MangaPageFetcher(
	private val okHttpClient: OkHttpClient,
	private val pagesCache: LocalStorageCache,
	private val localPageCache: LocalStorageCache,
	private val options: Options,
	private val page: MangaPage,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	private val imageLoader: ImageLoader,
) : Fetcher {

	override suspend fun fetch(): FetchResult? {
		val repo = mangaRepositoryFactory.create(page.source)
		if (!page.source.isExternalSource() && !page.preview.isNullOrEmpty()) {
			runCatchingCancellable {
				imageLoader.fetch(checkNotNull(page.preview), options)
			}.onSuccess {
				return it
			}
		}
		val pageUrl = repo.getPageUrl(page)
		val imageHeaders = repo.getImageRequestHeaders(pageUrl, page)
		val pageUri = pageUrl.toUri()
		val selectedCache = if (LocalReaderPageCachePolicy.usesSmallCache(page, pageUri)) localPageCache else pagesCache
		if (options.diskCachePolicy.readEnabled &&
            !(page.source == LocalMangaSource && pageUri.scheme == "content")) {
			selectedCache[pageUrl]?.let { file ->
				return SourceFetchResult(
					source = ImageSource(file.toOkioPath(), options.fileSystem),
					mimeType = MimeTypes.getMimeTypeFromExtension(file.name)?.toString(),
					dataSource = DataSource.DISK,
				)
			}
		}
        if (page.source == LocalMangaSource && pageUri.scheme == SmartLocalArchivePageUri.SCHEME) {
            val file = SmartLocalArchivePageMaterializer.materialize(options.context, pageUri, pageUrl, selectedCache)
            return SourceFetchResult(
                source = ImageSource(file.toOkioPath(), options.fileSystem),
                mimeType = MimeTypes.getMimeTypeFromExtension(file.name)?.toString(),
                dataSource = DataSource.DISK,
            )
        }
        if (page.source == LocalMangaSource && pageUri.scheme == "file") {
            val localFile = pageUri.toFile()
            if (LocalPdfCache.isPdfPage(localFile)) {
                val file = runInterruptible(Dispatchers.IO) { LocalPdfCache.materializePage(localFile) }
                return SourceFetchResult(
                    source = ImageSource(file.toOkioPath(), options.fileSystem),
                    mimeType = "image/png",
                    dataSource = DataSource.DISK,
                )
            }
        }
		return loadPage(pageUrl, imageHeaders, repo)
	}

	private suspend fun loadPage(pageUrl: String, imageHeaders: Headers?, repo: MangaRepository): FetchResult? {
		// Use the extension's getImage() first when available — it handles decryption/unscrambling
		// and resolves sources whose page url is not a directly-fetchable http(s) url (e.g. MangaDex
		// returns a relative "/data/..." imageUrl with the host kept inside the extension). Without
		// this, such a url is misread as a local file path below and fails with FileNotFoundException.
		repo.getImageStream(pageUrl, page)?.let {
			return consumeResponse(it, pageUrl)
		}
		return if (pageUrl.toUri().isNetworkUri()) {
			fetchPage(pageUrl, imageHeaders)
		} else {
			imageLoader.fetch(pageUrl, options)
		}
	}

	private suspend fun fetchPage(pageUrl: String, imageHeaders: Headers?): FetchResult {
		// Direct OkHttp fetch for non-extension sources (getImageStream returned null).
		val response = imageProxyInterceptor.interceptPageRequest(
			PageLoader.createPageRequest(pageUrl, page.source, imageHeaders),
			okHttpClient,
		)
		return consumeResponse(response, pageUrl)
	}

	private suspend fun consumeResponse(response: Response, pageUrl: String): FetchResult {
		return response.use { r ->
			if (!r.isSuccessful) {
				throw HttpException(r.toNetworkResponse())
			}
			val mimeType = r.mimeType?.toMimeTypeOrNull()
			val file = r.body.use {
				pagesCache.set(pageUrl, it.source(), mimeType)
			}
			SourceFetchResult(
				source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
				mimeType = mimeType?.toString(),
				dataSource = DataSource.NETWORK,
			)
		}
	}

	private fun Response.toNetworkResponse() = NetworkResponse(
		code = code,
		requestMillis = sentRequestAtMillis,
		responseMillis = receivedResponseAtMillis,
		headers = headers.toNetworkHeaders(),
		body = NetworkResponseBody(body.source()),
		delegate = this,
	)

	private fun Headers.toNetworkHeaders(): NetworkHeaders {
		val headers = NetworkHeaders.Builder()
		for ((key, values) in this) {
			headers.add(key, values)
		}
		return headers.build()
	}

	class Factory @Inject constructor(
		@MangaHttpClient private val okHttpClient: OkHttpClient,
		@PageCache private val pagesCache: LocalStorageCache,
		@LocalReaderPageCache private val localPageCache: LocalStorageCache,
		private val mangaRepositoryFactory: MangaRepository.Factory,
		private val imageProxyInterceptor: ImageProxyInterceptor,
	) : Fetcher.Factory<MangaPage> {

		override fun create(data: MangaPage, options: Options, imageLoader: ImageLoader) = MangaPageFetcher(
			okHttpClient = okHttpClient,
			pagesCache = pagesCache,
			localPageCache = localPageCache,
			options = options,
			page = data,
			mangaRepositoryFactory = mangaRepositoryFactory,
			imageProxyInterceptor = imageProxyInterceptor,
			imageLoader = imageLoader,
		)
	}
}
