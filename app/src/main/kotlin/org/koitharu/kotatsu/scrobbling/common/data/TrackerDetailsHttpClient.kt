package org.koitharu.kotatsu.scrobbling.common.data

import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koitharu.kotatsu.core.network.CurlLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Scope to optional catalog reads; leave legacy tracking/auth clients unchanged. */
internal fun trackerDetailsClient(providerClient: OkHttpClient): OkHttpClient = providerClient.newBuilder().apply {
	// Some legacy clients log after their bearer interceptor. Never inherit that logger here.
	interceptors().removeAll { it is CurlLoggingInterceptor }
	networkInterceptors().removeAll { it is CurlLoggingInterceptor }
	cache(null)
	followRedirects(false)
	followSslRedirects(false)
	callTimeout(40, TimeUnit.SECONDS)
}.build()

/** Keep call cancellation attached until optional metadata is fully consumed on OkHttp's worker. */
internal suspend fun <T> Call.awaitTrackerDetails(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
	continuation.invokeOnCancellation { cancel() }
	enqueue(object : Callback {
		override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
		override fun onResponse(call: Call, response: Response) {
			try {
				val result = response.use {
					continuation.context.ensureActive()
					read(it).also { continuation.context.ensureActive() }
				}
				continuation.resume(result)
			} catch (e: Exception) { continuation.resumeWithException(e) }
		}
	})
}
