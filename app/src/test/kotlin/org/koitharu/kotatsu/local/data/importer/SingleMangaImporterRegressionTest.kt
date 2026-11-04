package org.koitharu.kotatsu.local.data.importer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SingleMangaImporterRegressionTest {

	@Test
	fun `ordinary document uri is not assumed to be a directory`() {
		val source = source()
		val isDirectory = source
			.substringAfter("private fun isDirectory(uri: Uri): Boolean")
			.substringBefore("\n\t}")
			.replace(Regex("\\s+"), "")

		assertTrue(isDirectory.contains("?.isDirectory==true"))
		assertFalse(isDirectory.contains("}.isSuccess"))
	}

	@Test
	fun `file import removes only an empty directory left by the old bug`() {
		val source = source().replace(Regex("\\s+"), "")

		assertTrue(
			source.contains(
				"outputFile.isDirectory&&outputFile.listFiles()?.isEmpty()==true&&!outputFile.delete()",
			),
		)
		assertFalse(source.contains("outputFile.deleteRecursively()"))
	}

	@Test
	fun `epub import validates a complete temporary file before replacing destination`() {
		val source = source().replace(Regex("\\s+"), "")

		assertTrue(source.contains("hasEpubExtension(name)->importEpubAtomically(uri,name)"))
		assertTrue(source.contains("copyUriToFile(uri,tempFile)"))
		assertTrue(source.contains("EpubParser.parse(tempFile)"))
		assertTrue(source.contains("outputFile.renameTo(backupFile)"))
		assertTrue(source.contains("tempFile.renameTo(outputFile)"))
	}

	@Test
	fun `external epub preview validates through existing parser`() {
		val source = source().replace(Regex("\\s+"), "")

		assertTrue(source.contains("suspendfunpreviewEpub(uri:Uri):EpubImportPreview"))
		assertTrue(source.contains("EpubParser.parse(previewFile)"))
		assertTrue(source.contains("previewFile.delete()"))
	}


	@Test
	fun `single ZIP CBZ import validates staged content before replacing an existing archive`() {
		val source = source().replace(Regex("\\s+"), "")
		val archive = source.substringAfter("privatesuspendfunimportArchiveAtomically(")
			.substringBefore("privatesuspendfunimportEpubAtomically(")

		assertTrue(source.contains("else->importArchiveAtomically(uri,name)"))
		assertTrue(archive.contains("copyUriToFile(uri,tempFile)"))
		assertTrue(archive.contains("LocalMangaParser(tempFile).getManga(withDetails=false)"))
		assertTrue(archive.contains("commitRootReplacement(outputFile,tempFile)"))
		assertFalse(archive.contains("copyUriToFile(uri,outputFile)"))
	}

	@Test
	fun `PDF conversion stages output for single and directory imports`() {
		val source = source().replace(Regex("\\s+"), "")

		assertTrue(source.contains("renderPdfToCbzAtomically(pfd,outputFile)"))
		assertTrue(source.contains("renderPdfToCbzAtomically(pfd,cbzFile)"))
		assertTrue(source.contains("renderPdfToCbz(pfd,tempFile)"))
		assertTrue(source.contains("commitRootReplacement(outputFile,tempFile)"))
		assertTrue(source.contains("if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,zip))"))
		assertFalse(source.contains("renderPdfToCbz(pfd,outputFile)"))
		assertFalse(source.contains("renderPdfToCbz(pfd,cbzFile)"))
		assertFalse(source.contains("cbzFile.delete()"))
	}

	@Test
	fun `directory file import does not write directly over existing content`() {
		val source = source().replace(Regex("\\s+"), "")
		val directoryCopy = source.substringAfter("privatesuspendfunDocumentFile.copyTo(")
			.substringBefore("privatefunimportTarget(")

		assertTrue(directoryCopy.contains("copyUriToFile(uri,tempFile)"))
		assertTrue(directoryCopy.contains("commitRootReplacement(outputFile,tempFile)"))
		assertFalse(directoryCopy.contains("File(destDir,requireName()).sink()"))
		assertTrue(source.contains("target.canonicalFile.parentFile!=dir.canonicalFile"))
	}

	private fun source(): String {
		val relativePath =
			"org/koitharu/kotatsu/local/data/importer/SingleMangaImporter.kt"
		return sequenceOf(
			File("src/main/kotlin", relativePath),
			File("app/src/main/kotlin", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
