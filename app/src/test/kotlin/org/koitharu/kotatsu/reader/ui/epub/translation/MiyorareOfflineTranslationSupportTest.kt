package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiyorareOfflineTranslationSupportTest {

	@Test
	fun `english to indonesian requires only the indonesian model`() {
		assertEquals(
			listOf("id"),
			MiyorareOfflineTranslationSupport.requiredModelLanguages("en", "id"),
		)
	}

	@Test
	fun `indonesian to english requires only the indonesian model`() {
		assertEquals(
			listOf("id"),
			MiyorareOfflineTranslationSupport.requiredModelLanguages("id", "en"),
		)
	}

	@Test
	fun `two non english languages require both language models`() {
		assertEquals(
			listOf("id", "ja"),
			MiyorareOfflineTranslationSupport.requiredModelLanguages("id", "ja"),
		)
	}

	@Test
	fun `auto detect does not guess which offline source model to download`() {
		assertNull(
			MiyorareOfflineTranslationSupport.requiredModelLanguages(
				NovelTranslationSettings.LANGUAGE_AUTO,
				"id",
			),
		)
	}
}