package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EhentaiLegacyDownloadResolverTest {

	@Test
	fun `legacy e-hentai title tags match cleaned global title`() {
		assertTrue(
			EhentaiLegacyDownloadResolver.titlesMatch(
				remoteTitle = "(Raimy) The Herta 黑塔女士 (Unifans)",
				legacyTitle = "(Raimy) The Herta 黑塔女士 (Unifans) [AI Generated]",
			),
		)
	}

	@Test
	fun `every e-hentai language bucket is accepted as legacy`() {
		for (name in EhentaiSourceFamily.legacySourceDirectoryNames) {
			assertTrue(name, EhentaiLegacyDownloadResolver.isLegacySourceDirectoryName(name))
		}
		assertFalse(EhentaiLegacyDownloadResolver.isLegacySourceDirectoryName("ExHentai (OTHER)"))
	}

	@Test
	fun `unique legacy Japanese chapter is discovered without moving it`() {
		withTempRoot { root ->
			val mangaDir = legacyMangaDirectory(
				root,
				source = "E-Hentai (JA)",
				title = "(Raimy) The Herta 黑塔女士 (Unifans) [AI Generated]",
			)

			val resolved = EhentaiLegacyDownloadResolver.findUniqueDirectory(
				root = root,
				remoteSourceName = EhentaiLegacyDownloadResolver.OFFICIAL_SOURCE_NAME,
				remoteTitle = "(Raimy) The Herta 黑塔女士 (Unifans)",
				remotePublicUrl = "https://exhentai.org/g/123456/token/",
			)

			assertEquals(mangaDir.canonicalFile, resolved?.canonicalFile)
			assertTrue(File(mangaDir, "Chapter.cbz").isFile)
		}
	}

	@Test
	fun `same legacy gallery in EN and ALL stays ambiguous`() {
		withTempRoot { root ->
			legacyMangaDirectory(root, "E-Hentai (EN)", "Same Gallery")
			legacyMangaDirectory(root, "E-Hentai (ALL)", "Same Gallery")

			val resolved = EhentaiLegacyDownloadResolver.findUniqueDirectory(
				root = root,
				remoteSourceName = EhentaiLegacyDownloadResolver.OFFICIAL_SOURCE_NAME,
				remoteTitle = "Same Gallery",
			)

			assertNull(resolved)
		}
	}

	@Test
	fun `mihon language source can reuse a different legacy language bucket`() {
		withTempRoot { root ->
			val mangaDir = legacyMangaDirectory(root, "E-Hentai (ZH)", "Same Gallery")
			val resolved = EhentaiLegacyDownloadResolver.findUniqueDirectory(
				root = root,
				remoteSourceName = "MIHON_57122881048805941:E-Hentai",
				remoteTitle = "Same Gallery",
			)
			assertEquals(mangaDir.canonicalFile, resolved?.canonicalFile)
		}
	}

	@Test
	fun `new ExHentai OTHER directory is not treated as legacy`() {
		withTempRoot { root ->
			val mangaDir = legacyMangaDirectory(root, "ExHentai (OTHER)", "Same Gallery")
			assertFalse(
				EhentaiLegacyDownloadResolver.matchesDownloadedCopy(
					remoteSourceName = EhentaiLegacyDownloadResolver.OFFICIAL_SOURCE_NAME,
					remoteTitle = "Same Gallery",
					downloadedTitle = "Same Gallery",
					downloadedUrl = mangaDir.toURI().toString(),
				),
			)
		}
	}

	@Test
	fun `legacy path match requires ehentai family source and Chapter cbz`() {
		withTempRoot { root ->
			val mangaDir = legacyMangaDirectory(root, "E-Hentai (FR)", "Gallery [English]")

			assertTrue(
				EhentaiLegacyDownloadResolver.matchesDownloadedCopy(
					remoteSourceName = EhentaiLegacyDownloadResolver.OFFICIAL_SOURCE_NAME,
					remoteTitle = "Gallery",
					downloadedTitle = "Gallery [English]",
					downloadedUrl = mangaDir.toURI().toString(),
				),
			)
			assertFalse(
				EhentaiLegacyDownloadResolver.matchesDownloadedCopy(
					remoteSourceName = "TSUKI:MIYORARE:miyorare-global:SOMETHING_ELSE",
					remoteTitle = "Gallery",
					downloadedTitle = "Gallery [English]",
					downloadedUrl = mangaDir.toURI().toString(),
				),
			)
			File(mangaDir, "Chapter.cbz").delete()
			assertFalse(
				EhentaiLegacyDownloadResolver.matchesDownloadedCopy(
					remoteSourceName = EhentaiLegacyDownloadResolver.OFFICIAL_SOURCE_NAME,
					remoteTitle = "Gallery",
					downloadedTitle = "Gallery [English]",
					downloadedUrl = mangaDir.toURI().toString(),
				),
			)
		}
	}

	private fun legacyMangaDirectory(root: File, source: String, title: String): File {
		val mangaDir = File(root, "downloads/$source/$title")
		check(mangaDir.mkdirs())
		check(File(mangaDir, "Chapter.cbz").createNewFile())
		return mangaDir
	}

	private inline fun withTempRoot(block: (File) -> Unit) {
		val root = Files.createTempDirectory("miyorare-ehentai-test").toFile()
		try {
			block(root)
		} finally {
			root.deleteRecursively()
		}
	}
}
