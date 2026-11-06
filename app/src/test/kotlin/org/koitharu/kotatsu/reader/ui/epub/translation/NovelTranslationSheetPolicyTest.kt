package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTranslationSheetPolicyTest {
    @Test
    fun originalAndTranslationChoicesUseOneDisplayMode() {
        assertEquals(
            NovelTranslationDisplayMode.TRANSLATED_ONLY,
            NovelTranslationSheetModePolicy.translatedMode(NovelTranslationDisplayMode.ORIGINAL, false),
        )
        assertEquals(
            NovelTranslationDisplayMode.BILINGUAL,
            NovelTranslationSheetModePolicy.translatedMode(NovelTranslationDisplayMode.ORIGINAL, true),
        )
        assertEquals(
            NovelTranslationDisplayMode.BILINGUAL,
            NovelTranslationSheetModePolicy.translatedMode(NovelTranslationDisplayMode.BILINGUAL, false),
        )
        assertEquals(
            NovelTranslationDisplayMode.TRANSLATED_ONLY,
            NovelTranslationSheetModePolicy.translatedMode(NovelTranslationDisplayMode.TRANSLATED_ONLY, true),
        )
    }

    @Test
    fun adaptiveCardsStackOnNarrowScreensAndLargerFonts() {
        assertFalse(NovelTranslationSheetLayoutPolicy.useTwoColumns(320f, 1f))
        assertFalse(NovelTranslationSheetLayoutPolicy.useTwoColumns(410f, 1f))
        assertTrue(NovelTranslationSheetLayoutPolicy.useTwoColumns(600f, 1f))
        assertFalse(NovelTranslationSheetLayoutPolicy.useTwoColumns(600f, 1.3f))
        assertTrue(NovelTranslationSheetLayoutPolicy.useTwoColumns(800f, 1.3f))
    }
}
