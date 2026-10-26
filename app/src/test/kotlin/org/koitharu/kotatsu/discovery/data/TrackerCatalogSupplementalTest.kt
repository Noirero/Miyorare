package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType

class TrackerCatalogSupplementalTest {
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

	@Test
	fun `AniList Tracker Home keeps character staff portraits and semantic related works`() = runBlocking {
		val provider = AniListCatalogProvider(http { request ->
			val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
			assertTrue(body.contains("characters(perPage:12"))
			assertTrue(body.contains("staff(perPage:12"))
			assertTrue(body.contains("relations{edges"))
			assertTrue(body.contains("image{large medium}"))
			"""{
				"data":{"Media":{
					"id":42,"type":"MANGA","format":"MANGA","isAdult":false,
					"title":{"userPreferred":"Work"},
					"characters":{"edges":[{"role":"MAIN","node":{"id":1,"name":{"userPreferred":"Hero"},"image":{"large":"https://img.example/hero.jpg"},"siteUrl":"https://anilist.co/character/1"}}]},
					"staff":{"edges":[{"role":"Story & Art","node":{"id":2,"name":{"full":"Creator"},"image":{"medium":"https://img.example/creator.jpg"},"siteUrl":"https://anilist.co/staff/2"}}]},
					"relations":{"edges":[{"relationType":"ADAPTATION","node":{"id":9,"type":"ANIME","format":"TV","isAdult":false,"title":{"romaji":"Anime"},"coverImage":{"large":"https://img.example/anime.jpg"},"siteUrl":"https://anilist.co/anime/9"}}]},
					"recommendations":{"nodes":[]}
				}}
			} """
		})
		val details = provider.details(TrackerCatalogKey(ScrobblerService.ANILIST, CatalogMediaKind.MANGA, "42"), false)
		assertEquals("https://img.example/hero.jpg", details.characters.single().imageUrl)
		assertEquals("MAIN", details.characters.single().roles.single())
		assertEquals("https://img.example/creator.jpg", details.staffPeople.single().imageUrl)
		assertEquals(TrackerRelationType.ADAPTATION, details.relatedWorks.single().relation)
		assertEquals(CatalogRelatedMediaKind.ANIME, details.relatedWorks.single().mediaKind)
	}

	@Test
	fun `Kitsu Tracker Home keeps public character staff portraits and related novel`() = runBlocking {
		val provider = KitsuCatalogProvider(http { request ->
			when {
				request.url.encodedPath.endsWith("/characters") -> """{
					"data":[{"type":"mediaCharacters","id":"501","attributes":{"role":"main"},"relationships":{"character":{"data":{"type":"characters","id":"31"}}}}],
					"included":[{"type":"characters","id":"31","attributes":{"canonicalName":"Hero","image":{"large":"https://img.example/kitsu-hero.jpg"}}}]
				}"""
				request.url.encodedPath.endsWith("/staff") -> """{
					"data":[{"type":"mediaStaff","id":"601","attributes":{"role":"Story"},"relationships":{"person":{"data":{"type":"people","id":"41"}}}}],
					"included":[{"type":"people","id":"41","attributes":{"canonicalName":"Creator","image":{"medium":"https://img.example/kitsu-creator.jpg"}}}]
				}"""
				request.url.encodedPath == "/api/edge/media-relationships" -> """{
					"data":[{"type":"mediaRelationships","id":"701","attributes":{"role":"sequel"},"relationships":{"destination":{"data":{"type":"manga","id":"22"}}}}],
					"included":[{"type":"manga","id":"22","attributes":{"canonicalTitle":"Next Novel","subtype":"novel","ageRating":"PG","slug":"next-novel","posterImage":{"large":"https://img.example/next.jpg"}}}]
				}"""
				else -> """{
					"data":{"id":"21","type":"manga","attributes":{"canonicalTitle":"Work","subtype":"manga","ageRating":"PG"}},
					"included":[]
				}"""
			}
		})
		val details = provider.details(TrackerCatalogKey(ScrobblerService.KITSU, CatalogMediaKind.MANGA, "21"), false)
		assertEquals("https://img.example/kitsu-hero.jpg", details.characters.single().imageUrl)
		assertEquals("https://img.example/kitsu-creator.jpg", details.staffPeople.single().imageUrl)
		assertEquals(CatalogRelatedMediaKind.NOVEL, details.relatedWorks.single().mediaKind)
		assertEquals(TrackerRelationType.SEQUEL, details.relatedWorks.single().relation)
		assertEquals("https://img.example/next.jpg", details.relatedWorks.single().coverUrl)
	}

	@Test
	fun `MAL Tracker Home exposes verified authors and related works without fabricated portrait`() = runBlocking {
		val provider = MALCatalogProvider(http { request ->
			val fields = request.url.queryParameter("fields").orEmpty()
			assertTrue(fields.contains("related_manga"))
			assertTrue(fields.contains("related_anime"))
			"""{
				"id":13,"title":"Work","media_type":"manga","nsfw":"white",
				"authors":[{"node":{"id":5,"first_name":"Author","last_name":"Name"},"role":"Story"}],
				"recommendations":[],
				"related_manga":[{"node":{"id":14,"title":"Origin","media_type":"light_novel","nsfw":"white","main_picture":{"medium":"https://img.example/origin.jpg"}},"relation_type":"prequel","relation_type_formatted":"Prequel"}],
				"related_anime":[{"node":{"id":15,"title":"Anime","nsfw":"white","main_picture":{"medium":"https://img.example/anime.jpg"}},"relation_type":"adaptation","relation_type_formatted":"Adaptation"}]
			} """
		}, "fixture-public-id")
		val details = provider.details(TrackerCatalogKey(ScrobblerService.MAL, CatalogMediaKind.MANGA, "13"), false)
		assertEquals("Author Name", details.staffPeople.single().name)
		assertNull(details.staffPeople.single().imageUrl)
		assertEquals(2, details.relatedWorks.size)
		assertEquals(CatalogRelatedMediaKind.NOVEL, details.relatedWorks[0].mediaKind)
		assertEquals(CatalogRelatedMediaKind.ANIME, details.relatedWorks[1].mediaKind)
	}
}
