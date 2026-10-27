package org.koitharu.kotatsu.alternatives.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationDirectSearchQueryTest {
	private val seed = Manga(
		id = 1L,
		title = "(Arai Kei) Middle-Aged Miss Hanada's Defloration [Uncensored]",
		altTitles = setOf("Hanada-san", "花田さん"),
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

	@Test
	fun `primary query strips leading group and trailing edition decorators`() {
		assertEquals("Middle-Aged Miss Hanada's Defloration", buildPrimaryMigrationSearchQuery(seed))
	}

	@Test
	fun `default direct queries use cleaned title first and keep aliases as fallback`() {
		val queries = buildDirectMigrationSearchQueries(seed, MigrationDirectSearchOptions())

		assertEquals("Middle-Aged Miss Hanada's Defloration", queries.first())
		assertTrue(queries.contains("(Arai Kei) Middle-Aged Miss Hanada's Defloration [Uncensored]"))
		assertTrue(queries.any { normalizeMigrationTitle(it) == normalizeMigrationTitle("Hanada-san") })
	}

	@Test
	fun `original mode keeps exact stored title first`() {
		val queries = buildDirectMigrationSearchQueries(
			seed,
			MigrationDirectSearchOptions(titleMode = MigrationDirectTitleMode.ORIGINAL),
		)

		assertEquals(seed.title, queries.first())
	}

	@Test
	fun `alias fallback can be disabled entirely`() {
		val queries = buildDirectMigrationSearchQueries(
			seed,
			MigrationDirectSearchOptions(
				titleMode = MigrationDirectTitleMode.CLEANED,
				includeAlternativeTitles = true,
				searchAliasesIfNeeded = false,
			),
		)

		assertEquals(listOf("Middle-Aged Miss Hanada's Defloration"), queries)
	}

	@Test
	fun `raw selection keeps only stable search identity until refresh`() {
		val manga = seed.copy(id = 42L, title = "Result", source = MangaSource("DEST"))

		val raw = MigrationSelection.fromSearchResult(manga)
		assertEquals("DEST", raw.source)
		assertEquals(42L, raw.id)
		assertEquals("", raw.fingerprint)
		assertEquals(true, raw.isRawSearchIdentity)
	}
}
