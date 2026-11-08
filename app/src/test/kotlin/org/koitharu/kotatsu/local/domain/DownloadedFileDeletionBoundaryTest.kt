package org.koitharu.kotatsu.local.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Physical deletion is allowed only below the exact original download root.
 * Directory prefixes and symlink escapes must never turn a neighbouring folder into a target.
 */
class DownloadedFileDeletionBoundaryTest {

	@Test
	fun `only files inside selected downloads root may be removed`() {
		val workspace = Files.createTempDirectory("miyorare-delete-guard").toFile()
		try {
			val selected = File(workspace, "normal")
			val good = File(selected, "downloads/Source/Manga/Chapter.cbz")
			val neighbouring = File(workspace, "normal-other/downloads/Source/Manga/Chapter.cbz")
			val outsideDownloads = File(selected, "local/Manga.cbz")
			val downloadsDirItself = File(selected, "downloads")
			assertTrue(good.parentFile.mkdirs())
			assertTrue(neighbouring.parentFile.mkdirs())
			assertTrue(outsideDownloads.parentFile.mkdirs())
			assertTrue(isInsideDownloadRoot(selected, good))
			assertFalse(isInsideDownloadRoot(selected, neighbouring))
			assertFalse(isInsideDownloadRoot(selected, outsideDownloads))
			assertFalse(isInsideDownloadRoot(selected, downloadsDirItself))
		} finally {
			workspace.deleteRecursively()
		}
	}

	@Test
	fun `canonical path prevents symbolic links escaping the download root`() {
		val workspace = Files.createTempDirectory("miyorare-delete-link").toFile()
		try {
			val selected = File(workspace, "normal")
			val downloadsDir = File(selected, "downloads")
			val outside = File(workspace, "private/Manga.cbz")
			assertTrue(downloadsDir.mkdirs())
			assertTrue(outside.parentFile.mkdirs())
			// A dangling link may not be resolved by File.canonicalFile on the JVM.
			// Materialize the outside target so this exercises a real path escape.
			assertTrue(outside.createNewFile())
			val link = File(downloadsDir, "Manga.cbz")
			Files.createSymbolicLink(link.toPath(), outside.toPath())
			assertFalse(isInsideDownloadRoot(selected, link))
		} finally {
			workspace.deleteRecursively()
		}
	}
}
