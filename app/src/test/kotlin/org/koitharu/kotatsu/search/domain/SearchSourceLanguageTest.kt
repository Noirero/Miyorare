package org.koitharu.kotatsu.search.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.mihon.model.MihonMangaSource

class SearchSourceLanguageTest {

	@Test
	fun `regional language code remains distinct`() {
		val brazilian = source(1, "pt-BR")
		val portuguese = source(2, "pt-PT")

		assertEquals("pt-br", brazilian.searchLanguageCode())
		assertTrue(brazilian.matchesPreferredLanguage(setOf("pt")))
		assertTrue(brazilian.matchesPreferredLanguage(setOf("pt-BR")))
		assertFalse(portuguese.matchesPreferredLanguage(setOf("pt-BR")))
	}

	@Test
	fun `pseudo language remains available`() {
		assertEquals("all", source(3, "all").searchLanguageCode())
		assertEquals("other", source(4, "").searchLanguageCode())
	}

	private fun source(sourceId: Long, language: String) = MihonMangaSource(
		catalogueSource = object : CatalogueSource {
			override val id = sourceId
			override val name = "Source"
			override val lang = language
			override val supportsLatest = false
		},
		pkgName = "extension.test",
	)
}
