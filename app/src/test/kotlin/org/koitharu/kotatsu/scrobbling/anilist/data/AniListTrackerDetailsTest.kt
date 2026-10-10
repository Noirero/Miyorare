package org.koitharu.kotatsu.scrobbling.anilist.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class AniListTrackerDetailsTest {
	private val target = TrackerTarget(ScrobblerService.ANILIST, "2")
	private val page = TrackerPage()
	private fun root(body: String) = Json.parseToJsonElement(body).jsonObject

	@Test fun `characters retain role and a null image without fabrication`() {
		val json = root("""{"data":{"Media":{"id":2,"characters":{"pageInfo":{"hasNextPage":true},"edges":[{"role":"MAIN","node":{"id":42,"name":{"full":"A"},"image":null}}]}}}}""")
		val result = aniListPeople(json, target, TrackerContent.CHARACTERS, page) as TrackerResult.Success
		assertEquals("42", result.items.single().id)
		assertEquals(listOf("MAIN"), result.items.single().roles)
		assertNull(result.items.single().image)
		assertEquals(TrackerPage(2), result.next)
	}

	@Test fun `staff roles aggregate by supplied person id only`() {
		val json = root("""{"data":{"Media":{"id":2,"staff":{"edges":[
			{"role":"Story","node":{"id":3,"name":{"full":"A"},"image":{"medium":"https://img/a"}}},
			{"role":"Art","node":{"id":3,"name":{"full":"A"}}},
			{"role":"Story","node":{"id":4,"name":{"full":"A"}}},
			{"role":"Assistant","node":{"name":{"full":"A"}}}
		]}}}}""")
		val result = aniListPeople(json, target, TrackerContent.STAFF, page) as TrackerResult.Success
		assertEquals(3, result.items.size)
		assertEquals(listOf("Story", "Art"), result.items.first().roles)
		assertEquals("https://img/a", result.items.first().image)
	}

	@Test fun `GraphQL partial data survives errors and missing nodes`() {
		val json = root("""{"errors":[{"message":"field failed"}],"data":{"Media":{"id":2,"characters":{"edges":[null,{"role":"SUPPORTING","node":{"name":{"full":"B"}}}]}}}}""")
		val result = aniListPeople(json, target, TrackerContent.CHARACTERS, page) as TrackerResult.Partial
		assertEquals("B", result.items.single().name)
		assertEquals(setOf(TrackerPartialReason.PROVIDER_ERROR, TrackerPartialReason.MISSING_RECORDS), result.reasons)
	}

	@Test fun `recommendations use media identity rather than recommendation edge id`() {
		val json = root("""{"data":{"Media":{"id":2,"recommendations":{"edges":[{"node":{"id":999,"mediaRecommendation":{"id":7,"title":{"userPreferred":null,"romaji":"Title"},"coverImage":null,"siteUrl":"https://anilist.co/manga/7"}}}]}}}}""")
		val result = aniListRecommendations(json, target, page) as TrackerResult.Success
		assertEquals(TrackerTarget(ScrobblerService.ANILIST, "7", "https://anilist.co/manga/7"), result.items.single().target)
		assertEquals("Title", result.items.single().title)
		assertNull(result.items.single().image)
		assertEquals(TrackerRecommendationKind.RECOMMENDATION, result.items.single().kind)
	}

	@Test fun `empty differs from missing or wrong media`() {
		assertTrue(aniListPeople(root("""{"data":{"Media":{"id":2,"staff":{"edges":[]}}}}"""), target, TrackerContent.STAFF, page) is TrackerResult.Empty)
		assertTrue(aniListPeople(root("""{"data":{"Media":{"id":3,"staff":{"edges":[]}}}}"""), target, TrackerContent.STAFF, page) is TrackerResult.Error)
		assertTrue(aniListPeople(root("""{"data":{"Media":null}}"""), target, TrackerContent.STAFF, page) is TrackerResult.Error)
	}

	@Test fun `optional query selects only requested content and bounds page`() {
		val query = aniListDetailsQuery(target, TrackerContent.CHARACTERS, TrackerPage(2))
		assertTrue(query.contains("page: 2, perPage: 25"))
		assertTrue(query.contains("type: MANGA"))
		assertFalse(query.contains("staff"))
		assertFalse(query.contains("recommendations"))
	}
}
