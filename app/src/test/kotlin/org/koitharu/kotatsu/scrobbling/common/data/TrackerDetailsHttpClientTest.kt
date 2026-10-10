package org.koitharu.kotatsu.scrobbling.common.data

import okhttp3.Authenticator
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.network.CurlLoggingInterceptor

class TrackerDetailsHttpClientTest {
	@Test fun `complete body parsing preserves a partial GraphQL response`() = runBlocking {
		val body = """{"data":{"Media":{"id":7}},"errors":[{"message":"fixture partial data"}]}"""
		val client = OkHttpClient.Builder().addInterceptor { chain ->
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
				.body(body.toResponseBody("application/json".toMediaType())).build()
		}.build()
		val root = client.newCall(Request.Builder().url("https://fixture.invalid/metadata").build()).awaitTrackerDetails {
			Json.parseToJsonElement(it.body.string()).jsonObject
		}
		assertEquals(7, root.getValue("data").jsonObject.getValue("Media").jsonObject.getValue("id").jsonPrimitive.int)
		assertEquals(1, root.getValue("errors").jsonArray.size)
	}

	@Test fun `cancellation after headers terminates body consumption closes it and prevents publication`() = runBlocking {
		val entered = CountDownLatch(1)
		val ended = CountDownLatch(1)
		val closed = CountDownLatch(1)
		val published = AtomicBoolean(false)
		val client = OkHttpClient.Builder().addInterceptor { chain ->
			val source = object : Source {
				override fun timeout() = Timeout.NONE
				override fun close() { closed.countDown() }
				override fun read(sink: Buffer, byteCount: Long): Long {
					entered.countDown()
					try {
						val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
						while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
						if (!chain.call().isCanceled()) throw IOException("Fixture cancellation timed out")
						throw IOException("Fixture body canceled")
					} finally { ended.countDown() }
				}
			}.buffer()
			val body = object : ResponseBody() {
				override fun contentType() = "application/json".toMediaType()
				override fun contentLength() = -1L
				override fun source() = source
			}
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
		}.build()
		val call = client.newCall(Request.Builder().url("https://fixture.invalid/metadata").build())
		val flight = async {
			call.awaitTrackerDetails { Json.parseToJsonElement(it.body.string()) }
			published.set(true)
		}
		try {
			assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
			withTimeout(5000) { flight.cancelAndJoin() }
			assertTrue(call.isCanceled())
			assertTrue(withContext(Dispatchers.IO) { ended.await(5, TimeUnit.SECONDS) })
			assertTrue(withContext(Dispatchers.IO) { closed.await(5, TimeUnit.SECONDS) })
			assertFalse(published.get())
		} finally { flight.cancelAndJoin() }
	}

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
