package org.koitharu.kotatsu.sync.library

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.await

class LibrarySyncHttpException(val code: Int) : IOException("Library API HTTP $code") {
	val retryable: Boolean
		get() = code == 408 || code == 429 || code in 500..599
}

class LibrarySyncAuthException : IllegalStateException("Sign in again to continue library sync")

/** Deliberately isolated from Tracking auth, cookies, authenticators and debug token logging. */
class LibrarySyncHttp(
	private val id: LibrarySyncServiceId,
	private val state: LibrarySyncStateStore,
	minimumIntervalMs: Long,
	private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build(),
) {
	private val limiter = LibrarySyncRateLimiter(minimumIntervalMs)

	suspend fun request(request: Request): String =
		withContext(Dispatchers.IO) {
			if (state.retryAt(id) > System.currentTimeMillis()) throw LibrarySyncHttpException(429)
			limiter.awaitPermit()
			client.newCall(request).await().use { response ->
				if (response.code == 429) {
					state.setRetryAt(
						id,
						retryAfterAt(response.header("Retry-After"), System.currentTimeMillis()),
					)
				}
				if (!response.isSuccessful) throw LibrarySyncHttpException(response.code)
				response.body.string()
			}
		}

	suspend fun json(request: Request): JSONObject = JSONObject(request(request))
}

internal fun retryAfterAt(value: String?, now: Long): Long {
	val seconds = value?.toLongOrNull()
	val parsed =
		seconds?.coerceAtLeast(0)?.let { now + it.coerceAtMost(86400) * 1000 }
			?: value?.let {
				runCatching {
						ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
							.toInstant()
							.toEpochMilli()
					}
					.getOrNull()
			}
	return (parsed ?: (now + 60_000)).coerceAtLeast(now)
}

internal fun isLibrarySyncRetryable(error: Exception): Boolean =
	when (error) {
		is LibrarySyncHttpException -> error.retryable
		is LibrarySyncAuthException -> false
		is javax.net.ssl.SSLException -> false
		is IOException -> true
		else -> false
	}
