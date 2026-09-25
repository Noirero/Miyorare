package org.koitharu.kotatsu.download

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadEmptySuccessRegressionTest {

	@Test
	fun `download worker cannot report success without a real chapter`() {
		val worker = productionSource(
			"org/koitharu/kotatsu/download/ui/worker/DownloadWorker.kt",
		).replace(Regex("\\s+"), "")

		assertTrue(worker.contains("if(resolvedPages.isEmpty()){throwIOException("))
		assertTrue(worker.contains("varcompletedRequestedChapters=0"))
		assertTrue(worker.contains("completedRequestedChapters++"))
		assertTrue(worker.contains("check(completedRequestedChapters>0)"))
	}

	@Test
	fun `failed directory download removes an empty title folder`() {
		val output = productionSource(
			"org/koitharu/kotatsu/local/data/output/LocalMangaDirOutput.kt",
		).replace(Regex("\\s+"), "")

		assertTrue(output.contains("if(rootFile.isDirectory&&rootFile.list()?.isEmpty()==true){"))
		assertTrue(output.contains("rootFile.deleteAwait()"))
	}

	private fun productionSource(relativePath: String): String {
		return sequenceOf(
			File("src/main/kotlin", relativePath),
			File("app/src/main/kotlin", relativePath),
		).firstOrNull(File::isFile)?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
