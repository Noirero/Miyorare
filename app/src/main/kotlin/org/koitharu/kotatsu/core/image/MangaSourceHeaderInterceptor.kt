package org.koitharu.kotatsu.core.image

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor
import org.koitharu.kotatsu.core.util.ext.isRemoteCoverUrl
import org.koitharu.kotatsu.core.util.ext.mangaSourceKey
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.parsers.network.UserAgents
import javax.inject.Provider

class MangaSourceHeaderInterceptor(
	private val webViewExecutor: Provider<WebViewExecutor>,
	private val kotatsuCoverHeaders: suspend (KotatsuMangaSource) -> Headers?,
) : Interceptor {

	private val lnUserAgent: String by lazy {
		(webViewExecutor.get().defaultUserAgent ?: UserAgents.FIREFOX_MOBILE)
			.replace(Regex("; Android .*?\\)"), "; Android 10; K)")
			.replace(Regex("Version/.* Chrome/"), "Chrome/")
	}

	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val mangaSource = chain.request.extras[mangaSourceKey]?.unwrap() ?: return chain.proceed()
		val request = chain.request
		val requestHeaders = request.httpHeaders
		val parserHeaders = if (
			mangaSource is KotatsuMangaSource &&
			request.networkCachePolicy.readEnabled &&
			isRemoteCoverUrl(request.data.toString())
		) {
			try {
				kotatsuCoverHeaders(mangaSource)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// Cached covers can still be opened if the runtime is unavailable.
				e.printStackTraceDebug()
				null
			}
		} else {
			null
		}
		val newHeaders = requestHeaders.newBuilder()
			.set(CommonHeaders.MANGA_SOURCE, mangaSource.name)
			.apply {
				// Novel plugins declare cover headers (usually a Referer) as fetch-style imageRequestInit.
				(mangaSource as? LnMangaSource)?.let { source ->
					// Cloudflare cookies are UA-bound; match LN fetches and the resolver WebView.
					// An extension-declared User-Agent below intentionally overrides this default.
					set(CommonHeaders.USER_AGENT, lnUserAgent)
					source.plugin.imageRequestInit?.let { raw ->
						runCatching { JSONObject(raw).optJSONObject("headers") }.getOrNull()?.let { headers ->
							for (key in headers.keys()) set(key, headers.optString(key))
						}
					}
				}
				// Cover URLs may point to a CDN or a user-supplied host. Forward only browser
				// context headers, keeping parser cookies, credentials and API headers out.
				if (parserHeaders != null) {
					for (name in KOTATSU_COVER_HEADERS) {
						val values = parserHeaders.values(name)
						if (values.isNotEmpty() && requestHeaders[name] == null) set(name, values)
					}
				}
			}
			.build()
		val newRequest = request.newBuilder()
			.httpHeaders(newHeaders)
			.build()
		return chain.withRequest(newRequest).proceed()
	}

	private companion object {
		val KOTATSU_COVER_HEADERS = listOf("Referer", "Origin", CommonHeaders.USER_AGENT)
	}
}
