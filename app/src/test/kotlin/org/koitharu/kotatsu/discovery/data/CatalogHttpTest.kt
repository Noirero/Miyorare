package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService as Service
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CatalogHttpTest {
	@Test fun `public reads strip session credentials and reject any wrong origin`() = runBlocking {
		var calls = 0
		val base = OkHttpClient.Builder().addInterceptor { chain ->
			calls++; assertNull(chain.request().header("Authorization")); assertNull(chain.request().header("Cookie"))
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body("{}".toResponseBody()).build()
		}.build()
		val http = CatalogHttp(base)
		http.request(Service.ANILIST, Request.Builder().url("https://graphql.anilist.co").header("Authorization", "fixture-only").header("Cookie", "fixture-only").build())
		for (url in listOf("http://graphql.anilist.co", "https://graphql.anilist.co.evil.invalid", "https://graphql.anilist.co:444", "https://user@graphql.anilist.co")) {
			try { http.request(Service.ANILIST, Request.Builder().url(url).build()); fail(url) } catch (_: IllegalArgumentException) { }
		}
		assertEquals(1, calls); assertFalse(publicCatalogClient(base).followRedirects); assertSame(base.dns, publicCatalogClient(base).dns)
	}
	@Test fun `provider retry headers are honored without fixed requests per minute`() = runBlocking {
		var calls = 0
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			calls++; Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("fixture").header("Retry-After", "60").body("{}".toResponseBody()).build()
		}.build())
		repeat(2) {
			try { http.request(Service.ANILIST, Request.Builder().url("https://graphql.anilist.co").build()); fail() }
			catch (e: CatalogException) { assertEquals(CatalogFailureKind.RATE_LIMITED, e.failure.kind); assertTrue(e.failure.retryAt!! > System.currentTimeMillis()) }
		}
		assertEquals(1, calls)
		assertEquals(61000L, catalogRetryAt("60", null, 1000L)); assertNull(catalogRetryAt("nonsense", "nonsense", 1000L))
	}
	@Test fun `cancel during full catalogue body consumption cancels call closes body and cannot publish`() = runBlocking {
		val entered = CountDownLatch(1); val ended = CountDownLatch(1); val closed = CountDownLatch(1)
		val canceled = AtomicBoolean(); val published = AtomicBoolean()
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val source = object : Source {
				override fun timeout() = Timeout.NONE
				override fun close() { closed.countDown() }
				override fun read(sink: Buffer, byteCount: Long): Long {
					entered.countDown()
					try {
						val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
						while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
						canceled.set(chain.call().isCanceled()); throw IOException("fixture canceled")
					} finally { ended.countDown() }
				}
			}.buffer()
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(object : ResponseBody() {
				override fun contentType() = "application/json".toMediaType()
				override fun contentLength() = -1L
				override fun source() = source
			}).build()
		}.build())
		val flight = async { http.request(Service.ANILIST, Request.Builder().url("https://graphql.anilist.co").build()); published.set(true) }
		try {
			assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }); flight.cancelAndJoin()
			assertTrue(withContext(Dispatchers.IO) { ended.await(5, TimeUnit.SECONDS) }); assertTrue(withContext(Dispatchers.IO) { closed.await(5, TimeUnit.SECONDS) })
			assertTrue(canceled.get()); assertFalse(published.get())
		} finally { flight.cancelAndJoin() }
	}
}
