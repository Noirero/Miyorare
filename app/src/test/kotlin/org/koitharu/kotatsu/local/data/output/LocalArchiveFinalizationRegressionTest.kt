package org.koitharu.kotatsu.local.data.output

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalArchiveFinalizationRegressionTest {

	@Test
	fun `root replacement preserves old archive until replacement commits`() {
		val output = source("org/koitharu/kotatsu/local/data/output/LocalMangaOutput.kt")
		val replace = output
			.substringAfter("protectedfunreplaceRootFileBlocking(temp:File){")
			.substringBefore("companionobject{")

		assertTrue(replace.contains("if(hadRoot&&!hasBackup){error("))
		assertFalse(replace.contains("if(!hasBackup){rootFile.delete()"))
		assertTrue(replace.contains("temp.copyTo(rootFile,overwrite=true)"))
		assertTrue(replace.contains("if(hasBackup){check(backup.renameTo(rootFile))"))
	}

	@Test
	fun `single cbz filtering uses the shared safe finalizer`() {
		val zip = source("org/koitharu/kotatsu/local/data/output/LocalMangaZipOutput.kt")
		val filter = zip
			.substringAfter("suspendfunfilterChapters(")
			.substringBefore("companionobject{", missingDelimiterValue = zip)

		assertTrue(filter.contains("subject.replaceFilteredRootFile()"))
		assertFalse(filter.contains("subject.rootFile.delete()"))
		assertFalse(filter.contains("subject.output.file.renameTo(subject.rootFile)"))
	}

	private fun source(relativePath: String): String {
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
			)
			.replace(Regex("""//[^\\r\\n]*"""), "")
			.replace(Regex("""\\s+"""), "")
	}
}
