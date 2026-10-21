package org.koitharu.kotatsu.core.model

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.NovelSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.lnreader.model.LnPlugin
import org.koitharu.kotatsu.mihon.model.MihonMangaSource

@Suppress("DEPRECATION")
class NovelSourceCapabilitiesTest {

	@Test
	fun `missing LN source remains classified as novel`() {
		val missingLn = MissingMangaSource("LN_example")
		val missingOther = MissingMangaSource("OTHER_example")
		assertTrue(missingLn.isNovelSource)
		assertTrue(missingLn.isNovelContentSource)
		assertFalse(missingOther.isNovelSource)
		assertFalse(missingOther.isNovelContentSource)
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
		val source = mihonSource(isNovelProperty = true, pkgName = "test.extension.en.example")

		assertTrue(source.supportsNovelCapability(NovelSourceCapability.SEARCH))
		assertTrue(source.supportsNovelCapability(NovelSourceCapability.LATEST))
		assertTrue(source.supportsNovelCapability(NovelSourceCapability.TEXT_CONTENT))
		assertFalse(source.supportsNovelCapability(NovelSourceCapability.FILTERS))
	}

	@Test
	fun `compatibility NovelSource marker classifies prose without legacy property or package marker`() {
		val source = MihonMangaSource(
			catalogueSource = object : CatalogueSource, NovelSource {
				override val id = 8L
				override val name = "Marker Novel"
				override val lang = "en"
				override val supportsLatest = false
			},
			pkgName = "test.extension.en.marker",
		)

		assertTrue(source.isNovel)
		assertTrue(source.isNovelSource)
		assertTrue(source.isNovelContentSource)
		assertTrue(source.supportsNovelCapability(NovelSourceCapability.TEXT_CONTENT))
	}

	@Test
	fun `novel package marker remains a fallback when source contract is missing`() {
		val source = mihonSource(
			isNovelProperty = false,
			pkgName = "test.novelextension.en.fallback",
		)

		assertTrue(source.isNovel)
		assertTrue(source.isNovelContentSource)
	}

	@Test
	fun `ordinary manga source remains outside novel pipeline`() {
		val source = mihonSource(
			isNovelProperty = false,
			pkgName = "test.extension.en.manga",
		)

		assertFalse(source.isNovel)
		assertFalse(source.isNovelSource)
		assertFalse(source.isNovelContentSource)
		assertFalse(source.supportsNovelCapability(NovelSourceCapability.TEXT_CONTENT))
	}

	private fun mihonSource(isNovelProperty: Boolean, pkgName: String) = MihonMangaSource(
		catalogueSource = object : CatalogueSource {
			override val id = 7L
			override val name = "Test Source"
			override val lang = "en"
			override val supportsLatest = true
			override val isNovelSource = isNovelProperty
		},
		pkgName = pkgName,
	)
}
