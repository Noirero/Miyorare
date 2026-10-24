package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService as Service

class CatalogProvidersTest {
	private fun http(answer: (Request) -> String) = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
		Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(answer(chain.request()).toResponseBody("application/json".toMediaType())).build()
	}.build())
	private val aniManga = """{"id":42,"type":"MANGA","format":"MANGA","isAdult":false,"title":{"userPreferred":"Manga"},"averageScore":85,"siteUrl":"https://anilist.co/manga/42"}"""
	private val aniNovel = """{"id":42,"type":"MANGA","format":"NOVEL","isAdult":false,"title":{"romaji":"Novel"}}"""
	private val malManga = """{"id":13,"title":"Manga","media_type":"manga","nsfw":"white","mean":8.7}"""
	private val malNovel = """{"id":14,"title":"Novel","media_type":"light_novel","nsfw":"white"}"""
	private val kitsuManga = """{"id":"21","type":"manga","attributes":{"canonicalTitle":"Manga","subtype":"manga","ageRating":"PG","averageRating":"85.5"}}"""
	private val kitsuNovel = """{"id":"22","type":"manga","attributes":{"canonicalTitle":"Novel","subtype":"novel","ageRating":"R"}}"""
	private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

	@Test fun `AniList Home batches eight real rails with separate format filters`() = runBlocking {
		var calls = 0
		val provider = AniListCatalogProvider(http { request ->
			calls++
			val body = BufferText(request)
			assertTrue(body.contains("format:NOVEL")); assertTrue(body.contains("format_not:NOVEL")); assertTrue(body.contains("TRENDING_DESC")); assertTrue(body.contains("startDate_lesser:"))
			assertFalse(body.contains("synonyms")); assertNull(request.header("Authorization"))
			val sections = (0..7).joinToString { i -> "\"s$i\":{\"pageInfo\":{\"hasNextPage\":true},\"media\":[${if (i < 4) aniManga else aniNovel}]}" }
			"""{"data":{$sections}}"""
		})
		val home = provider.home(CatalogFilter.ALL, false)
		assertEquals(1, calls); assertEquals(8, home.sections.size)
		assertEquals(setOf(CatalogMediaKind.MANGA, CatalogMediaKind.NOVEL), home.sections.map { it.key.kind }.toSet())
		assertTrue(home.sections.all { it.items.single().key.kind == it.key.kind && it.nextPage == 2 })
	}
	@Test fun `AniList partial GraphQL rail does not hide successful rail`() = runBlocking {
		val p = AniListCatalogProvider(http { """{"data":{"s0":{"media":[$aniManga]}},"errors":[{"message":"fixture"}]}""" })
		val result = p.home(CatalogFilter.MANGA, false)
		assertEquals(1, result.sections.count { it.failure == null }); assertEquals(3, result.sections.count { it.failure != null })
	}
	@Test fun `AniList search escapes input and preserves Novel identity and cursor`() = runBlocking {
		val p = AniListCatalogProvider(http { r ->
			val query = obj(BufferText(r))["query"]!!.jsonPrimitive.content
			assertTrue(query.contains("search:\"a\\\"b\"")); assertTrue(query.contains("format:NOVEL"))
			"""{"data":{"Page":{"media":[$aniNovel],"pageInfo":{"hasNextPage":true}}}}"""
		})
		assertEquals(TrackerCatalogKey(Service.ANILIST, CatalogMediaKind.NOVEL, "42"), p.search("a\"b", CatalogFilter.NOVEL, false, 1).items.single().key)
	}
	@Test fun `MAL public rankings use client id truthful semantics and type filtering`() = runBlocking {
		val p = MALCatalogProvider(http { r ->
			assertEquals("fixture-public-id", r.header("X-MAL-CLIENT-ID")); assertNull(r.header("Authorization"))
			assertEquals("bypopularity", r.url.queryParameter("ranking_type")); assertTrue(r.url.queryParameter("fields")!!.contains("media_type"))
			"""{"data":[{"node":$malManga},{"node":$malNovel}],"paging":{"next":"https://untrusted.invalid/ignored"}}"""
		}, "fixture-public-id")
		val result = p.browseSection(CatalogSectionKey(Service.MAL, CatalogMediaKind.NOVEL, CatalogSectionType.POPULAR), false, 1)
		assertEquals("14", result.items.single().key.remoteId); assertEquals(2, result.nextPage)
		assertEquals("novels", p.rankingType(CatalogSectionKey(Service.MAL, CatalogMediaKind.NOVEL, CatalogSectionType.TOP_RANKED)))
	}
	@Test fun `MAL details requests author names and recommendations retain actual kind`() = runBlocking {
		val p = MALCatalogProvider(http { r ->
			assertTrue(r.url.queryParameter("fields")!!.contains("authors{node{first_name,last_name},role}"))
			malManga.dropLast(1) + """, "authors":[{"node":{"first_name":"Author","last_name":"Name"},"role":"Story"}],"recommendations":[{"node":$malNovel}]}"""
		}, "fixture-public-id")
		val detail = p.details(TrackerCatalogKey(Service.MAL, CatalogMediaKind.MANGA, "13"), false)
		assertEquals(listOf("Author Name · Story"), detail.staff); assertEquals(CatalogMediaKind.NOVEL, detail.recommendations.single().key.kind)
	}
	@Test fun `Kitsu trending is real unpaged collection and types remain separated`() = runBlocking {
		var calls = 0
		val p = KitsuCatalogProvider(http { r ->
			calls++; assertEquals("/api/edge/trending/manga", r.url.encodedPath); assertNull(r.header("Authorization"))
			"""{"data":[$kitsuManga,$kitsuNovel]}"""
		})
		val result = p.browseSection(CatalogSectionKey(Service.KITSU, CatalogMediaKind.NOVEL, CatalogSectionType.TRENDING), false, 1)
		assertEquals("22", result.items.single().key.remoteId); assertNull(result.nextPage); assertEquals(1, calls)
		try { p.browseSection(CatalogSectionKey(Service.KITSU, CatalogMediaKind.NOVEL, CatalogSectionType.TRENDING), false, 2); fail() } catch (e: CatalogException) { assertEquals(CatalogFailureKind.UNSUPPORTED, e.failure.kind) }
	}
	@Test fun `Kitsu search uses subtype paging and public JSON API contract`() = runBlocking {
		val p = KitsuCatalogProvider(http { r ->
			assertEquals("novel", r.url.queryParameter("filter[subtype]")); assertEquals("title", r.url.queryParameter("filter[text]")); assertEquals("20", r.url.queryParameter("page[offset]"))
			assertFalse(r.url.queryParameter("fields[manga]")!!.contains("nsfw"))
			"""{"data":[$kitsuNovel],"links":{"next":"https://kitsu.app/api/edge/manga?page[offset]=40"}}"""
		})
		assertEquals(3, p.search("title", CatalogFilter.NOVEL, false, 2).nextPage)
	}
	@Test fun `optional malformed AniList recommendations preserve primary detail and honest partial state`() = runBlocking {
		val media = aniManga.dropLast(1) + """, "startDate":{"year":2024,"month":null,"day":null},"recommendations":{"nodes":[{"mediaRecommendation":{"id":7}}]}}"""
		val p = AniListCatalogProvider(http { """{"data":{"Media":$media}}""" })
		val detail = p.details(TrackerCatalogKey(Service.ANILIST, CatalogMediaKind.MANGA, "42"), false)
		assertEquals("Manga", detail.item.title); assertEquals("2024", detail.startDate); assertTrue(detail.partial); assertTrue(detail.recommendations.isEmpty())
	}
	@Test fun `Kitsu staff failure cannot discard valid public metadata detail`() = runBlocking {
		val p = KitsuCatalogProvider(http { r ->
			if (r.url.encodedPath.endsWith("/staff")) throw java.io.IOException("fixture staff offline")
			"""{"data":$kitsuManga}"""
		})
		val detail = p.details(TrackerCatalogKey(Service.KITSU, CatalogMediaKind.MANGA, "21"), false)
		assertEquals("Manga", detail.item.title); assertTrue(detail.partial); assertTrue(detail.staff.isEmpty())
	}
	@Test fun `policy never shows known adult or unknown-rating covers when disabled`() {
		val unknown = parseMALCard(obj(malManga.replace("\"white\"", "null").dropLast(1) + ",\"main_picture\":{\"large\":\"https://image.invalid/a\"}}"), false)!!
		assertNull(unknown.coverUrl)
		assertNull(parseAniListCard(obj(aniManga.replace("\"isAdult\":false", "\"isAdult\":true")), false))
		assertNotNull(parseAniListCard(obj(aniManga.replace("\"isAdult\":false", "\"isAdult\":true")), true))
	}
	@Test fun `identity is provider and media kind qualified with full width ids`() {
		val id = Long.MAX_VALUE.toString()
		val a = TrackerCatalogKey(Service.KITSU, CatalogMediaKind.MANGA, id)
		assertNotEquals(a, a.copy(service = Service.MAL)); assertNotEquals(a, a.copy(kind = CatalogMediaKind.NOVEL)); assertEquals(id, a.remoteId)
	}
	@Test fun `all malformed records are invalid data but empty collection is legitimate`() = runBlocking {
		for (data in listOf("[]", "[{\"node\":{\"id\":13}}]")) {
			val p = MALCatalogProvider(http { """{"data":$data}""" }, "fixture")
			try { val r = p.search("x", CatalogFilter.ALL, false, 1); assertEquals("[]", data); assertTrue(r.items.isEmpty()) }
			catch (e: CatalogException) { assertNotEquals("[]", data); assertEquals(CatalogFailureKind.INVALID_DATA, e.failure.kind) }
		}
	}
	private fun BufferText(request: Request): String = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
}
