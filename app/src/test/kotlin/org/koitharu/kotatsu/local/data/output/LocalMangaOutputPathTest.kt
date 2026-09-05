package org.koitharu.kotatsu.local.data.output

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMangaOutputPathTest {

	@Test
	fun `language folder codes remain stable and dynamic`() {
		assertEquals("NHentai (EN)", LocalMangaOutput.mihonSourceDirectoryName("NHentai", "en"))
		assertEquals("SourceABC (PT-BR)", LocalMangaOutput.mihonSourceDirectoryName("SourceABC", "pt-BR"))
		assertEquals("SourceABC (ZH-HANT)", LocalMangaOutput.mihonSourceDirectoryName("SourceABC", "zh_Hant"))
		assertEquals("SourceABC (EO)", LocalMangaOutput.mihonSourceDirectoryName("SourceABC", "eo"))
	}

	@Test
	fun `pseudo languages and missing metadata remain available`() {
		assertEquals("NHentai (ALL)", LocalMangaOutput.mihonSourceDirectoryName("NHentai", "all"))
		assertEquals("NHentai (OTHER)", LocalMangaOutput.mihonSourceDirectoryName("NHentai", "other"))
		assertEquals("NHentai (OTHER)", LocalMangaOutput.mihonSourceDirectoryName("NHentai", ""))
	}

	@Test
	fun `existing language suffix is not duplicated`() {
		assertEquals("NHentai (EN)", LocalMangaOutput.mihonSourceDirectoryName("NHentai EN", "en"))
		assertEquals("NHentai (PT-BR)", LocalMangaOutput.mihonSourceDirectoryName("NHentai (pt-BR)", "pt-BR"))
		assertEquals("NHentai (PT-BR)", LocalMangaOutput.mihonSourceDirectoryName("NHentai (PT_BR)", "pt-BR"))
	}
}
