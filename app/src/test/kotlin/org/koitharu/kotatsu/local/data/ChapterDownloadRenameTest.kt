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

class ChapterDownloadRenameTest {
	@get:Rule val directory = TemporaryFolder()

	@Test fun renamesOnlySelectedDownloadWithoutChangingBytes() {
		val root = directory.root
		val bytes = byteArrayOf(1, 2, 3)
		val old = File(root, "Chapter 1 END.cbz").apply { writeBytes(bytes) }
		val other = File(root, "Chapter 2.cbz").apply { writeText("other") }
		assertTrue(renameStandaloneChapterFile(root, old, "Chapter 1"))
		assertFalse(old.exists())
		assertArrayEquals(bytes, File(root, "Chapter 1.cbz").readBytes())
		assertEquals("other", other.readText())
	}

	@Test fun existingAndInProgressTargetsAreNotOverwritten() {
		val root = directory.root
		val old = File(root, "Chapter 1 END.cbz").apply { writeText("original") }
		for (name in listOf("chapter 1.CBZ", "Chapter 1.cbz.tmp")) {
			val other = File(root, name).apply { writeText("other") }
			val failure = runCatching { renameStandaloneChapterFile(root, old, "Chapter 1") }.exceptionOrNull()
			assertTrue(failure is FileAlreadyExistsException)
			assertEquals("original", old.readText())
			assertEquals("other", other.readText())
			assertTrue(other.delete())
		}
	}

	@Test fun indexedOrMissingDownloadsAreNotEligible() {
		val root = directory.root
		val file = File(root, "Chapter 1.cbz")
		assertFalse(canRenameStandaloneChapterFile(root, file))
		file.writeText("chapter")
		assertTrue(canRenameStandaloneChapterFile(root, file))
		assertFalse(renameStandaloneChapterFile(root, file, "Chapter 1"))
		File(root, "index.json").writeText("{}")
		assertFalse(canRenameStandaloneChapterFile(root, file))
		assertTrue(runCatching { renameStandaloneChapterFile(root, file, "Chapter 2") }.isFailure)
		assertEquals("chapter", file.readText())
		assertFalse(File(root, "Chapter 2.cbz").exists())
	}

	@Test fun rejectsInvalidNamesAndKeepsExtensionSeparate() {
		for (name in listOf("", "  ", "..", "../Chapter 1", "nested/Chapter 1", "nested\\Chapter 1")) {
			assertNull(chapterDownloadStem(name))
		}
		assertEquals("Chapter 1", chapterDownloadStem(" Chapter 1.cbz "))
	}
}
