package org.koitharu.kotatsu.settings.sources.catalog

import eu.kanade.tachiyomi.source.CatalogueSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.mihon.model.MihonMangaSource

class InstalledExtensionLanguageFilterTest {

	@Test
	fun `multi-source apk matches every contained source language`() {
		val sources = listOf(source(1, "en"), source(2, "ru"), source(3, "pt-BR"))

		assertTrue(installedExtensionMatchesLanguage(sources, "en", "ru"))
		assertTrue(installedExtensionMatchesLanguage(sources, "en", "pt_br"))
		assertFalse(installedExtensionMatchesLanguage(sources, "en", "ja"))
	}

	@Test
	fun `package language remains fallback while sources are loading`() {
		assertTrue(installedExtensionMatchesLanguage(emptyList(), "en", "EN"))
		assertFalse(installedExtensionMatchesLanguage(emptyList(), "en", "ru"))
	}

	@Test
	fun `universal package language remains eligible for a specific language`() {
		assertTrue(installedExtensionMatchesLanguage(emptyList(), "all", "ja"))
	}

	@Test
	fun `uninstalled multi-source entry matches every published source language`() {
		val entry = ExternalExtensionRepoEntry(
			name = "Multi",
			packageName = "extension.multi",
			apkName = "multi.apk",
			lang = "all",
			versionCode = 1,
			versionName = "1.0",
			sources = listOf(
				ExternalExtensionRepoSource(id = "1", name = "Japanese", lang = "ja"),
				ExternalExtensionRepoSource(id = "2", name = "Chinese", lang = "zh"),
			),
		)

		assertTrue(extensionEntryMatchesLanguage(entry, emptyList(), "ja"))
		assertTrue(extensionEntryMatchesLanguage(entry, emptyList(), "ZH"))
		// Older repositories can expose only a universal package language without per-source metadata.
		assertTrue(extensionEntryMatchesLanguage(entry.copy(sources = emptyList()), emptyList(), "ko"))
	}

	private fun source(sourceId: Long, language: String) = MihonMangaSource(
		catalogueSource = object : CatalogueSource {
			override val id = sourceId
			override val name = "Reader"
			override val lang = language
			override val supportsLatest = false
		},
		pkgName = "extension.reader",
	)
}
