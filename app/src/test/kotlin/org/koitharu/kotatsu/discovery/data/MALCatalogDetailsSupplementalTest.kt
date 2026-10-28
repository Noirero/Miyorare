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
import org.koitharu.kotatsu.discovery.domain.CatalogCapability
import org.koitharu.kotatsu.discovery.domain.CatalogFailureKind
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogException
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class MALCatalogDetailsSupplementalTest {
	private fun response(request: Request, code: Int = 200, body: String = "{}", retryAfter: String? = null): Response =
		Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("fixture")
			.apply { retryAfter?.let { header("Retry-After", it) } }
			.body(body.toResponseBody())
			.build()

	@Test
	fun `MAL details include every public character image and full community statistics`() = runBlocking {
		val requested = mutableListOf<String>()
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			requested += "${request.url.host}${request.url.encodedPath}"
			when {
				request.url.host == "api.jikan.moe" && request.url.encodedPath.endsWith("/characters") -> {
					val characters = (1..12).joinToString(",") { index ->
						"""{"character":{"mal_id":$index,"url":"https://myanimelist.net/character/$index/Character_$index","images":{"jpg":{"image_url":"https://cdn.myanimelist.net/images/characters/$index.jpg"}},"name":"Character $index"},"role":"${if (index == 1) "Main" else "Supporting"}"}"""
					}
					response(request, body = """{"data":[$characters]}""")
				}
				request.url.host == "api.jikan.moe" && request.url.encodedPath.endsWith("/statistics") -> response(
					request,
					body = """{"data":{"reading":120,"completed":340,"on_hold":12,"dropped":9,"plan_to_read":800,"total":1281,"scores":[{"score":10,"votes":200,"percentage":40.5},{"score":9,"votes":120,"percentage":24.3}]}}""",
				)
				else -> response(
					request,
					body = """{"id":13,"title":"Work","media_type":"manga","nsfw":"white","mean":8.5,"rank":10,"popularity":20,"num_list_users":1281,"authors":[],"recommendations":[],"related_manga":[],"related_anime":[]}""",
				)
			}
		}.build())
		val provider = MALCatalogProvider(http, "fixture-public-id")

		val details = provider.details(TrackerCatalogKey(ScrobblerService.MAL, CatalogMediaKind.MANGA, "13"), false)

		assertTrue(CatalogCapability.CHARACTERS in provider.catalogCapabilities)
		assertEquals(12, details.characters.size)
		assertEquals("Character 1", details.characters.first().name)
		assertEquals("https://cdn.myanimelist.net/images/characters/1.jpg", details.characters.first().imageUrl)
		assertEquals("Main", details.characters.first().roles.single())
		assertEquals("https://myanimelist.net/character/1/Character_1", details.characters.first().providerUrl)
		assertEquals(120L, details.statistics?.reading)
		assertEquals(340L, details.statistics?.completed)
		assertEquals(1281L, details.statistics?.total)
		assertEquals(listOf(10, 9), details.statistics?.scores?.map { it.score })
		assertFalse(details.partial)
		assertTrue(requested.contains("api.jikan.moe/v4/manga/13/characters"))
		assertTrue(requested.contains("api.jikan.moe/v4/manga/13/statistics"))
	}

	@Test
	fun `one supplemental failure keeps official MAL details and marks metadata partial`() = runBlocking {
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			when {
				request.url.host == "api.jikan.moe" && request.url.encodedPath.endsWith("/characters") -> response(request, 503)
				request.url.host == "api.jikan.moe" -> response(request, body = """{"data":{"reading":1,"completed":2,"on_hold":0,"dropped":0,"plan_to_read":3,"total":6,"scores":[]}}""")
				else -> response(request, body = """{"id":13,"title":"Work","media_type":"manga","nsfw":"white","authors":[],"recommendations":[],"related_manga":[],"related_anime":[]}""")
			}
		}.build())

		val details = MALCatalogProvider(http, "fixture-public-id")
			.details(TrackerCatalogKey(ScrobblerService.MAL, CatalogMediaKind.MANGA, "13"), false)

		assertTrue(details.partial)
		assertTrue(details.characters.isEmpty())
		assertEquals(6L, details.statistics?.total)
	}

	@Test
	fun `Jikan MAL reads strip credentials stay host scoped and cannot be used by another provider`() = runBlocking {
		var officialCalls = 0
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			assertNull(request.header("Authorization"))
			assertNull(request.header("Cookie"))
			if (request.url.host == "api.jikan.moe") response(request, 429, retryAfter = "60")
			else {
				officialCalls++
				response(request)
			}
		}.build())

		try {
			http.request(
				ScrobblerService.MAL,
				Request.Builder().url("https://api.jikan.moe/v4/manga/13/statistics")
					.header("Authorization", "fixture").header("Cookie", "fixture").build(),
			)
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
