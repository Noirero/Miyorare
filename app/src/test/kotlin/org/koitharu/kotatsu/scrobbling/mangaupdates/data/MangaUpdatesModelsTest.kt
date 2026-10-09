package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException

class MangaUpdatesModelsTest {
	private fun root(value: String) = Json.parseToJsonElement(value).jsonObject
	private val target = TrackerTarget(ScrobblerService.MANGAUPDATES, "17360452316")

	@Test fun `recommendations preserve full provider identity title cover and provider page`() {
		val value = root("""{"series_id":17360452316,"recommendations":[{"series_id":70994361491,"series_name":"Bleach","series_url":"https://www.mangaupdates.com/series/wm47jwz/bleach","series_image":{"url":{"original":"https://cdn.mangaupdates.com/cover.jpg"}}}]}""")
		val item = (mangaUpdatesRecommendations(value, target) as TrackerResult.Success).items.single()
		assertEquals(ScrobblerService.MANGAUPDATES, item.target.service)
		assertEquals("70994361491", item.target.id)
		assertEquals("Bleach", item.title)
		assertEquals("https://cdn.mangaupdates.com/cover.jpg", item.image)
		assertEquals("https://www.mangaupdates.com/series/wm47jwz/bleach", item.target.url)
	}

	@Test fun `unrelated category recommendations are not mislabeled as user recommendations`() {
		assertTrue(mangaUpdatesRecommendations(root("""{"series_id":17360452316,"recommendations":[],"category_recommendations":[{"series_id":7,"series_name":"Category match"}]}"""), target) is TrackerResult.Empty)
		assertTrue(mangaUpdatesRecommendations(root("""{"series_id":2,"recommendations":[]}"""), target) is TrackerResult.Error)
	}

	@Test fun `author and artist roles merge by full author identity with provenance`() {
		val value = root("""{"series_id":17360452316,"authors":[{"author_id":9545965743,"name":"Creator","type":"Author","url":"https://www.mangaupdates.com/author/4dvf6b3/creator"},{"author_id":9545965743,"name":"Creator","type":"Artist"},{"name":"Creator","type":"Author"}]}""")
		val staff = (mangaUpdatesStaff(value, target) as TrackerResult.Success).items
		assertEquals(2, staff.size)
		assertEquals("9545965743", staff.first().id)
		assertEquals(listOf("Author", "Artist"), staff.first().roles)
		assertNull(staff.first().image)
		assertNotNull(staff.first().url)
		assertNull(staff.last().id)
	}

	@Test fun `list IDs are discovered by type and custom lists remain distinct`() {
		val lists = mangaUpdatesLists(Json.parseToJsonElement("""[{"list_id":107,"type":"read","custom":false},{"list_id":809,"type":"wish","custom":false},{"list_id":3000,"type":"read","custom":true}]"""))
		assertEquals(listOf(107L, 809L, 3000L), lists.map { it.id })
		assertTrue(lists.last().custom)
	}

	@Test fun `absolute progress payload preserves volume priority and full series identity`() {
		val state = mangaUpdatesRemoteState(root("""{"series":{"id":17360452316},"list_id":3000,"list_type":"read","status":{"chapter":17,"volume":4},"priority":2}"""), 17360452316L)
		val body = state.copy(chapter = 20).writeBody().single().jsonObject
		assertEquals(17360452316L, body.getValue("series").jsonObject.getValue("id").jsonPrimitive.long)
		assertEquals(3000L, body.getValue("list_id").jsonPrimitive.long)
		assertEquals(2, body.getValue("priority").jsonPrimitive.int)
		assertEquals(setOf("chapter", "volume"), body.getValue("status").jsonObject.keys)
		assertEquals(4, body.getValue("status").jsonObject.getValue("volume").jsonPrimitive.int)
		assertEquals(20, body.getValue("status").jsonObject.getValue("chapter").jsonPrimitive.int)
	}

	@Test fun `wrong identity and overflowing progress reject unsafe remote state`() {
		val value = root("""{"series":{"id":17360452316},"list_id":0,"list_type":"read","status":{"chapter":2147483648,"volume":4},"priority":2}""")
		assertThrows(IOException::class.java) { mangaUpdatesRemoteState(value, 17360452316L) }
		assertThrows(IOException::class.java) { mangaUpdatesRemoteState(value, 4L) }
	}

	@Test fun `ratings use the provider ten point scale with explicit zero removal`() {
		assertEquals(0.8f, mangaUpdatesRating(root("""{"rating":8,"last_updated":{"timestamp":1}}""")), 0f)
		assertEquals(8, mangaUpdatesRatingValue(0.8f))
		assertEquals(10, mangaUpdatesRatingValue(1f))
		assertEquals(0, mangaUpdatesRatingValue(0f))
		assertThrows(IOException::class.java) { mangaUpdatesRating(root("""{"rating":11}""")) }
		assertThrows(IOException::class.java) { mangaUpdatesRating(root("""{"score":8}""")) }
		assertThrows(IllegalArgumentException::class.java) { mangaUpdatesRatingValue(Float.NaN) }
	}

	@Test fun `only reusable session fields are accepted and malformed tokens fail closed`() {
		assertEquals("fixture-session", mangaUpdatesLoginToken(root("""{"status":"success","context":{"session_token":"fixture-session"}}""")))
		assertEquals("fixture-session", mangaUpdatesLoginToken(root("""{"session_token":"fixture-session"}""")))
		assertThrows(IOException::class.java) { mangaUpdatesLoginToken(root("""{"status":"success","context":{"user_id":1}}""")) }
		assertThrows(IllegalArgumentException::class.java) { mangaUpdatesLoginToken(root("""{"session_token":"fixture\nsecret"}""")) }
		assertThrows(IllegalArgumentException::class.java) { mangaUpdatesLoginToken(root("""{"session_token":" fixture "}""")) }
	}
}
