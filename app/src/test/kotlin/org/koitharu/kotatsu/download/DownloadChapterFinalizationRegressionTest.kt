package org.koitharu.kotatsu.download

import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.koitharu.kotatsu.download.ui.worker.accountStagedChapter

class DownloadChapterFinalizationRegressionTest {

	@Test
	fun `staged chapter counts as completed even before durable artifact exists`() {
		val accounting = accountStagedChapter(flushCreatedArtifact = false)
		assertTrue(accounting.requestedChapterCompleted)
		assertTrue(!accounting.durableArtifactAvailable)
	}

	@Test
	fun `zip output close finalizes archive exactly once`() {
		val path = Files.createTempFile("miyorare-finalize-", ".zip")
		try {
			ZipOutputStream(path.outputStream()).use { zip ->
				zip.putNextEntry(java.util.zip.ZipEntry("chapter.txt"))
				zip.write("ok".toByteArray())
				zip.closeEntry()
			}
			ZipFile(path.toFile()).use { zip ->
				assertEquals("ok", zip.getInputStream(zip.getEntry("chapter.txt")).bufferedReader().readText())
			}
		} finally {
			Files.deleteIfExists(path)
		}
	}
}
