package org.koitharu.kotatsu.scrobbling.common.data

import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.CurlLoggingInterceptor
import java.util.concurrent.TimeUnit

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
