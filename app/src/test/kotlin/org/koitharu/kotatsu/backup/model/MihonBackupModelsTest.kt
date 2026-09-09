package org.koitharu.kotatsu.backup.model

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MihonBackupModelsTest {

	@Test
	fun `manga memo defaults to Mihon empty JSON object`() {
		assertArrayEquals(
			byteArrayOf(0x7B, 0x7D),
			MihonBackupManga(source = 1L, url = "/manga/test").memo,
		)
	}

	@Test
	fun `chapter memo defaults to Mihon empty JSON object`() {
		assertArrayEquals(
			byteArrayOf(0x7B, 0x7D),
			MihonBackupChapter(url = "/chapter/1", name = "Chapter 1").memo,
		)
	}
}
