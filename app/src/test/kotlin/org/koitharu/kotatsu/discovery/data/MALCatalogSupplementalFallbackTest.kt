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
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogPerson
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class MALCatalogSupplementalFallbackTest {
	private fun response(request: Request, code: Int = 200, body: String = "{}"): Response = Response.Builder()
		.request(request)
		.protocol(Protocol.HTTP_1_1)
		.code(code)
		.message("fixture")
		.body(body.toResponseBody())
		.build()

	@Test
	fun `fallback restores MAL characters and statistics when primary supplemental origin is unavailable`() = runBlocking {
		val requested = mutableListOf<String>()
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			requested += "${request.url.host}${request.url.encodedPath}"
			when {
				request.url.host == "api.jikan.moe" -> response(request, 503)
				request.url.host == "jikan.lucashdo.com" && request.url.encodedPath.endsWith("/characters") -> response(
					request,
					body = """{"data":[{"malId":40,"name":"Monkey D. Luffy","imageUrl":"https://cdn.myanimelist.net/images/characters/40.jpg","role":"Main","favorites":100,"voiceActors":[]},{"malId":62,"name":"Roronoa Zoro","imageUrl":"https://cdn.myanimelist.net/images/characters/62.jpg","role":"Main","favorites":90,"voiceActors":[]}]}""",
				)
				request.url.host == "jikan.lucashdo.com" && request.url.encodedPath.endsWith("/statistics") -> response(
					request,
					body = """{"data":{"status":{"inProgress":100,"completed":200,"onHold":30,"dropped":20,"planned":400,"total":750},"scores":[{"score":10,"votes":300,"percentage":50.0}],"fetchedAt":"2026-10-29T00:00:00.000Z"}}""",
				)
				else -> response(
					request,
					body = """{"id":13,"title":"One Piece","media_type":"manga","nsfw":"white","mean":9.2,"authors":[],"recommendations":[],"related_manga":[],"related_anime":[]}""",
				)
			}
		}.build())

		val details = MALCatalogProvider(http, "fixture-public-id")
			.details(TrackerCatalogKey(ScrobblerService.MAL, CatalogMediaKind.MANGA, "13"), false)

		assertFalse(details.partial)
		assertEquals(listOf("Monkey D. Luffy", "Roronoa Zoro"), details.characters.map { it.name })
		assertEquals("https://cdn.myanimelist.net/images/characters/40.jpg", details.characters.first().imageUrl)
		assertEquals("Main", details.characters.first().roles.single())
		assertEquals(100L, details.statistics?.reading)
		assertEquals(30L, details.statistics?.onHold)
		assertEquals(400L, details.statistics?.planToRead)
		assertEquals(750L, details.statistics?.total)
		assertTrue(requested.contains("api.jikan.moe/v4/manga/13/characters"))
		assertTrue(requested.contains("api.jikan.moe/v4/manga/13/statistics"))
		assertTrue(requested.contains("jikan.lucashdo.com/v1/manga/13/characters"))
		assertTrue(requested.contains("jikan.lucashdo.com/v1/manga/13/statistics"))
	}

	@Test
	fun `character profile falls back and preserves full internal profile data`() = runBlocking {
		val requested = mutableListOf<String>()
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			requested += "${request.url.host}${request.url.encodedPath}"
			when (request.url.host) {
				"api.jikan.moe" -> response(request, 503)
				"jikan.lucashdo.com" -> response(
					request,
					body = """{"data":{"malId":40,"name":"Monkey D. Luffy","nameKanji":"モンキー・D・ルフィ","nicknames":["Straw Hat"],"imageUrl":"https://cdn.myanimelist.net/images/characters/40-large.jpg","images":{"large":"https://cdn.myanimelist.net/images/characters/40-large.jpg"},"about":"Captain of the Straw Hat Pirates.","favorites":123456,"manga":[{"malId":13,"title":"One Piece","imageUrl":"https://cdn.myanimelist.net/images/manga/13.jpg","role":"Main"}],"anime":[{"malId":21,"title":"One Piece","imageUrl":"https://cdn.myanimelist.net/images/anime/21.jpg","role":"Main"}],"voices":[{"malId":185,"name":"Mayumi Tanaka","imageUrl":"https://cdn.myanimelist.net/images/people/185.jpg","language":"Japanese"}]}}""",
				)
				else -> response(request, 404)
			}
		}.build())
		val seed = TrackerCatalogPerson(
			id = "40",
			name = "Monkey D. Luffy",
			imageUrl = "https://cdn.myanimelist.net/images/characters/40.jpg",
			roles = listOf("Main"),
		)

		val profile = MALCatalogProvider(http, "fixture-public-id").characterDetails(seed)

		assertEquals("40", profile.person.id)
		assertEquals("Monkey D. Luffy", profile.person.name)
		assertEquals("モンキー・D・ルフィ", profile.nativeName)
		assertEquals(listOf("Straw Hat"), profile.nicknames)
		assertEquals("Captain of the Straw Hat Pirates.", profile.about)
		assertEquals(123456L, profile.favorites)
		assertEquals(2, profile.works.size)
		assertEquals(CatalogRelatedMediaKind.MANGA, profile.works.first().mediaKind)
		assertEquals("13", profile.works.first().remoteId)
		assertEquals("https://cdn.myanimelist.net/images/manga/13.jpg", profile.works.first().coverUrl)
		assertEquals("Main", profile.works.first().role)
		assertEquals("Mayumi Tanaka", profile.voices.single().name)
		assertEquals("https://cdn.myanimelist.net/images/people/185.jpg", profile.voices.single().imageUrl)
		assertEquals("Japanese", profile.voices.single().roles.single())
		assertTrue(requested.contains("api.jikan.moe/v4/characters/40/full"))
		assertTrue(requested.contains("jikan.lucashdo.com/v1/characters/40/full"))
	}

	@Test
	fun `fallback is credential free and remains MAL scoped`() = runBlocking {
		var calls = 0
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			assertNull(request.header("Authorization"))
			assertNull(request.header("Cookie"))
			calls++
			response(request)
		}.build())

		http.request(
			ScrobblerService.MAL,
			Request.Builder().url("https://jikan.lucashdo.com/v1/manga/13/statistics")
				.header("Authorization", "fixture").header("Cookie", "fixture").build(),
		)
		assertEquals(1, calls)
		try {
			http.request(
				ScrobblerService.ANILIST,
				Request.Builder().url("https://jikan.lucashdo.com/v1/manga/13/statistics").build(),
			)
			fail("Fallback must only be available inside the MAL catalogue boundary")
		} catch (_: IllegalArgumentException) {
			// Expected: provider/host boundary stays fail-closed.
		}
	}
}
