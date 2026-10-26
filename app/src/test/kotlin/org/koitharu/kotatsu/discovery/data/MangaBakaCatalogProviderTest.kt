package org.koitharu.kotatsu.discovery.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType

class MangaBakaCatalogProviderTest {
	@Test
	fun `public card keeps provider identity cover score and novel kind`() {
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
		assertEquals("English title", card.title)
		assertEquals("https://img.example/42.jpg", card.coverUrl)
		assertEquals("https://mangabaka.org/42", card.providerUrl)
		assertEquals(8.75, card.score ?: 0.0, 0.001)
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

	private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
