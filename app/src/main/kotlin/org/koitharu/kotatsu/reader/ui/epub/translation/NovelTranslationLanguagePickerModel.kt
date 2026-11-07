package org.koitharu.kotatsu.reader.ui.epub.translation

import java.util.Locale

/** Presentation-only symbols. The language code remains the authoritative translation setting. */
internal sealed interface NovelLanguageSymbol {
    data object Globe : NovelLanguageSymbol
    data class Flag(val emoji: String) : NovelLanguageSymbol
    data class CodeBadge(val text: String) : NovelLanguageSymbol
}

internal object NovelTranslationLanguagePickerModel {
    // Use flags only for narrowly-associated languages or explicit locale regions.
    // English, Arabic, Spanish, etc. intentionally retain a language-code badge.
    private val regionalFlags = mapOf(
        "id" to "🇮🇩",
        "ja" to "🇯🇵",
        "ko" to "🇰🇷",
        "zh-cn" to "🇨🇳",
        "zh-tw" to "🇹🇼",
    )

    fun symbol(code: String): NovelLanguageSymbol {
        if (code.equals(NovelTranslationSettings.LANGUAGE_AUTO, ignoreCase = true)) {
            return NovelLanguageSymbol.Globe
        }
        val normalized = code.lowercase(Locale.ROOT)
        return regionalFlags[normalized]?.let { NovelLanguageSymbol.Flag(it) }
            ?: NovelLanguageSymbol.CodeBadge(code.uppercase(Locale.ROOT).take(5))
    }

    fun filter(choices: List<NovelTranslationLanguage>, query: String): List<NovelTranslationLanguage> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return choices
        return choices.filter { language ->
            language.name.lowercase(Locale.ROOT).contains(needle) ||
                language.code.lowercase(Locale.ROOT).contains(needle)
        }
    }
}
