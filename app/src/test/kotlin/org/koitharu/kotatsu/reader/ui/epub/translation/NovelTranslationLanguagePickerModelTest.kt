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
        assertEquals(listOf("zh-TW"), NovelTranslationLanguagePickerModel.filter(languages, " ZH-TW ").map { it.code })
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
        assertEquals(NovelLanguageSymbol.CodeBadge("LA"), NovelTranslationLanguagePickerModel.symbol("la"))
    }

    @Test
    fun onlyConservativeLanguagesAndExplicitRegionsReceiveFlags() {
        assertEquals(NovelLanguageSymbol.Flag("🇿🇦"), NovelTranslationLanguagePickerModel.symbol("af"))
        assertEquals(NovelLanguageSymbol.Flag("🇦🇱"), NovelTranslationLanguagePickerModel.symbol("sq"))
        assertEquals(NovelLanguageSymbol.Flag("🇪🇹"), NovelTranslationLanguagePickerModel.symbol("am"))
        assertEquals(NovelLanguageSymbol.Flag("🇦🇲"), NovelTranslationLanguagePickerModel.symbol("hy"))
        assertEquals(NovelLanguageSymbol.Flag("🇦🇿"), NovelTranslationLanguagePickerModel.symbol("az"))
        assertEquals(NovelLanguageSymbol.Flag("🇧🇾"), NovelTranslationLanguagePickerModel.symbol("be"))
        assertEquals(NovelLanguageSymbol.Flag("🇧🇩"), NovelTranslationLanguagePickerModel.symbol("bn"))
        assertEquals(NovelLanguageSymbol.Flag("🇧🇦"), NovelTranslationLanguagePickerModel.symbol("bs"))
        assertEquals(NovelLanguageSymbol.Flag("🇧🇬"), NovelTranslationLanguagePickerModel.symbol("bg"))
        assertEquals(NovelLanguageSymbol.Flag("🇮🇪"), NovelTranslationLanguagePickerModel.symbol("ga"))
        assertEquals(NovelLanguageSymbol.Flag("🇮🇹"), NovelTranslationLanguagePickerModel.symbol("it"))
        assertEquals(NovelLanguageSymbol.Flag("🇮🇳"), NovelTranslationLanguagePickerModel.symbol("kn"))
        assertEquals(NovelLanguageSymbol.Flag("🇰🇿"), NovelTranslationLanguagePickerModel.symbol("kk"))
        assertEquals(NovelLanguageSymbol.Flag("🇰🇭"), NovelTranslationLanguagePickerModel.symbol("km"))
        assertEquals(NovelLanguageSymbol.Flag("🇰🇬"), NovelTranslationLanguagePickerModel.symbol("ky"))
        assertEquals(NovelLanguageSymbol.Flag("🇱🇦"), NovelTranslationLanguagePickerModel.symbol("lo"))
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
