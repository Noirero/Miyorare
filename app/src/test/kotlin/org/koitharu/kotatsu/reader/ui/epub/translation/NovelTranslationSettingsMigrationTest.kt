package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelTranslationSettingsMigrationTest {

	@Test
	fun `legacy built in selection migrates to online and preserves previous ai provider`() {
		val migration = legacyBuiltInProviderMigration("built_in", "openai")

		assertEquals(NovelTranslationEngineKind.ONLINE, migration?.engine)
		assertEquals(NovelAiProvider.OPENAI, migration?.provider)
	}

	@Test
	fun `legacy built in selection without previous ai provider keeps a safe ai default`() {
		val migration = legacyBuiltInProviderMigration("built_in", null)

		assertEquals(NovelTranslationEngineKind.ONLINE, migration?.engine)
		assertEquals(NovelAiProvider.GEMINI, migration?.provider)
	}

	@Test
	fun `remote ai provider selection does not migrate`() {
		assertNull(legacyBuiltInProviderMigration("gemini", "openai"))
	}
}
