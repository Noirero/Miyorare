package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.CatalogFailureKind
import org.koitharu.kotatsu.discovery.domain.CatalogHomeRequest
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogException
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogHome
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class MALCatalogDetailsSupplementalTest {
	private fun response(request: Request, code: Int = 200, body: String = "{}", retryAfter: String? = null): Response =
		Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
			.apply { retryAfter?.let { header("Retry-After", it) } }.body(body.toResponseBody()).build()

	private fun store() = object : CatalogSnapshotStore {
		override fun read(key: CatalogHomeRequest, now: Long): TrackerCatalogHome? = null
		override fun write(key: CatalogHomeRequest, home: TrackerCatalogHome) = true
	}

	@Test
	fun `MAL core details return without waiting for supplemental reads`() = runBlocking {
		val requested = mutableListOf<String>()
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			requested += "${request.url.host}${request.url.encodedPath}"
			if (request.url.host != "api.myanimelist.net") fail("Core details must not request supplemental metadata")
			response(request, body = """{"id":13,"title":"Work","media_type":"manga","nsfw":"white","mean":8.5,"rank":10,"popularity":20,"num_list_users":1281,"authors":[],"recommendations":[],"related_manga":[],"related_anime":[]}""")
		}.build())
		val provider = MALCatalogProvider(http, "fixture-public-id")
		val details = provider.details(TrackerCatalogKey(ScrobblerService.MAL, CatalogMediaKind.MANGA, "13"), false)

		assertTrue(details.characters.isEmpty())
		assertNull(details.statistics)
		assertFalse(details.partial)
		assertEquals(listOf("api.myanimelist.net/v2/manga/13"), requested)
	}

	@Test
	fun `supplemental metadata loads independently and caches successful components`() = runBlocking {
		var characterCalls = 0
		var statisticsCalls = 0
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			when {
				request.url.encodedPath.endsWith("/characters") -> {
					characterCalls++
					response(request, body = """{"data":[{"character":{"mal_id":1,"url":"https://myanimelist.net/character/1","images":{"jpg":{"image_url":"https://cdn.myanimelist.net/images/characters/1.jpg"}},"name":"Character 1"},"role":"Main"}]}""")
				}
				request.url.encodedPath.endsWith("/statistics") -> {
					statisticsCalls++
					response(request, body = """{"data":{"reading":120,"completed":340,"on_hold":12,"dropped":9,"plan_to_read":800,"total":1281,"scores":[]}}""")
				}
				else -> response(request, body = """{"id":13,"title":"Work","media_type":"manga","nsfw":"white","authors":[],"recommendations":[],"related_manga":[],"related_anime":[]}""")
			}
		}.build())
		val provider = MALCatalogProvider(http, "fixture-public-id")
		val repository = TrackerCatalogRepository(setOf(provider), store())
		val key = TrackerCatalogKey(ScrobblerService.MAL, CatalogMediaKind.MANGA, "13")

		val first = repository.malSupplemental(key)
		val second = repository.malSupplemental(key)

		assertFalse(first.partial)
		assertEquals("Character 1", first.characters.single().name)
		assertEquals(1281L, first.statistics?.total)
		assertEquals(first, second)
		assertEquals(1, characterCalls)
		assertEquals(1, statisticsCalls)
	}

	@Test
	fun `supplemental failure does not invalidate cached official MAL details`() = runBlocking {
		var officialCalls = 0
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			when {
				request.url.host == "api.myanimelist.net" -> {
					officialCalls++
					response(request, body = """{"id":13,"title":"Work","media_type":"manga","nsfw":"white","authors":[],"recommendations":[],"related_manga":[],"related_anime":[]}""")
				}
				request.url.encodedPath.endsWith("/characters") -> response(request, 503)
				request.url.host == "api.jikan.moe" && request.url.encodedPath.endsWith("/statistics") -> response(request, body = """{"data":{"reading":1,"completed":2,"on_hold":0,"dropped":0,"plan_to_read":3,"total":6,"scores":[]}}""")
				else -> response(request, 503)
			}
		}.build())
		val repository = TrackerCatalogRepository(setOf(MALCatalogProvider(http, "fixture-public-id")), store())
		val key = TrackerCatalogKey(ScrobblerService.MAL, CatalogMediaKind.MANGA, "13")

		val firstDetails = repository.details(key, false)
		val supplemental = repository.malSupplemental(key)
		val secondDetails = repository.details(key, false)

		assertTrue(supplemental.partial)
		assertTrue(supplemental.characters.isEmpty())
		assertEquals(6L, supplemental.statistics?.total)
		assertEquals(firstDetails, secondDetails)
		assertEquals(1, officialCalls)
	}

	@Test
	fun `Jikan MAL reads strip credentials stay host scoped and cannot be used by another provider`() = runBlocking {
		var officialCalls = 0
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			assertNull(request.header("Authorization"))
			assertNull(request.header("Cookie"))
			if (request.url.host == "api.jikan.moe") response(request, 429, retryAfter = "60")
			else { officialCalls++; response(request) }
		}.build())
		try {
			http.request(ScrobblerService.MAL, Request.Builder().url("https://api.jikan.moe/v4/manga/13/statistics").header("Authorization", "fixture").header("Cookie", "fixture").build())
			fail("Expected Jikan fixture rate limit")
		} catch (e: CatalogException) {
			assertEquals(CatalogFailureKind.RATE_LIMITED, e.failure.kind)
		}
		http.request(ScrobblerService.MAL, Request.Builder().url("https://api.myanimelist.net/v2/manga/13").build())
		assertEquals(1, officialCalls)
		try {
			http.request(ScrobblerService.ANILIST, Request.Builder().url("https://api.jikan.moe/v4/manga/13/statistics").build())
			fail("Jikan must only be an MAL catalogue origin")
		} catch (_: IllegalArgumentException) {
			// Expected: provider/host boundary stays fail-closed.
		}
	}
}
