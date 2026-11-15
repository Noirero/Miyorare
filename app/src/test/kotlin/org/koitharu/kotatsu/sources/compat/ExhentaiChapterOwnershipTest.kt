package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExhentaiChapterOwnershipTest {
	@get:Rule val directory = TemporaryFolder()

	@Test fun `plain and tagged chapters in same folder have independent provenance`() {
		val a = cbz("Chapter.cbz", 11, ExhentaiChapterOwnership.comment(11))
		val b = cbz(ExhentaiChapterFileName.withGalleryId(2222222), 22, ExhentaiChapterOwnership.comment(2222222))
		val aBefore = a.readBytes()
		val bBefore = b.readBytes()
		assertEquals(ExhentaiChapterOwnership.Evidence.Owned(11), ExhentaiChapterOwnership.inspect(a))
		assertEquals(ExhentaiChapterOwnership.Evidence.Owned(2222222), ExhentaiChapterOwnership.inspect(b))
		assertArrayEquals(aBefore, a.readBytes())
		assertArrayEquals(bBefore, b.readBytes())
	}

	@Test fun `fresh lookup selects the right bytes and never borrows a known foreign copy`() {
		val a = cbz("Chapter.cbz", 11, ExhentaiChapterOwnership.comment(11))
		assertEquals(null, ExhentaiChapterOwnership.select(22, listOf(a), legacyMatch = a))
		val b = cbz("Chapter_22.cbz", 22, ExhentaiChapterOwnership.comment(22))
		val candidates = listOf(a, b).map { File(it.path) }
		assertEquals(11, page(ExhentaiChapterOwnership.select(11, candidates, legacyMatch = a)))
		assertEquals(22, page(ExhentaiChapterOwnership.select(22, candidates, legacyMatch = a)))
	}

	@Test fun `numeric legacy filename without marker does not claim any gallery`() {
		for (name in listOf("Chapter.cbz", "Chapter_123456.cbz", "Chapter_12345678.cbz")) {
			assertEquals(ExhentaiChapterOwnership.Evidence.Unmarked, ExhentaiChapterOwnership.inspect(cbz(name, 31)))
		}
	}

	@Test fun `conflicting suffix and incomplete archive are not ownership evidence`() {
		val conflict = cbz("Chapter_220.cbz", 42, ExhentaiChapterOwnership.comment(221))
		assertEquals(ExhentaiChapterOwnership.Evidence.Unusable, ExhentaiChapterOwnership.inspect(conflict))
		val invalid = directory.newFile("Chapter.cbz").apply { writeBytes(byteArrayOf(80, 75, 3, 4)) }
		assertEquals(ExhentaiChapterOwnership.Evidence.Unusable, ExhentaiChapterOwnership.inspect(invalid))
	}

	private fun page(file: File?): Int = java.util.zip.ZipFile(checkNotNull(file)).use { zip ->
		zip.getInputStream(zip.getEntry("1.jpg")).use { it.read() }
	}

	private fun cbz(name: String, payload: Int, comment: String? = null): File = directory.newFile(name).apply {
		ZipOutputStream(outputStream()).use { zip ->
			if (comment != null) zip.setComment(comment)
			zip.putNextEntry(ZipEntry("1.jpg"))
			zip.write(payload)
			zip.closeEntry()
		}
	}
}
