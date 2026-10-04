package org.koitharu.kotatsu.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadChapterFinalizationRegressionTest {

	@Test
	fun `multiple cbz chapter finalization closes archive only once`() {
		val source = File(
			"src/main/kotlin/org/koitharu/kotatsu/local/data/output/LocalMangaDirOutput.kt",
		).readText()

		val finalizer = source.substringAfter("private suspend fun ZipOutput.flushAndFinish()")
			.substringBefore("private fun chapterFileName")
		val executable = finalizer
			.lineSequence()
			.map { it.substringBefore("//") }
			.joinToString("")
			.replace(Regex("\\s+"), "")

		assertTrue(
			"Closing ZipOutput must be the single terminal operation for a completed chapter",
			executable.contains("try{close()null}"),
		)
		assertFalse(
			"Do not finish and then close the same ZipOutput; close already finalizes ZipOutputStream",
			executable.contains("finish()"),
		)
		assertTrue(
			"A chapter is complete only after its final CBZ exists and is non-empty",
			executable.contains("check(resFile.isFile&&resFile.length()>0L)"),
		)
	}
}
