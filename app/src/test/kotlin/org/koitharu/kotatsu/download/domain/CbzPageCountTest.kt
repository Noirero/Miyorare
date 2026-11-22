package org.koitharu.kotatsu.download.domain

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzPageCountTest {
	@get:Rule val temporaryFolder = TemporaryFolder()

	@Test fun `generated archive counts pages without the cover or index`() {
		val complete = archive("complete.cbz", listOf("one.jpg", "two.jpg"))
		assertEquals(2, CbzPageCount.read(complete, "index.json", "cover.jpg"))
	}

	@Test fun `incomplete archive reports fewer pages than resolved by the source`() {
		val expected = 2
		val incomplete = archive("incomplete.cbz", listOf("one.jpg"))
		val actual = CbzPageCount.read(incomplete, "index.json", "cover.jpg")
		assertEquals(1, expected - actual)
	}

	private fun archive(name: String, pages: List<String>): File = temporaryFolder.newFile(name).apply {
		ZipOutputStream(outputStream()).use { zip ->
			for (entry in pages + listOf("index.json", "cover.jpg", "directory/")) {
				zip.putNextEntry(ZipEntry(entry))
				if (!entry.endsWith('/')) zip.write(1)
				zip.closeEntry()
			}
		}
	}
}
