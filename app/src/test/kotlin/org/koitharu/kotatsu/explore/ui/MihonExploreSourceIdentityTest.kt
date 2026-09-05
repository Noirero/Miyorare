package org.koitharu.kotatsu.explore.ui

import eu.kanade.tachiyomi.source.CatalogueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.mihon.model.MihonMangaSource

class MihonExploreSourceIdentityTest {

	@Test
	fun `same-name language variants have independent Explore identities`() {
		val english = source(sourceId = 101, language = "en")
		val russian = source(sourceId = 102, language = "ru")

		assertEquals("MIHON_101", english.name)
		assertEquals("MIHON_102", russian.name)
		assertNotEquals(
			MangaSourceItem(MangaSourceInfo(english, true, false), isGrid = true).id,
			MangaSourceItem(MangaSourceInfo(russian, true, false), isGrid = true).id,
		)
	}

	private fun source(sourceId: Long, language: String) = MihonMangaSource(
		catalogueSource = object : CatalogueSource {
			override val id = sourceId
			override val name = "NHentai"
			override val lang = language
			override val supportsLatest = false
		},
		pkgName = "extension.nhentai",
		hasLanguageSuffix = true,
	)
}
