package org.koitharu.kotatsu.download.ui.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadSizeArtifactTest {

	@get:Rule val folder = TemporaryFolder()

	@Test
	fun originalRootAcceptsOnlyItsOwnArtifact() {
		val a = folder.newFolder("a")
		val b = folder.newFolder("b")
		val own = File(a, "downloads/manga.cbz").apply { parentFile!!.mkdirs(); writeText("A") }
		val foreign = File(b, "downloads/manga.cbz").apply { parentFile!!.mkdirs(); writeText("BBBB") }
		assertEquals(own.canonicalFile, downloadSizeArtifactInRoot(a, own))
		assertNull(downloadSizeArtifactInRoot(a, foreign))
		assertNull(downloadSizeArtifactInRoot(a, own.parentFile))
		own.delete()
		assertNull(downloadSizeArtifactInRoot(a, own))
		a.deleteRecursively()
		assertNull(downloadSizeArtifactInRoot(a, foreign))
	}

	@Test
	fun noOwnedCandidateDoesNotSearchTheRoot() {
		val root = folder.newFolder("root")
		File(root, "downloads/manga.cbz").apply { parentFile!!.mkdirs(); writeText("present") }
		assertNull(downloadSizeArtifactInRoot(root, null))
	}
}
