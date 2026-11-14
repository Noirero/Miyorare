package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExhentaiCompatibilityPolicyTest {

	@Test
	fun `display and legacy lookup do not use the same cleanup`() {
		val source = "[kuso(sia)] Aaaa (29) [English]"
		assertEquals("Aaaa (29)", ExhentaiTitlePolicy.compactTitle(source))
		assertEquals("Aaaa", ExhentaiTitlePolicy.legacyFolderTitle(source))
		assertEquals("Aaaa", ExhentaiTitlePolicy.legacyFolderTitle("[Circle] Aaaa (C29)"))
		assertEquals("Aaaa (C29)", ExhentaiTitlePolicy.compactTitle("[Circle] Aaaa (C29)"))
	}

	@Test
	fun `empty or malformed bracket cleanup keeps source text`() {
		for (title in listOf("[English]", "Aaaa [English", "Aaaa ]")) {
			assertEquals(title, ExhentaiTitlePolicy.compactTitle(title))
		}
		assertEquals("Aaaa (29)", ExhentaiTitlePolicy.compactTitle("[a [b]] Aaaa (29)"))
	}

	@Test
	fun `gallery file names round trip without treating six digit hashes as ids`() {
		assertEquals("Chapter_0123456.cbz", ExhentaiChapterFileName.withGalleryId(123456))
		assertEquals(210L, ExhentaiChapterFileName.explicitGalleryId("Chapter_00210.cbz"))
		for (id in listOf(1L, 210L, 123456L, 1234567L, Long.MAX_VALUE)) {
			assertEquals(id, ExhentaiChapterFileName.explicitGalleryId(ExhentaiChapterFileName.withGalleryId(id)))
		}
		for (fileName in listOf("Chapter.cbz", "Chapter_123456.cbz", "Chapter_838c38.cbz", "Chapter_0.cbz", "Chapter_210.cbz.tmp")) {
			assertNull(ExhentaiChapterFileName.explicitGalleryId(fileName))
		}
	}
}
