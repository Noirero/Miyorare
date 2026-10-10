package org.koitharu.kotatsu.scrobbling.mal.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class MALTrackerDetailsTest {
	private val target = TrackerTarget(ScrobblerService.MAL, "2")
	private fun root(body: String) = Json.parseToJsonElement(body).jsonObject

	@Test fun `authors retain roles and have no invented image`() {
		val result = malStaff(root("""{"id":2,"authors":[
			{"role":"Story","node":{"id":8,"first_name":"First","last_name":"Last"}},
			{"role":"Art","node":{"id":8,"first_name":"First","last_name":"Last"}},
			{"role":"Story","node":{"id":9,"first_name":null,"last_name":"Other"}}
		]}"""), target) as TrackerResult.Success
		assertEquals(2, result.items.size)
		assertEquals("First Last", result.items.first().name)
		assertEquals(listOf("Story", "Art"), result.items.first().roles)
		assertTrue(result.items.all { it.image == null })
	}

	@Test fun `recommendations retain MAL namespace and optional cover`() {
		val result = malRecommendations(root("""{"id":2,"recommendations":[{"node":{"id":7,"title":"R","main_picture":null},"num_recommendations":42}]}"""), target) as TrackerResult.Success
		assertEquals(TrackerTarget(ScrobblerService.MAL, "7", "https://myanimelist.net/manga/7"), result.items.single().target)
		assertNull(result.items.single().image)
		assertEquals(TrackerRecommendationKind.RECOMMENDATION, result.items.single().kind)
	}

	@Test fun `mal does not advertise characters`() {
		assertEquals(setOf(TrackerContent.STAFF, TrackerContent.RECOMMENDATIONS), MAL_DETAILS_CAPABILITIES)
		assertFalse(TrackerContent.CHARACTERS in MAL_DETAILS_CAPABILITIES)
	}

	@Test fun `mal empty and partial collections remain distinct`() {
		assertTrue(malStaff(root("""{"id":2,"authors":[]}"""), target) is TrackerResult.Empty)
		val result = malStaff(root("""{"id":2,"authors":[{}, {"role":"Art","node":{"first_name":"A"}}]}"""), target) as TrackerResult.Partial
		assertEquals("A", result.items.single().name)
		assertEquals(setOf(TrackerPartialReason.MISSING_RECORDS), result.reasons)
	}
}
