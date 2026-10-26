package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogSectionKey
import org.koitharu.kotatsu.discovery.domain.CatalogSectionType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType

class MangaBakaCatalogProviderTest {
	private val manga = """{"id":701,"type":"manga","content_rating":"safe","title":"Manga"}"""
	private val manhwa = """{"id":702,"type":"manhwa","content_rating":"safe","title":"Manhwa"}"""
	private val novel = """{"id":703,"type":"novel","content_rating":"safe","title":"Novel"}"""

	@Test
	fun `public card keeps provider identity cover score novel kind and provider type`() {
		val card = parseMangaBakaCatalogCard(
			json(
				"""
				{
				  "id": 42,
				  "type": "novel",
				  "content_rating": "safe",
				  "rating": 87.5,
				  "canonical_url": "https://mangabaka.org/42",
				  "titles": [
				    {"language":"ja-Latn","title":"Romaji","is_primary":true},
				    {"language":"en","title":"English title","is_primary":true}
				  ],
				  "cover": {"x350":"https://img.example/42.jpg"}
				}
				""",
			),
			allowAdult = false,
		)
		requireNotNull(card)
		assertEquals("42", card.key.remoteId)
		assertEquals(CatalogMediaKind.NOVEL, card.key.kind)
		assertEquals("novel", card.providerType)
		assertEquals("English title", card.title)
		assertEquals("https://img.example/42.jpg", card.coverUrl)
		assertEquals("https://mangabaka.org/42", card.providerUrl)
		assertEquals(8.75, card.score ?: 0.0, 0.001)
	}

	@Test
	fun `All Home exposes truthful mixed MangaBaka discovery rails`() = runBlocking {
		val provider = MangaBakaCatalogProvider(http { request ->
			when (request.url.encodedPath) {
				"/v2/series/discover/rising", "/v2/series/discover/hidden-gems" -> {
					assertNull(request.url.queryParameter("type"))
					assertNull(request.url.queryParameter("type_not"))
					"""{"data":[$manhwa,$novel],"pagination":{"next":false}}"""
				}
				"/v2/series/search" -> {
					assertEquals("latest", request.url.queryParameter("sort_by"))
					assertNull(request.url.queryParameter("type"))
					"""{"data":[$manga,$novel],"pagination":{"next":false}}"""
				}
				else -> error("Unexpected request ${request.url}")
			}
		})
		val home = provider.home(CatalogFilter.ALL, allowAdult = false)
		assertEquals(
			listOf(CatalogSectionType.RISING, CatalogSectionType.HIDDEN_GEMS, CatalogSectionType.NEW_RELEASES),
			home.sections.map { it.key.type },
		)
		assertTrue(home.sections.all { it.key.kind == CatalogMediaKind.MIXED })
		assertTrue(home.sections.flatMap { it.items }.all { it.key.kind != CatalogMediaKind.MIXED })
		assertTrue(home.sections.flatMap { it.items }.any { it.providerType == "manhwa" })
		assertTrue(home.sections.flatMap { it.items }.any { it.key.kind == CatalogMediaKind.NOVEL })
	}

	@Test
	fun `Novel Home never sends unsupported type filter to rising or hidden gems`() = runBlocking {
		val provider = MangaBakaCatalogProvider(http { request ->
			when (request.url.encodedPath) {
				"/v2/series/discover/rising", "/v2/series/discover/hidden-gems" -> {
					assertNull(request.url.queryParameter("type"))
					assertNull(request.url.queryParameter("type_not"))
					assertEquals("24", request.url.queryParameter("limit"))
					"""{"data":[$manhwa,$novel],"pagination":{"next":false}}"""
				}
				"/v2/series/search" -> {
					assertEquals("novel", request.url.queryParameter("type"))
					assertEquals("latest", request.url.queryParameter("sort_by"))
					"""{"data":[$novel],"pagination":{"next":false}}"""
				}
				else -> error("Unexpected request ${request.url}")
			}
		})
		val home = provider.home(CatalogFilter.NOVEL, allowAdult = false)
		assertTrue(home.sections.all { it.key.kind == CatalogMediaKind.NOVEL })
		assertTrue(home.sections.all { section -> section.items.all { it.key.kind == CatalogMediaKind.NOVEL } })
		assertEquals(3, home.sections.count { it.items.singleOrNull()?.key?.remoteId == "703" })
	}

	@Test
	fun `mixed section paging preserves item identity`() = runBlocking {
		val provider = MangaBakaCatalogProvider(http { request ->
			assertEquals("/v2/series/discover/rising", request.url.encodedPath)
			assertEquals("2", request.url.queryParameter("page"))
			"""{"data":[$manga,$novel],"pagination":{"next":false}}"""
		})
		val page = provider.browseSection(
			CatalogSectionKey(ScrobblerService.MANGABAKA, CatalogMediaKind.MIXED, CatalogSectionType.RISING),
			allowAdult = false,
			page = 2,
		)
		assertEquals(listOf(CatalogMediaKind.MANGA, CatalogMediaKind.NOVEL), page.items.map { it.key.kind })
	}

	@Test
	fun `adult MangaBaka card is hidden when adult catalogue is disabled`() {
		val card = parseMangaBakaCatalogCard(
			json(
				"""
				{
				  "id": 7,
				  "type": "manga",
				  "content_rating": "erotica",
				  "title": "Adult title"
				}
				""",
			),
			allowAdult = false,
		)
		assertNull(card)
	}

	@Test
	fun `staff uses official author and artist names without fabricated portraits`() {
		val staff = mangaBakaCatalogStaff(
			json(
				"""
				{
				  "authors": ["Author One"],
				  "artists": ["Artist One"]
				}
				""",
			),
		)
		assertEquals(2, staff.size)
		assertEquals(listOf("Author"), staff.first { it.name == "Author One" }.roles)
		assertEquals(listOf("Artist"), staff.first { it.name == "Artist One" }.roles)
		assertTrue(staff.all { it.imageUrl == null })
		assertTrue(staff.all { it.providerUrl == null })
	}

	@Test
	fun `related work preserves MangaBaka relation and media kind`() {
		val relation = mangaBakaCatalogRelatedWork(
			series = json(
				"""
				{
				  "id": 99,
				  "type": "manga",
				  "content_rating": "safe",
				  "title": "Next Work",
				  "canonical_url": "https://mangabaka.org/99"
				}
				""",
			),
			rawRelation = "SEQUEL",
			allowAdult = false,
		)
		requireNotNull(relation)
		assertEquals("99", relation.remoteId)
		assertEquals(TrackerRelationType.SEQUEL, relation.relation)
		assertEquals(CatalogRelatedMediaKind.MANGA, relation.mediaKind)
		assertEquals("SEQUEL", relation.providerRelation)
		assertFalse(relation.title.isBlank())
	}

	@Test
	fun `pagination does not treat false next flag as another page`() {
		val pagination = json("""{"page":2,"pages":2,"next":false}""")
		assertFalse(mangaBakaHasNext(pagination, page = 2, rowCount = 20, limit = 20))
		assertTrue(mangaBakaHasNext(json("""{"page":1,"pages":2,"next":true}"""), page = 1, rowCount = 20, limit = 20))
	}

	private fun http(answer: (Request) -> String) = CatalogHttp(
		OkHttpClient.Builder().addInterceptor { chain ->
			Response.Builder()
				.request(chain.request())
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("fixture")
				.body(answer(chain.request()).toResponseBody("application/json".toMediaType()))
				.build()
		}.build(),
	)

	private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
