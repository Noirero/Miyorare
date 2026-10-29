package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.network.CloudFlareInterceptor
import org.koitharu.kotatsu.core.network.CurlLoggingInterceptor
import org.koitharu.kotatsu.core.network.RateLimitInterceptor
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.data.awaitTrackerDetails
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal fun publicCatalogClient(base: OkHttpClient): OkHttpClient = base.newBuilder().apply {
	// Keep the configured DNS/proxy/transport, without source browser flows or credential logging.
	interceptors().removeAll { it is CurlLoggingInterceptor || it is CloudFlareInterceptor || it is RateLimitInterceptor }
	networkInterceptors().removeAll { it is CurlLoggingInterceptor }
	authenticator(Authenticator.NONE)
	cookieJar(CookieJar.NO_COOKIES)
	cache(null)
	followRedirects(false)
	followSslRedirects(false)
	callTimeout(40, TimeUnit.SECONDS)
}.build()

@Singleton
class CatalogHttp @Inject constructor(@BaseHttpClient base: OkHttpClient) {
	private val client = publicCatalogClient(base)
	private val requests = Semaphore(3)
	private val resetTimes = ConcurrentHashMap<String, Long>()

	suspend fun request(service: ScrobblerService, request: Request): JsonObject = requests.withPermit {
		val now = System.currentTimeMillis()
		val url = request.url
		val hosts = when (service) {
			ScrobblerService.ANILIST -> setOf("graphql.anilist.co")
			ScrobblerService.MAL -> setOf("api.myanimelist.net", "api.jikan.moe", "api.tenrai.org")
			ScrobblerService.KITSU -> setOf("kitsu.app")
			ScrobblerService.MANGABAKA -> setOf("api.mangabaka.org")
			else -> throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
		}
		require(url.scheme == "https" && url.host in hosts && url.port == 443 && url.username.isEmpty() && url.password.isEmpty())
		val rateKey = "${service.name}:${url.host}"
		resetTimes[rateKey]?.takeIf { it > now }?.let { throw CatalogException(CatalogFailure(CatalogFailureKind.RATE_LIMITED, it)) }
		val publicRequest = request.newBuilder().removeHeader("Authorization").removeHeader("Cookie")
			.header("User-Agent", "Miyorare").build()
		client.newCall(publicRequest).awaitTrackerDetails { response ->
			val reset = catalogRetryAt(response.header("Retry-After"), response.header("X-RateLimit-Reset"), System.currentTimeMillis())
			if (response.code == 429 || response.header("X-RateLimit-Remaining") == "0") {
				if (reset != null) resetTimes[rateKey] = reset
			}
			if (response.code == 429) throw CatalogException(CatalogFailure(CatalogFailureKind.RATE_LIMITED, reset))
			if (!response.isSuccessful) throw CatalogException(CatalogFailure(CatalogFailureKind.PROVIDER))
			val body = response.body ?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
			if (body.source().request(2_000_001)) throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
			try { Json.parseToJsonElement(body.string()).jsonObject }
			catch (e: IllegalArgumentException) { throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA), e) }
		}
	}
}

internal fun catalogRetryAt(retryAfter: String?, reset: String?, now: Long): Long? {
	val seconds = retryAfter?.toLongOrNull()?.takeIf { it in 0..86400 }
	val date = retryAfter?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
	val epoch = reset?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
	return listOfNotNull(seconds?.let { now + it * 1000 }, date, epoch).filter { it >= now }.maxOrNull()
}