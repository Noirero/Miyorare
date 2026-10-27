package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTranslationPresentationPolicyTest {

	@Test
	fun `translated only is the canonical presentation default`() {
		assertEquals(
			NovelTranslationDisplayMode.TRANSLATED_ONLY,
			NovelTranslationPresentationPolicy.defaultDisplayMode,
		)
		assertFalse(NovelTranslationPresentationPolicy.defaultBilingual)
	}

	@Test
	fun `legacy implicit bilingual state migrates once to translated only`() {
		val missing = NovelTranslationPresentationPolicy.migrate(
			storedVersion = 0,
			storedBilingual = null,
		)
		val persistedTrue = NovelTranslationPresentationPolicy.migrate(
			storedVersion = 0,
			storedBilingual = true,
		)

		listOf(missing, persistedTrue).forEach { migration ->
			assertFalse(migration.bilingual)
			assertEquals(NovelTranslationPresentationPolicy.CURRENT_VERSION, migration.version)
			assertTrue(migration.shouldPersist)
		}
	}

	@Test
	fun `explicit bilingual choice is preserved after migration`() {
		val migration = NovelTranslationPresentationPolicy.migrate(
			storedVersion = NovelTranslationPresentationPolicy.CURRENT_VERSION,
			storedBilingual = true,
		)

		assertTrue(migration.bilingual)
		assertEquals(NovelTranslationPresentationPolicy.CURRENT_VERSION, migration.version)
		assertFalse(migration.shouldPersist)
	}

	@Test
	fun `migrated state without stored value remains translated only`() {
		val migration = NovelTranslationPresentationPolicy.migrate(
			storedVersion = NovelTranslationPresentationPolicy.CURRENT_VERSION,
			storedBilingual = null,
		)

		assertFalse(migration.bilingual)
		assertFalse(migration.shouldPersist)
	}
}
