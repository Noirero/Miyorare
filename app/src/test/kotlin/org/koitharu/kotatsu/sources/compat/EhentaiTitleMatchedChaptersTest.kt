package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EhentaiTitleMatchedChaptersTest {
	@Test fun onlyExhentaiTsukiIdentitiesAreEligible() {
		assertTrue(EhentaiSourceFamily.supportsTitleMatchedDownloads(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME))
		assertTrue(EhentaiSourceFamily.supportsTitleMatchedDownloads("TSUKI:GEKKOUSHI:gekko:EXHENTAI"))
		assertFalse(EhentaiSourceFamily.supportsTitleMatchedDownloads("TSUKI:GEKKOUSHI:gekko:OTHER"))
		assertFalse(EhentaiSourceFamily.supportsTitleMatchedDownloads("MIHON_123:ExHentai"))
		assertFalse(EhentaiSourceFamily.supportsTitleMatchedDownloads("TSUKI:GEKKOUSHI:EXHENTAI"))
	}

	@Test fun currentSourceDirectoryFindsChapterWithoutGalleryMetadata() = withRoot { root ->
		val file = chapter(root, "ExHentai (OTHER)", "Legacy Gallery")
		assertEquals(listOf(file.canonicalFile),
			EhentaiLegacyDownloadResolver.findTitleMatchedChapters(root, "Legacy Gallery"))
		assertTrue(file.exists())
	}

	@Test fun duplicateSuffixCanBeFoundButNotSilentlyDropped() = withRoot { root ->
		val first = chapter(root, "ExHentai (OTHER)", "Legacy Gallery")
		val second = chapter(root, "ExHentai (OTHER)", "Legacy Gallery_1")
		assertEquals(setOf(first.canonicalFile, second.canonicalFile),
			EhentaiLegacyDownloadResolver.findTitleMatchedChapters(root, "Legacy Gallery").toSet())
	}

	@Test fun legacyLanguageDirectoriesAndStandaloneCbzRemainDiscoverable() = withRoot { root ->
		val legacy = chapter(root, "E-Hentai (EN)", "Legacy Gallery")
		val single = File(root, "downloads/ExHentai (OTHER)/Legacy Gallery_2.cbz").apply {
			parentFile.mkdirs()
			check(createNewFile())
		}
		assertEquals(setOf(legacy.canonicalFile, single.canonicalFile),
			EhentaiLegacyDownloadResolver.findTitleMatchedChapters(root, "Legacy Gallery").toSet())
	}

	@Test fun koreanTaggedDuplicateNamesFromLegacyArchivesNormalizeToTheSameTitle() = withRoot { root ->
		val title = "Otona no Yoyuu _ 어른의 여유 (COMIC Kairakuten BEAST 2026-09)"
		val legacy = chapter(root, "ExHentai (OTHER)", title)
		val tagged = chapter(
			root, "ExHentai (OTHER)",
			"[Bekotarou] $title [Korean] [Team Edge] [Decensored]",
		)
		assertEquals(
			setOf(legacy.canonicalFile, tagged.canonicalFile),
			EhentaiLegacyDownloadResolver.findTitleMatchedChapters(root, title).toSet(),
		)
	}

	@Test fun wrongTitleAndOtherSourceNeverEnterCandidateSet() = withRoot { root ->
		chapter(root, "Unrelated (OTHER)", "Legacy Gallery")
		chapter(root, "ExHentai (OTHER)", "Different Gallery")
		assertTrue(EhentaiLegacyDownloadResolver.findTitleMatchedChapters(root, "Legacy Gallery").isEmpty())
	}

	private fun chapter(root: File, source: String, title: String): File {
		val dir = File(root, "downloads/$source/$title")
		check(dir.mkdirs())
		return File(dir, "Chapter.cbz").apply { check(createNewFile()) }
	}

	private inline fun withRoot(block: (File) -> Unit) {
		val root = Files.createTempDirectory("exhentai-title-test").toFile()
		try {
			block(root)
		} finally {
			root.deleteRecursively()
		}
	}
}
