package org.koitharu.kotatsu.alternatives.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationDirectSearchQueryTest {
	@Test
	fun `primary query strips leading group and trailing edition decorators`() {
		val seed = Manga(
			id = 1L,
			title = "(Arai Kei) Middle-Aged Miss Hanada's Defloration [Uncensored]",
			altTitles = emptySet(),
			url = "/1",
			publicUrl = "https://example.invalid/1",
			rating = 0f,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = MangaSource("OLD"),
			chapters = null,
			description = null,
		)

		assertEquals("Middle-Aged Miss Hanada's Defloration", buildPrimaryMigrationSearchQuery(seed))
	}

	@Test
	fun `raw selection keeps only stable search identity until refresh`() {
		val manga = Manga(
			id = 42L,
			title = "Result",
			altTitles = setOf("Alias"),
			url = "/42",
			publicUrl = "https://example.invalid/42",
			rating = 0f,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = setOf("Writer"),
			source = MangaSource("DEST"),
			chapters = null,
			description = null,
		)

		val raw = MigrationSelection.fromSearchResult(manga)
		assertEquals("DEST", raw.source)
		assertEquals(42L, raw.id)
		assertEquals("", raw.fingerprint)
		assertEquals(true, raw.isRawSearchIdentity)
	}
}
