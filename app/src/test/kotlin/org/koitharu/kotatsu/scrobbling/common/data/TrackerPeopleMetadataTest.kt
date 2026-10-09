package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.anilist.data.aniListPeople
import org.koitharu.kotatsu.scrobbling.kitsu.data.kitsuPeople
import org.koitharu.kotatsu.scrobbling.shikimori.data.shikimoriPeople
import org.koitharu.kotatsu.scrobbling.mal.data.malStaff
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class TrackerPeopleMetadataTest {
	private fun root(value: String) = Json.parseToJsonElement(value).jsonObject
	@Test fun `AniList keeps preferred names large portraits roles and person page`() {
		val result = aniListPeople(root("""{"data":{"Media":{"id":2,"characters":{"edges":[{"role":"MAIN","node":{"id":7,"name":{"full":"Full name","userPreferred":"Preferred name"},"image":{"large":"https://image.invalid/large","medium":"https://image.invalid/medium"},"siteUrl":"https://anilist.co/character/7"}}]}}}}"""), TrackerTarget(ScrobblerService.ANILIST, "2"), TrackerContent.CHARACTERS, TrackerPage()) as TrackerResult.Success
		assertEquals("Preferred name", result.items.single().name)
		assertEquals("https://image.invalid/large", result.items.single().image)
		assertEquals("https://anilist.co/character/7", result.items.single().url)
		assertEquals(listOf("MAIN"), result.items.single().roles)
	}
	@Test fun `Kitsu name and portrait fallbacks retain relationship identity`() {
		val result = kitsuPeople(root("""{"data":[{"type":"mediaStaff","relationships":{"person":{"data":{"type":"people","id":"7"}}},"attributes":{"role":"Story"}}],"included":[{"type":"people","id":"7","links":{"self":"https://kitsu.app/api/edge/people/7"},"attributes":{"names":{"en":"Creator"},"image":{"original":"https://image.invalid/original"}}}]}"""), TrackerTarget(ScrobblerService.KITSU, "2"), TrackerContent.STAFF, TrackerPage()) as TrackerResult.Success
		assertEquals("7", result.items.single().id)
		assertEquals("Creator", result.items.single().name)
		assertEquals("https://image.invalid/original", result.items.single().image)
		assertEquals(listOf("Story"), result.items.single().roles)
		assertEquals("https://kitsu.app/api/edge/people/7", result.items.single().url)
	}
	@Test fun `Shikimori preserves public person page and MAL preserves the actual author role`() {
		val shiki = shikimoriPeople(root("""{"data":{"mangas":[{"id":"2","personRoles":[{"rolesEn":["Story"],"person":{"id":"7","name":"Creator","url":"/people/7","poster":null}}]}]}}"""), TrackerTarget(ScrobblerService.SHIKIMORI, "2"), TrackerContent.STAFF) as TrackerResult.Success
		assertEquals("https://shikimori.io/people/7", shiki.items.single().url)
		val mal = malStaff(root("""{"id":2,"authors":[{"node":{"id":7,"first_name":"First","last_name":"Last"},"role":"Story & Art"}]}"""), TrackerTarget(ScrobblerService.MAL, "2")) as TrackerResult.Success
		assertEquals(listOf("Story & Art"), mal.items.single().roles)
		assertEquals("https://myanimelist.net/people/7", mal.items.single().url)
		assertNull(mal.items.single().image)
	}
}
