package org.koitharu.kotatsu.discovery.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
	fun `remote library row preserves provider card and tracking truth`() {
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

	private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
