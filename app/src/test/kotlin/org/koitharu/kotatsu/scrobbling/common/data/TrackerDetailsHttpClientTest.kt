package org.koitharu.kotatsu.scrobbling.common.data

import okhttp3.Authenticator
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.network.CurlLoggingInterceptor

class TrackerDetailsHttpClientTest {
	@Test fun `optional client retains provider auth and networking without inheriting secret logging`() {
		val auth = Authenticator { _, _ -> null }
		val dns = Dns { emptyList() }
		val interceptor = Interceptor { it.proceed(it.request()) }
		val original = OkHttpClient.Builder().authenticator(auth).dns(dns)
			.addInterceptor(interceptor).addInterceptor(CurlLoggingInterceptor())
			.addNetworkInterceptor(CurlLoggingInterceptor()).build()
		val client = trackerDetailsClient(original)
		assertSame(auth, client.authenticator)
		assertSame(dns, client.dns)
		assertSame(original.proxySelector, client.proxySelector)
		assertSame(original.proxyAuthenticator, client.proxyAuthenticator)
		assertSame(original.cookieJar, client.cookieJar)
		assertEquals(listOf(interceptor), client.interceptors)
		assertTrue(client.networkInterceptors.isEmpty())
		assertNull(client.cache)
		assertFalse(client.followRedirects)
		assertFalse(client.followSslRedirects)
		assertTrue(client.callTimeoutMillis > 0)
		// Cloning for supplemental traffic must not alter the existing tracking client.
		assertTrue(original.interceptors.any { it is CurlLoggingInterceptor })
		assertTrue(original.followRedirects)
	}
}
