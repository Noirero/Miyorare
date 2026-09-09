package org.koitharu.kotatsu.core.model

import eu.kanade.tachiyomi.source.CatalogueSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.lnreader.model.LnPlugin
import org.koitharu.kotatsu.mihon.model.MihonMangaSource

class NovelSourceCapabilitiesTest {

	@Test
	fun `missing LN source remains classified as novel`() {
		assertTrue(MissingMangaSource("LN_example").isNovelContentSource)
		assertFalse(MissingMangaSource("OTHER_example").isNovelContentSource)
	}

	@Test
	fun `local novel paths remain format and platform independent`() {
		assertTrue("/storage/emulated/0/Miyorare/downloads/00.Novel/Source/Title/Chapter.epub".isNovelContentPath())
		assertTrue("C:\\Miyorare\\downloads\\00.Novel\\Source\\Title\\Chapter.epub".isNovelContentPath())
		assertTrue("/storage/books/Standalone.EPUB?chapter=1#anchor".isNovelContentPath())
		assertTrue("00.Novel/Source/Title".isNovelContentPath())
		assertFalse("/storage/emulated/0/Miyorare/downloads/Source/Manga/Chapter.cbz".isNovelContentPath())
	}

	@Test
	fun `LN metadata maps to capabilities without loading plugin runtime`() {
		val source = LnMangaSource(
			LnPlugin(
				id = "example",
				name = "Example",
				site = "https://example.com",
				lang = "en",
				version = "1.0",
				iconUrl = "",
				imageRequestInit = null,
				filters = "{}",
				pluginSettings = null,
				hasParsePage = true,
				hasResolveUrl = false,
			),
		)

		assertTrue(source.supportsNovelCapability(NovelSourceCapability.SEARCH))
		assertTrue(source.supportsNovelCapability(NovelSourceCapability.LATEST))
		assertTrue(source.supportsNovelCapability(NovelSourceCapability.FILTERS))
		assertTrue(source.supportsNovelCapability(NovelSourceCapability.PAGED_CHAPTERS))
		assertTrue(source.supportsNovelCapability(NovelSourceCapability.TEXT_CONTENT))
		assertFalse(source.supportsNovelCapability(NovelSourceCapability.RESOLVE_CHAPTER_URL))
	}

	@Test
	fun `Mihon novel capabilities follow source contract`() {
		val source = MihonMangaSource(
			catalogueSource = object : CatalogueSource {
				override val id = 7L
				override val name = "Novel Test"
				override val lang = "en"
				override val supportsLatest = true
				override val isNovelSource = true
			},
			pkgName = "test.novelextension.en.example",
		)

		assertTrue(source.supportsNovelCapability(NovelSourceCapability.SEARCH))
		assertTrue(source.supportsNovelCapability(NovelSourceCapability.LATEST))
		assertTrue(source.supportsNovelCapability(NovelSourceCapability.TEXT_CONTENT))
		assertFalse(source.supportsNovelCapability(NovelSourceCapability.FILTERS))
	}
}
