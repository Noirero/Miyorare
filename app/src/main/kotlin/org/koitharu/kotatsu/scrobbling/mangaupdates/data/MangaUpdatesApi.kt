package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal val MANGAUPDATES_API = "https://api.mangaupdates.com/v1/".toHttpUrl()

internal class MangaUpdatesHttpException(val code: Int) : IOException("MangaUpdates HTTP $code")
class MangaUpdatesAuthTicket(val token: String, val generation: Long, val invalidateStored: Boolean = true) {
	override fun toString() = "MangaUpdatesAuthTicket(redacted)"
}

internal class MangaUpdatesInterceptor(private val store: MangaUpdatesSessionStore, private val origin: HttpUrl = MANGAUPDATES_API) : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val url = request.url
		if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
			url.username.isNotEmpty() || url.password.isNotEmpty() || !url.encodedPath.startsWith(origin.encodedPath)) throw IOException("Invalid MangaUpdates origin")
		val ticket = request.tag(MangaUpdatesAuthTicket::class.java)
		if (ticket != null && store.generation.value != ticket.generation) throw IOException("MangaUpdates session changed")
		val builder = request.newBuilder().removeHeader("Authorization")
		if (ticket != null) builder.header("Authorization", "Bearer ${validMangaUpdatesToken(ticket.token)}")
		val response = chain.proceed(builder.build())
		if (response.code == 401 && ticket?.invalidateStored == true) store.clear(ticket.generation)
		return response
	}
}

internal fun mangaUpdatesClient(base: OkHttpClient, store: MangaUpdatesSessionStore): OkHttpClient = OkHttpClient.Builder()
	.dns(base.dns).proxy(base.proxy).proxySelector(base.proxySelector).proxyAuthenticator(base.proxyAuthenticator)
	.connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
	.followRedirects(false).followSslRedirects(false).addInterceptor(MangaUpdatesInterceptor(store)).build()

@Singleton
class MangaUpdatesApi internal constructor(
	private val client: OkHttpClient,
	private val store: MangaUpdatesSessionStore,
	private val base: HttpUrl,
) {
	@Inject constructor(@ScrobblerType(ScrobblerService.MANGAUPDATES) client: OkHttpClient, store: MangaUpdatesSessionStore) : this(client, store, MANGAUPDATES_API)
	private val requests = Semaphore(3)

	suspend fun request(method: String, path: String, body: JsonElement? = null, ticket: MangaUpdatesAuthTicket? = null): JsonElement = requests.withPermit {
		currentCoroutineContext().ensureActive()
		if (ticket != null) ensureCurrent(ticket)
		require(!path.startsWith('/') && ':' !in path && ".." !in path)
		val url = base.resolve(path) ?: error("Invalid MangaUpdates path")
		val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
		val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Miyorare")
			.tag(MangaUpdatesAuthTicket::class.java, ticket)
			.method(method, payload ?: if (method in setOf("POST", "PUT", "PATCH")) "".toRequestBody() else null).build()
		client.newCall(request).await().use { response ->
			if (!response.isSuccessful) throw MangaUpdatesHttpException(response.code)
			if (ticket != null) ensureCurrent(ticket)
			val responseBody = response.body
			if (responseBody?.source()?.request(2_000_001) == true) throw IOException("MangaUpdates response exceeds the size limit")
			val content = responseBody?.string().orEmpty()
			val result = if (content.isBlank()) JsonNull else try { Json.parseToJsonElement(content) } catch (_: Exception) { throw IOException("Malformed MangaUpdates response") }
			if (method != "GET" && (result as? JsonObject)?.get("status") == JsonPrimitive("error")) throw IOException("MangaUpdates rejected the request")
			currentCoroutineContext().ensureActive()
			if (ticket != null) ensureCurrent(ticket)
			result
		}
	}

	fun sessionTicket(): MangaUpdatesAuthTicket {
		val session = store.snapshot() ?: throw IOException("MangaUpdates login required")
		return MangaUpdatesAuthTicket(session.token, session.generation)
	}
	fun ensureCurrent(ticket: MangaUpdatesAuthTicket) {
		if (store.generation.value != ticket.generation) throw CancellationException("MangaUpdates session changed")
	}
}
