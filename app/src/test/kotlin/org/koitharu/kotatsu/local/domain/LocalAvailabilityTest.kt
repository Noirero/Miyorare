package org.koitharu.kotatsu.local.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.parsers.model.Manga

class LocalAvailabilityTest {
	@Test
	fun `normalized matching accepts case and repeated whitespace but ignores empty names`() {
		val keys = listOf(manga(1L, "  SOLO\t Leveling  ").copy(altTitles = setOf(" ", "Only I Level Up"))).localTitleKeys()
		assertEquals(setOf("solo leveling", "only i level up"), keys)
		assertTrue((grid(manga(2L, "solo  leveling")).withLocalAvailability(keys) as MangaGridModel).isAvailableInLocal)
		assertFalse((grid(manga(3L, "Solo Leveling II")).withLocalAvailability(keys) as MangaGridModel).isAvailableInLocal)
	}

	@Test
	fun `a title hint preserves source identity membership download language and progress fields`() {
		val source = grid(manga(2L, "Solo Leveling")).copy(isFavorite = false, isSaved = true, languageLabel = "EN")
		val hinted = source.withLocalAvailability(setOf("solo leveling")) as MangaGridModel
		assertEquals(source.copy(isAvailableInLocal = true), hinted)
		assertEquals(source.manga, hinted.manga)
		assertFalse(hinted.isFavorite)
		assertTrue(hinted.isSaved)
		assertEquals("EN", hinted.languageLabel)
	}

	@Test
	fun `alternate titles match and stale hints are removed on snapshot updates`() {
		val source = grid(manga(2L, "Other title").copy(altTitles = setOf("Solo Leveling")))
		val hinted = source.withLocalAvailability(setOf("solo leveling")) as MangaGridModel
		assertTrue(hinted.isAvailableInLocal)
		assertFalse((hinted.withLocalAvailability(emptySet()) as MangaGridModel).isAvailableInLocal)
	}

	@Test
	fun `all list modes show a hint without merging a local record`() {
		val remote = manga(2L, "Solo Leveling")
		val local = manga(1L, "Solo Leveling").copy(source = LocalMangaSource)
		val keys = listOf(local).localTitleKeys()
		assertTrue((MangaCompactListModel(remote, null, "tags", 0).withLocalAvailability(keys) as MangaCompactListModel).isAvailableInLocal)
		assertTrue((MangaDetailedListModel(remote, null, null, 0, null, false, false, emptyList()).withLocalAvailability(keys) as MangaDetailedListModel).isAvailableInLocal)
		assertFalse((grid(local).withLocalAvailability(keys) as MangaGridModel).isAvailableInLocal)
		assertEquals(2L, (grid(remote).withLocalAvailability(keys) as MangaGridModel).id)
	}

	private fun grid(manga: Manga) = MangaGridModel(manga, null, 0, null, false, false, false, false, false)

	private fun manga(id: Long, title: String) = Manga(
		id = id, title = title, altTitles = emptySet(), url = "/manga/$id", publicUrl = "https://fixture.invalid/$id",
		rating = -1f, contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
		largeCoverUrl = null, description = null, source = MissingMangaSource("FIXTURE"), chapters = null,
	)
}
