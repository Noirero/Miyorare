package org.koitharu.kotatsu.local.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChapterDownloadRenameTest {
	@get:Rule val directory = TemporaryFolder()

	@Test fun renamesOnlySelectedDownloadWithoutChangingBytes() {
		val root = directory.root
		val old = File(root, "Chapter 1 END.cbz").apply { writeCbz() }
		val bytes = old.readBytes()
		val other = File(root, "Chapter 2.cbz").apply { writeText("other") }
		assertTrue(renameStandaloneChapterFile(root, old, "Chapter 1"))
		assertFalse(old.exists())
		assertArrayEquals(bytes, File(root, "Chapter 1.cbz").readBytes())
		assertEquals("other", other.readText())
	}

	@Test fun existingAndInProgressTargetsAreNotOverwritten() {
		val root = directory.root
		val old = File(root, "Chapter 1 END.cbz").apply { writeCbz() }
		val original = old.readBytes()
		for (name in listOf("chapter 1.CBZ", "Chapter 1.cbz.tmp")) {
			val other = File(root, name).apply { writeText("other") }
			val failure = runCatching { renameStandaloneChapterFile(root, old, "Chapter 1") }.exceptionOrNull()
			assertTrue(failure is FileAlreadyExistsException)
			assertArrayEquals(original, old.readBytes())
			assertEquals("other", other.readText())
			assertTrue(other.delete())
		}
	}

	@Test fun indexedOrMissingDownloadsAreNotEligible() {
		val root = directory.root
		val file = File(root, "Chapter 1.cbz")
		assertFalse(canRenameStandaloneChapterFile(root, file))
		file.writeCbz()
		val original = file.readBytes()
		assertTrue(canRenameStandaloneChapterFile(root, file))
		assertFalse(renameStandaloneChapterFile(root, file, "Chapter 1"))
		File(root, "index.json").writeText("{}")
		assertFalse(canRenameStandaloneChapterFile(root, file))
		assertTrue(runCatching { renameStandaloneChapterFile(root, file, "Chapter 2") }.isFailure)
		assertArrayEquals(original, file.readBytes())
		assertFalse(File(root, "Chapter 2.cbz").exists())
	}

	@Test fun unreadableAndEmbeddedIndexArchivesCannotBeRenamed() {
		val root = directory.root
		val file = File(root, "Chapter 1 END.cbz")
		file.writeText("not a zip")
		assertFalse(canRenameStandaloneChapterFile(root, file))
		assertTrue(runCatching { renameStandaloneChapterFile(root, file, "Chapter 1") }.isFailure)
		assertEquals("not a zip", file.readText())

		file.writeCbz(indexed = true)
		val original = file.readBytes()
		assertFalse(canRenameStandaloneChapterFile(root, file))
		assertTrue(runCatching { renameStandaloneChapterFile(root, file, "Chapter 1") }.isFailure)
		assertArrayEquals(original, file.readBytes())
		assertFalse(File(root, "Chapter 1.cbz").exists())
	}

	private fun File.writeCbz(indexed: Boolean = false) {
		ZipOutputStream(outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("1.jpg"))
			zip.write(byteArrayOf(1, 2, 3))
			zip.closeEntry()
			if (indexed) {
				zip.putNextEntry(ZipEntry("index.json"))
				zip.write("{}".toByteArray())
				zip.closeEntry()
			}
		}
	}

	@Test fun rejectsInvalidNamesAndKeepsExtensionSeparate() {
		for (name in listOf("", "  ", "..", "../Chapter 1", "nested/Chapter 1", "nested\\Chapter 1")) {
			assertNull(chapterDownloadStem(name))
		}
		assertEquals("Chapter 1", chapterDownloadStem(" Chapter 1.cbz "))
	}
}
