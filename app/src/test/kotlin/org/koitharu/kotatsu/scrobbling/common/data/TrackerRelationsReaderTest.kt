package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerMediaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerResult
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerTarget

class TrackerRelationsReaderTest {
	private fun root(body: String) = Json.parseToJsonElement(body).jsonObject

	@Test fun `AniList preserves semantic relation media kind format cover and provider url`() {
		val result = parseAniListRelations(
			root("""
				{
				  "data": {"Media": {"id": 2, "relations": {"edges": [
				    {"relationType":"PREQUEL","node":{"id":1,"type":"MANGA","format":"NOVEL","title":{"userPreferred":"Origin"},"coverImage":{"large":"https://img.invalid/origin"},"siteUrl":"https://anilist.co/manga/1"}},
				    {"relationType":"ADAPTATION","node":{"id":3,"type":"ANIME","format":"TV","title":{"romaji":"Animated"},"coverImage":{"medium":"https://img.invalid/anime"},"siteUrl":"https://anilist.co/anime/3"}}
				  ]}}}
				}
			""".trimIndent()),
			TrackerTarget(ScrobblerService.ANILIST, "2"),
		) as TrackerResult.Success

		assertEquals(2, result.items.size)
		assertEquals(TrackerRelationType.PREQUEL, result.items[0].relation)
		assertEquals(TrackerMediaType.MANGA, result.items[0].mediaType)
		assertEquals("NOVEL", result.items[0].format)
		assertEquals("https://img.invalid/origin", result.items[0].image)
		assertEquals("https://anilist.co/manga/1", result.items[0].target.url)
		assertEquals(TrackerRelationType.ADAPTATION, result.items[1].relation)
		assertEquals(TrackerMediaType.ANIME, result.items[1].mediaType)
		assertEquals("TV", result.items[1].format)
	}

	@Test fun `MAL keeps manga and anime relations distinct`() {
		val result = parseMalRelations(
			root("""
				{
				  "id":2,
				  "related_manga":[{"node":{"id":7,"title":"Side Story","main_picture":{"medium":"https://img.invalid/manga"}},"relation_type":"side_story","relation_type_formatted":"Side Story"}],
				  "related_anime":[{"node":{"id":9,"title":"Anime","main_picture":{"large":"https://img.invalid/anime"}},"relation_type":"adaptation","relation_type_formatted":"Adaptation"}]
				}
			""".trimIndent()),
			TrackerTarget(ScrobblerService.MAL, "2"),
		) as TrackerResult.Success

		assertEquals(2, result.items.size)
		assertEquals(TrackerMediaType.MANGA, result.items[0].mediaType)
		assertEquals(TrackerRelationType.SIDE_STORY, result.items[0].relation)
		assertEquals("https://myanimelist.net/manga/7", result.items[0].target.url)
		assertEquals(TrackerMediaType.ANIME, result.items[1].mediaType)
		assertEquals(TrackerRelationType.ADAPTATION, result.items[1].relation)
		assertEquals("https://myanimelist.net/anime/9", result.items[1].target.url)
	}

	@Test fun `Kitsu relationship resolves included destination without synthesizing local manga`() {
		val result = parseKitsuRelations(
			root("""
				{
				  "data":[{
				    "type":"mediaRelationships",
				    "id":"55",
				    "attributes":{"role":"sequel"},
				    "relationships":{"destination":{"data":{"type":"manga","id":"7"}}}
				  }],
				  "included":[{
				    "type":"manga",
				    "id":"7",
				    "attributes":{"canonicalTitle":"Next Story","subtype":"manga","slug":"next-story","posterImage":{"small":"https://img.invalid/next"}}
				  }]
				}
			""".trimIndent()),
			TrackerTarget(ScrobblerService.KITSU, "2"),
		) as TrackerResult.Success

		val item = result.items.single()
		assertEquals("Next Story", item.title)
		assertEquals(TrackerRelationType.SEQUEL, item.relation)
		assertEquals(TrackerMediaType.MANGA, item.mediaType)
		assertEquals("manga", item.format)
		assertEquals("https://kitsu.app/manga/next-story", item.target.url)
	}

	@Test fun `relation aliases normalize and unknown relation remains explicit other`() {
		assertEquals(TrackerRelationType.ALTERNATIVE, trackerRelationType("alternative version"))
		assertEquals(TrackerRelationType.SPIN_OFF, trackerRelationType("spinoff"))
		assertEquals(TrackerRelationType.PARENT, trackerRelationType("parent_story"))
		assertEquals(TrackerRelationType.SAME_UNIVERSE, trackerRelationType("same-universe"))
		assertEquals(TrackerRelationType.OTHER, trackerRelationType("shared cast"))
		assertEquals(TrackerRelationType.OTHER, trackerRelationType(null))
	}

	@Test fun `wrong AniList media identity fails closed`() {
		val result = parseAniListRelations(
			root("""{"data":{"Media":{"id":3,"relations":{"edges":[]}}}}"""),
			TrackerTarget(ScrobblerService.ANILIST, "2"),
		)
		assertTrue(result is TrackerResult.Error)
		assertNull((result as? TrackerResult.Success)?.next)
	}
}
