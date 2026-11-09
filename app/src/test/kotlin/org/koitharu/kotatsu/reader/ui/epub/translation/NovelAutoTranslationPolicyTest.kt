package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAutoTranslationPolicyTest {

	@Test
	fun `only a ready untranslated current chapter starts once`() {
		assertTrue(
			NovelAutoTranslationPolicy.shouldStart(
				enabled = true,
				readerActive = true,
				chapterReady = true,
				hasTranslation = false,
				translationInFlight = false,
				chapterId = 42L,
				lastAttemptedChapterId = 41L,
			),
		)
		assertFalse(
			NovelAutoTranslationPolicy.shouldStart(true, true, true, false, false, 42L, 42L),
		)
	}

	@Test
	fun `preload cached disabled and in flight states never start`() {
		assertFalse(NovelAutoTranslationPolicy.shouldStart(false, true, true, false, false, 42L, 0L))
		assertFalse(NovelAutoTranslationPolicy.shouldStart(true, false, true, false, false, 42L, 0L))
		assertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, false, false, false, 42L, 0L))
		assertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, true, true, false, 42L, 0L))
		assertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, true, false, true, 42L, 0L))
	}


	@Test
	fun `page mode requires an actual opened paged viewport and an idle translator`() {
		assertTrue(NovelAutoTranslationPolicy.shouldStartPage(true, true, true, true, true, false))
		assertFalse(NovelAutoTranslationPolicy.shouldStartPage(false, true, true, true, true, false))
		assertFalse(NovelAutoTranslationPolicy.shouldStartPage(true, false, true, true, true, false))
		assertFalse(NovelAutoTranslationPolicy.shouldStartPage(true, true, false, true, true, false))
		assertFalse(NovelAutoTranslationPolicy.shouldStartPage(true, true, true, false, true, false))
		assertFalse(NovelAutoTranslationPolicy.shouldStartPage(true, true, true, true, false, false))
		assertFalse(NovelAutoTranslationPolicy.shouldStartPage(true, true, true, true, true, true))
	}

	@Test
	fun `page auto mode is triggered by navigation but not reflow and only requests visible source`() {
		val fragment = source("EpubReaderFragment.kt")
		assertTrue(fragment.contains("autoPageOpenedPending = true"))
		assertTrue(fragment.contains("if (openedChapter != null && openedChapter != autoPageLastChapterId) autoPageOpenedPending = true"))
		assertTrue(fragment.contains("autoPageOpenedPending = false"))
		assertTrue(fragment.contains("val visibleOnly = selection.scope == NovelTranslationScope.PAGE"))
		assertTrue(fragment.contains("NovelTranslationPageScope.uncovered("))
		assertTrue(fragment.contains("if (!automatic) showCachedTranslation(selection.displayMode)"))
		assertTrue(fragment.contains("scope = if (mode == NovelAutoTranslationMode.PAGE) NovelTranslationScope.PAGE"))
	}

	private fun source(file: String): String = sequenceOf(
		java.io.File("src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub", file),
		java.io.File("app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub", file),
	).first(java.io.File::isFile).readText()

	@Test
	fun `auto projection retention stays bounded around current chapter`() {
		assertTrue(NovelAutoTranslationPolicy.shouldRetainProjection(10, 12, 2))
		assertTrue(NovelAutoTranslationPolicy.shouldRetainProjection(14, 12, 2))
		assertFalse(NovelAutoTranslationPolicy.shouldRetainProjection(9, 12, 2))
	}

	@Test
	fun `provider binding crosses cost boundary explicitly`() {
		val online = NovelAutoTranslationPolicy.providerBinding(NovelTranslationEngineKind.ONLINE, NovelAiProvider.GEMINI)
		val gemini = NovelAutoTranslationPolicy.providerBinding(NovelTranslationEngineKind.AI, NovelAiProvider.GEMINI)
		val openAi = NovelAutoTranslationPolicy.providerBinding(NovelTranslationEngineKind.AI, NovelAiProvider.OPENAI)
		assertNotEquals(online, gemini)
		assertNotEquals(gemini, openAi)
	}
}
