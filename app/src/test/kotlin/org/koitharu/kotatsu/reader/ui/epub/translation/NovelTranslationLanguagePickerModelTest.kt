package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTranslationLanguagePickerModelTest {
    @Test
    fun searchesByNameAndLanguageCodeWithoutChangingOrdering() {
        val languages = NovelTranslationLanguages.sourceChoices()
        assertEquals(listOf("id"), NovelTranslationLanguagePickerModel.filter(languages, "indonesian").map { it.code })
        assertEquals(listOf("ja"), NovelTranslationLanguagePickerModel.filter(languages, " JA ").map { it.code })
        assertTrue(NovelTranslationLanguagePickerModel.filter(languages, "english").any { it.code == "en" })
        assertTrue(NovelTranslationLanguagePickerModel.filter(languages, "auto").any { it.code == "auto" })
        assertEquals(languages, NovelTranslationLanguagePickerModel.filter(languages, "  "))
        assertTrue(NovelTranslationLanguagePickerModel.filter(languages, "no-such-language").isEmpty())
    }

    @Test
    fun autoDetectUsesGlobeAndAmbiguousLanguagesNeverGetCountryFlag() {
        assertEquals(NovelLanguageSymbol.Globe, NovelTranslationLanguagePickerModel.symbol("auto"))
        assertEquals(NovelLanguageSymbol.CodeBadge("EN"), NovelTranslationLanguagePickerModel.symbol("en"))
        assertEquals(NovelLanguageSymbol.CodeBadge("AR"), NovelTranslationLanguagePickerModel.symbol("ar"))
        assertEquals(NovelLanguageSymbol.CodeBadge("ES"), NovelTranslationLanguagePickerModel.symbol("es"))
        assertEquals(NovelLanguageSymbol.CodeBadge("JV"), NovelTranslationLanguagePickerModel.symbol("jv"))
        assertEquals(NovelLanguageSymbol.CodeBadge("KU"), NovelTranslationLanguagePickerModel.symbol("ku"))
        assertEquals(NovelLanguageSymbol.CodeBadge("EU"), NovelTranslationLanguagePickerModel.symbol("eu"))
    }

    @Test
    fun onlyConservativeLanguagesAndExplicitRegionsReceiveFlags() {
        assertEquals(NovelLanguageSymbol.Flag("🇮🇩"), NovelTranslationLanguagePickerModel.symbol("id"))
        assertEquals(NovelLanguageSymbol.Flag("🇯🇵"), NovelTranslationLanguagePickerModel.symbol("JA"))
        assertEquals(NovelLanguageSymbol.Flag("🇰🇷"), NovelTranslationLanguagePickerModel.symbol("ko"))
        assertEquals(NovelLanguageSymbol.Flag("🇨🇳"), NovelTranslationLanguagePickerModel.symbol("zh-CN"))
        assertEquals(NovelLanguageSymbol.Flag("🇹🇼"), NovelTranslationLanguagePickerModel.symbol("zh-TW"))
        assertFalse(NovelTranslationLanguagePickerModel.symbol("en") is NovelLanguageSymbol.Flag)
    }

    @Test
    fun customLanguageCodesRemainUsableWithoutFlags() {
        assertEquals(
            NovelLanguageSymbol.CodeBadge("ES-MX"),
            NovelTranslationLanguagePickerModel.symbol("es-MX"),
        )
    }
}
