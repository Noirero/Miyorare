package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.network.CurlLoggingInterceptor
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

class MangaUpdatesApiTest {
	private class Sessions : MangaUpdatesSessionStore {
		override val generation = MutableStateFlow(0L)
		private var value: MangaUpdatesSession? = null
		@Synchronized override fun snapshot() = value
		@Synchronized override fun save(token: String, user: ScrobblerUser, expectedGeneration: Long): Boolean {
			if (generation.value != expectedGeneration) return false
			generation.value++
			value = MangaUpdatesSession(token, user, generation.value)
			return true
		}
		@Synchronized override fun clear(expectedGeneration: Long?): Boolean {
			if (expectedGeneration != null && expectedGeneration != generation.value) return false
			generation.value++; value = null; return true
		}
	}
	private val user = ScrobblerUser(1, "Fixture", null, ScrobblerService.MANGAUPDATES)
	private fun fixture(store: Sessions, reply: (Request) -> Pair<Int, String>): OkHttpClient = OkHttpClient.Builder()
		.followRedirects(false).followSslRedirects(false).addInterceptor(MangaUpdatesInterceptor(store)).addInterceptor { chain ->
			val request = chain.request()
			val (code, body) = reply(request)
			Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
				.body(body.toResponseBody("application/json".toMediaType())).build()
		}.build()

	@Test fun `credentials stay on the intended HTTPS origin and public calls have no bearer`() = runTest {
		val store = Sessions().apply { save("fixture-session", user, 0) }
		val requests = mutableListOf<Request>()
		val client = fixture(store) { requests += it; 200 to "{}" }
		val api = MangaUpdatesApi(client, store, MANGAUPDATES_API)
		api.request("GET", "account/profile", ticket = api.sessionTicket())
		api.request("GET", "series/17360452316")
		assertEquals("Bearer fixture-session", requests.first().header("Authorization"))
		assertNull(requests.last().header("Authorization"))
		for (url in listOf("https://api.mangaupdates.com.evil/v1/account/profile", "http://api.mangaupdates.com/v1/account/profile", "https://api.mangaupdates.com:8443/v1/account/profile", "https://api.mangaupdates.com/account/profile")) {
			assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(url).tag(MangaUpdatesAuthTicket::class.java, api.sessionTicket()).build()).execute().close() }
		}
		assertEquals(2, requests.size)
	}

	@Test fun `expired session clears storage and changes the metadata generation`() = runTest {
		val store = Sessions().apply { save("fixture-session", user, 0) }
		val api = MangaUpdatesApi(fixture(store) { 401 to "secret-shaped fixture body" }, store, MANGAUPDATES_API)
		val generation = store.generation.value
		try { api.request("GET", "account/profile", ticket = api.sessionTicket()); fail("Expected 401") } catch (e: MangaUpdatesHttpException) { assertEquals(401, e.code); assertFalse(e.message.orEmpty().contains("secret-shaped")) }
		assertNull(store.snapshot())
		assertTrue(store.generation.value > generation)
	}

	@Test fun `late unauthorized response cannot clear a replacement account`() = runTest {
		val store = Sessions().apply { save("old-fixture", user, 0) }
		val client = fixture(store) { store.save("new-fixture", user.copy(id = 2), store.generation.value); 401 to "{}" }
		val api = MangaUpdatesApi(client, store, MANGAUPDATES_API)
		try { api.request("GET", "account/profile", ticket = api.sessionTicket()); fail("Expected 401") } catch (_: MangaUpdatesHttpException) { }
		assertEquals(2L, store.snapshot()?.user?.id)
		assertEquals("new-fixture", store.snapshot()?.token)
	}

	@Test fun `late successful response is rejected after account change`() = runTest {
		val store = Sessions().apply { save("old-fixture", user, 0) }
		val api = MangaUpdatesApi(fixture(store) { store.save("new-fixture", user.copy(id = 2), store.generation.value); 200 to "{}" }, store, MANGAUPDATES_API)
		try { api.request("GET", "account/profile", ticket = api.sessionTicket()); fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
		assertEquals(2L, store.snapshot()?.user?.id)
	}

	@Test fun `failed provisional login does not invalidate the existing account`() = runTest {
		val store = Sessions().apply { save("active-fixture", user, 0) }
		val api = MangaUpdatesApi(fixture(store) { 401 to "{}" }, store, MANGAUPDATES_API)
		try { api.request("GET", "account/profile", ticket = MangaUpdatesAuthTicket("candidate-fixture", store.generation.value, false)); fail("Expected 401") } catch (_: MangaUpdatesHttpException) { }
		assertEquals("active-fixture", store.snapshot()?.token)
	}

	@Test fun `credential client preserves proxy and DNS with independent TLS and no logging or redirects`() {
		val store = Sessions()
		val verifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
		val original = OkHttpClient.Builder().hostnameVerifier(verifier).addInterceptor(CurlLoggingInterceptor()).build()
		val client = mangaUpdatesClient(original, store)
		assertSame(original.dns, client.dns)
		assertSame(original.proxySelector, client.proxySelector)
		assertSame(original.proxyAuthenticator, client.proxyAuthenticator)
		assertNotSame(verifier, client.hostnameVerifier)
		assertSame(Authenticator.NONE, client.authenticator)
		assertSame(CookieJar.NO_COOKIES, client.cookieJar)
		assertNull(client.cache)
		assertEquals(1, client.interceptors.size)
		assertTrue(client.interceptors.single() is MangaUpdatesInterceptor)
		assertTrue(client.networkInterceptors.isEmpty())
		assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
		assertTrue(client.callTimeoutMillis > 0)
	}

	@Test fun `malformed response and model diagnostics do not expose credential bodies`() = runTest {
		val store = Sessions()
		val api = MangaUpdatesApi(fixture(store) { 200 to "not JSON containing fixture-password" }, store, MANGAUPDATES_API)
		try { api.request("PUT", "account/login"); fail("Expected malformed response") } catch (e: IOException) { assertFalse(e.message.orEmpty().contains("fixture-password")) }
		assertFalse(MangaUpdatesAuthTicket("fixture-session", 0).toString().contains("fixture-session"))
		assertFalse(MangaUpdatesSession("fixture-session", user, 0).toString().contains("fixture-session"))
	}

	@Test fun `concurrent account reads are bounded and queued stale requests send no credentials`() = runBlocking {
		val store = Sessions().apply { save("fixture-session", user, 0) }
		val started = CountDownLatch(3)
		val release = CountDownLatch(1)
		val count = AtomicInteger()
		val api = MangaUpdatesApi(fixture(store) {
			count.incrementAndGet(); started.countDown()
			check(release.await(10, TimeUnit.SECONDS))
			200 to "{}"
		}, store, MANGAUPDATES_API)
		val ticket = api.sessionTicket()
		val calls = List(6) { async { api.request("GET", "account/profile", ticket = ticket) } }
		try {
			assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
			assertEquals(3, count.get())
			store.clear(); release.countDown()
			for (call in calls) try { call.await(); fail("Expected stale account rejection") } catch (_: kotlinx.coroutines.CancellationException) { }
			assertEquals(3, count.get())
		} finally { release.countDown(); calls.forEach { it.cancel() } }
	}

	@Test fun `cancellation after headers interrupts a slow body without blocking the caller`() = runBlocking {
		val entered = CountDownLatch(1)
		val ended = CountDownLatch(1)
		val client = OkHttpClient.Builder().addInterceptor { chain ->
			val source = object : Source {
				override fun timeout() = Timeout.NONE
				override fun close() = Unit
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
		val api = MangaUpdatesApi(client, Sessions(), MANGAUPDATES_API)
		val flight = async { api.request("GET", "series/17360452316") }
		try {
			assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
			flight.cancelAndJoin()
			assertTrue(withContext(Dispatchers.IO) { ended.await(5, TimeUnit.SECONDS) })
		} finally { flight.cancelAndJoin() }
	}
}
