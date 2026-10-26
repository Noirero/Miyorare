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
import org.koitharu.kotatsu.discovery.domain.MangaBakaExploreType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus

class MangaBakaPersonalHomeTest {
	@Test
	fun `remote library row preserves provider card type and tracking truth`() {
		val item = parseMangaBakaLibraryItem(
			json(
				"""
				{
				  "series": {
				    "id": 101,
				    "type": "manhwa",
				    "content_rating": "safe",
				    "title": "Library title",
				    "canonical_url": "https://mangabaka.org/101",
				    "cover": {"x350":"https://img.example/101.jpg"}
				  },
				  "entry": {
				    "id": 9001,
				    "series_id": 101,
				    "state": "reading",
				    "progress_chapter": 18.0,
				    "rating": 82
				  }
				}
				""",
			),
			CatalogFilter.ALL,
			allowAdult = false,
		)
		requireNotNull(item)
		assertEquals("101", item.item.key.remoteId)
		assertEquals(CatalogMediaKind.MANGA, item.item.key.kind)
		assertEquals("manhwa", item.item.providerType)
		assertEquals("https://img.example/101.jpg", item.item.coverUrl)
		assertEquals(ScrobblingStatus.READING, item.tracking.status)
		assertEquals(18, item.tracking.chapter)
		assertEquals(0.82f, item.tracking.rating, 0.001f)
		assertEquals(101L, item.tracking.entryId)
	}

	@Test
	fun `remote library respects global kind filter`() {
		val novel = json(
			"""
			{
			  "series": {"id":202,"type":"novel","content_rating":"safe","title":"Novel"},
			  "entry": {"series_id":202,"state":"plan_to_read"}
			}
			""",
		)
		assertNull(parseMangaBakaLibraryItem(novel, CatalogFilter.MANGA, allowAdult = false))
		assertEquals(
			CatalogMediaKind.NOVEL,
			parseMangaBakaLibraryItem(novel, CatalogFilter.NOVEL, allowAdult = false)?.item?.key?.kind,
		)
	}

	@Test
	fun `remote adult library item does not leak into safe Home`() {
		val adult = json(
			"""
			{
			  "series": {"id":303,"type":"manga","content_rating":"erotica","title":"Hidden","cover":{"x350":"https://img.example/adult.jpg"}},
			  "entry": {"series_id":303,"state":"reading"}
			}
			""",
		)
		assertNull(parseMangaBakaLibraryItem(adult, CatalogFilter.ALL, allowAdult = false))
		assertEquals("303", parseMangaBakaLibraryItem(adult, CatalogFilter.ALL, allowAdult = true)?.item?.key?.remoteId)
	}

	@Test
	fun `personal recommendation accepts nested MangaBaka series`() {
		val card = parseMangaBakaPersonalCard(
			json(
				"""
				{
				  "score": 0.91,
				  "series": {
				    "id": 404,
				    "type": "manga",
				    "content_rating": "safe",
				    "title": "Recommended title"
				  }
				}
				""",
			),
			allowAdult = false,
		)
		requireNotNull(card)
		assertEquals("404", card.key.remoteId)
		assertEquals("Recommended title", card.title)
		assertEquals("manga", card.providerType)
	}

	@Test
	fun `Explore uses public Search exact provider type and rejects mismatched rows`() = runBlocking {
		val public = CatalogHttp(client { request ->
			assertEquals("/v2/series/search", request.url.encodedPath)
			assertEquals("novel", request.url.queryParameter("type"))
			assertEquals("popularity_asc", request.url.queryParameter("sort_by"))
			assertNull(request.header("Authorization"))
			"""{"data":[
				{"id":501,"type":"novel","content_rating":"safe","title":"Right novel"},
				{"id":502,"type":"manhwa","content_rating":"safe","title":"Wrong type"}
			]}"""
		})
		val repository = MangaBakaPersonalHomeRepository(OkHttpClient(), public)
		val result = repository.explore(MangaBakaExploreType.NOVEL, allowAdult = false)
		assertEquals(listOf("501"), result.map { it.key.remoteId })
		assertTrue(result.all { it.providerType == "novel" && it.key.kind == CatalogMediaKind.NOVEL })
	}

	@Test
	fun `For You sends official Novel type filter before defensive filtering`() {
		val url = mangaBakaRecommendationsUrl(CatalogFilter.NOVEL, allowAdult = false)
		assertEquals("/v1/my/series/recommendations", url.encodedPath)
		assertEquals("novel", url.queryParameter("type"))
		assertNull(url.queryParameter("type_not"))
		assertEquals(listOf("safe", "suggestive"), url.queryParameterValues("content_rating"))
	}

	@Test
	fun `explore types follow the global Manga Novel filter without flattening MangaBaka taxonomy`() {
		assertTrue(MangaBakaExploreType.entries.all { it.isVisibleFor(CatalogFilter.ALL) })
		assertEquals(listOf(MangaBakaExploreType.NOVEL), MangaBakaExploreType.entries.filter { it.isVisibleFor(CatalogFilter.NOVEL) })
		val mangaTypes = MangaBakaExploreType.entries.filter { it.isVisibleFor(CatalogFilter.MANGA) }
		assertFalse(MangaBakaExploreType.NOVEL in mangaTypes)
		assertTrue(MangaBakaExploreType.MANHWA in mangaTypes)
		assertTrue(MangaBakaExploreType.MANHUA in mangaTypes)
		assertTrue(MangaBakaExploreType.OEL in mangaTypes)
		assertTrue(MangaBakaExploreType.OTHER in mangaTypes)
	}

	private fun client(answer: (Request) -> String): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
		Response.Builder()
			.request(chain.request())
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("fixture")
			.body(answer(chain.request()).toResponseBody("application/json".toMediaType()))
			.build()
	}.build()

	private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
