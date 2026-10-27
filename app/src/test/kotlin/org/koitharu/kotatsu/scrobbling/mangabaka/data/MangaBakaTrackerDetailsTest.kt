package org.koitharu.kotatsu.scrobbling.mangabaka.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerMediaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerResult
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerTarget

class MangaBakaTrackerDetailsTest {
	private fun root(body: String) = Json.parseToJsonElement(body).jsonObject
	private val target = TrackerTarget(ScrobblerService.MANGABAKA, "42")

	@Test fun `staff uses official author and artist names without fabricated portraits`() {
		val result = mangaBakaStaff(
			root("""
				{"data":{"id":42,"authors":["Author A"],"artists":["Artist B"]}}
			""".trimIndent()),
			target,
		) as TrackerResult.Success

		assertEquals(listOf("Author A", "Artist B"), result.items.map { it.name })
		assertEquals(listOf("Author", "Artist"), result.items.flatMap { it.roles })
		assertNull(result.items[0].image)
		assertNull(result.items[0].url)
	}

	@Test fun `readers also like preserves provider target title cover and url`() {
		val result = mangaBakaRecommendations(
			root("""
				{"data":[{"series":{"id":7,"titles":[{"title":"Next Story","language":"en","is_primary":true}],"cover":{"x350":"https://img.invalid/next"},"canonical_url":"https://mangabaka.org/manga/7/next-story"},"rank":1,"score":0.9,"shared_users":4}]}
			""".trimIndent()),
			target,
		) as TrackerResult.Success

		val item = result.items.single()
		assertEquals("7", item.target.id)
		assertEquals("Next Story", item.title)
		assertEquals("https://img.invalid/next", item.image)
		assertEquals("https://mangabaka.org/manga/7/next-story", item.target.url)
	}

	@Test fun `relations resolve batch destination without fabricating anime`() {
		val details = root("""
			{"data":{"id":42,"relationships":[{"to_series_id":7,"relation_type":"sequel","chronology":"after"}]}}
		""".trimIndent())
		val related = root("""
			{"data":[{"id":7,"type":"novel","titles":[{"title":"Next Novel","language":"en","is_primary":true}],"cover":{"x250":"https://img.invalid/novel"},"canonical_url":"https://mangabaka.org/manga/7/next-novel"}]}
		""".trimIndent())

		assertEquals(listOf("7"), mangaBakaRelationTargetIds(details, target))
		val result = mangaBakaRelations(details, related, target) as TrackerResult.Success
		val item = result.items.single()
		assertEquals(TrackerRelationType.SEQUEL, item.relation)
		assertEquals(TrackerMediaType.MANGA, item.mediaType)
		assertEquals("novel", item.format)
		assertEquals("Next Novel", item.title)
		assertEquals("https://mangabaka.org/manga/7/next-novel", item.target.url)
	}
}
