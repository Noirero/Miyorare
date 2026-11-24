package org.koitharu.kotatsu.settings.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource

class KotatsuSourceManagementTest {

	@Test
	fun languageFilterCombinesWithSearchAndKeepsSourcesWithoutLanguageDiscoverable() {
		val english = KotatsuMangaSource("SHELF_EN", "Shelf", "en", "MANGA")
		val indonesian = KotatsuMangaSource("SHELF_ID", "Shelf Indonesia", "id", "MANGA")
		val unknown = KotatsuMangaSource("SHELF_UNKNOWN", "Shelf Archive", "", "MANGA")
		val sources = listOf(indonesian, unknown, english)

		assertEquals(listOf(english, unknown, indonesian), filterKotatsuSources(sources, " shelf ", null))
		assertEquals(listOf(indonesian), filterKotatsuSources(sources, "SHELF", "ID"))
		assertEquals(listOf(unknown), filterKotatsuSources(sources, "shelf", ""))
		assertEquals(emptyList<KotatsuMangaSource>(), filterKotatsuSources(sources, "Archive", "en"))
	}

	@Test
	fun faviconUsesWebsiteOriginAndRejectsNonWebOrCredentialUrls() {
		assertEquals("https://shelf.example/favicon.ico", kotatsuWebsiteIconUrl("shelf.example"))
		assertEquals("https://shelf.example/favicon.ico", kotatsuWebsiteIconUrl("https://shelf.example/reader"))
		assertNull(kotatsuWebsiteIconUrl(""))
		assertNull(kotatsuWebsiteIconUrl("file:///tmp/source"))
		assertNull(kotatsuWebsiteIconUrl("https://user:secret@shelf.example"))
	}
}
