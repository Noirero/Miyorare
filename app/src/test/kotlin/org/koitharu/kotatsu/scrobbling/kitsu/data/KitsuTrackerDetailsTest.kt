package org.koitharu.kotatsu.scrobbling.kitsu.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class KitsuTrackerDetailsTest {
	private val target = TrackerTarget(ScrobblerService.KITSU, "1")
	private fun root(body: String) = Json.parseToJsonElement(body).jsonObject

	@Test fun `mediaCharacters link only matching included character resources`() {
		val json = root("""{"data":[{"type":"mediaCharacters","attributes":{"role":"main"},"relationships":{"character":{"data":{"type":"characters","id":"8"}}}}],
			"included":[{"type":"people","id":"8","attributes":{"name":"Wrong"}},{"type":"characters","id":"8","attributes":{"canonicalName":"C","image":{"small":"https://img/c"}}}]}""")
		val result = kitsuPeople(json, target, TrackerContent.CHARACTERS, TrackerPage()) as TrackerResult.Success
		assertEquals("C", result.items.single().name)
		assertEquals("8", result.items.single().id)
		assertEquals(listOf("main"), result.items.single().roles)
		assertEquals("https://img/c", result.items.single().image)
	}

	@Test fun `mediaStaff aggregate roles from linked people without optional image`() {
		val json = root("""{"data":[
			{"type":"mediaStaff","attributes":{"role":"Story"},"relationships":{"person":{"data":{"type":"people","id":"8"}}}},
			{"type":"mediaStaff","attributes":{"role":"Art"},"relationships":{"person":{"data":{"type":"people","id":"8"}}}}
		],"included":[{"type":"people","id":"8","attributes":{"name":"S","image":null}}]}""")
		val result = kitsuPeople(json, target, TrackerContent.STAFF, TrackerPage()) as TrackerResult.Success
		assertEquals(listOf("Story", "Art"), result.items.single().roles)
		assertNull(result.items.single().image)
	}

	@Test fun `unresolved included linkage preserves resolved people as partial`() {
		val json = root("""{"data":[
			{"type":"mediaCharacters","relationships":{"character":{"data":{"type":"characters","id":"8"}}}},
			{"type":"mediaCharacters","relationships":{"character":{"data":{"type":"characters","id":"9"}}}}
		],"included":[{"type":"characters","id":"8","attributes":{"name":"C"}}]}""")
		val result = kitsuPeople(json, target, TrackerContent.CHARACTERS, TrackerPage()) as TrackerResult.Partial
		assertEquals("C", result.items.single().name)
		assertTrue(result.items.single().roles.isEmpty())
		assertEquals(setOf(TrackerPartialReason.MISSING_RECORDS), result.reasons)
	}

	@Test fun `next response link is retained and used without downloading all pages`() {
		val link = "https://kitsu.app/api/edge/manga/1/characters?page%5Boffset%5D=20&page%5Blimit%5D=20&include=character"
		val result = kitsuPeople(root("""{"data":[],"links":{"next":"$link"}}"""), target, TrackerContent.CHARACTERS, TrackerPage()) as TrackerResult.Empty
		assertEquals(TrackerPage(2, link), result.next)
		val url = kitsuDetailsUrl(target, TrackerContent.CHARACTERS, result.next!!)
		assertEquals("20", url.queryParameter("page[offset]"))
		assertEquals("20", url.queryParameter("page[limit]"))
	}

	@Test fun `unsafe next links are partial and cannot escape provider origin or target`() {
		for (link in listOf("https://evil.test/api/edge/manga/1/characters", "http://kitsu.app/api/edge/manga/1/characters", "https://kitsu.app/api/edge/manga/2/characters", "https://kitsu.app/api/edge/manga/1/characters?page%5Blimit%5D=200")) {
			val result = kitsuPeople(root("""{"data":[],"links":{"next":"$link"}}"""), target, TrackerContent.CHARACTERS, TrackerPage()) as TrackerResult.Partial
			assertNull(result.next)
			assertTrue(result.items.isEmpty())
		}
	}

	@Test fun `kitsu recommendations are unsupported and deprecated resources are not accepted`() {
		assertFalse(TrackerContent.RECOMMENDATIONS in KITSU_DETAILS_CAPABILITIES)
		assertSame(TrackerResult.Unsupported, kitsuPeople(root("""{"data":[]}"""), target, TrackerContent.RECOMMENDATIONS, TrackerPage()))
		val result = kitsuPeople(root("""{"data":[{"type":"mangaCharacters"}]}"""), target, TrackerContent.CHARACTERS, TrackerPage())
		assertTrue(result is TrackerResult.Partial)
	}
}
