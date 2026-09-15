package org.koitharu.kotatsu.reader.ui.epub.translation

import java.util.Locale

/** Display metadata only; translation engines accept language codes directly and are not pair-whitelisted. */
data class NovelTranslationLanguage(
	val code: String,
	val name: String,
)

object NovelTranslationLanguages {

	val auto = NovelTranslationLanguage(NovelTranslationSettings.LANGUAGE_AUTO, "Auto-detect")

	/**
	 * Friendly choices for the Novel translator. This list is not a protocol whitelist: callers may
	 * still pass any language code accepted by the selected provider, so new provider languages do
	 * not require an app update.
	 */
	val common: List<NovelTranslationLanguage> = listOf(
		"af" to "Afrikaans",
		"sq" to "Albanian",
		"am" to "Amharic",
		"ar" to "Arabic",
		"hy" to "Armenian",
		"az" to "Azerbaijani",
		"eu" to "Basque",
		"be" to "Belarusian",
		"bn" to "Bengali",
		"bs" to "Bosnian",
		"bg" to "Bulgarian",
		"ca" to "Catalan",
		"ceb" to "Cebuano",
		"zh-CN" to "Chinese (Simplified)",
		"zh-TW" to "Chinese (Traditional)",
		"co" to "Corsican",
		"hr" to "Croatian",
		"cs" to "Czech",
		"da" to "Danish",
		"nl" to "Dutch",
		"en" to "English",
		"eo" to "Esperanto",
		"et" to "Estonian",
		"fi" to "Finnish",
		"fr" to "French",
		"fy" to "Frisian",
		"gl" to "Galician",
		"ka" to "Georgian",
		"de" to "German",
		"el" to "Greek",
		"gu" to "Gujarati",
		"ht" to "Haitian Creole",
		"ha" to "Hausa",
		"haw" to "Hawaiian",
		"he" to "Hebrew",
		"hi" to "Hindi",
		"hmn" to "Hmong",
		"hu" to "Hungarian",
		"is" to "Icelandic",
		"ig" to "Igbo",
		"id" to "Indonesian",
		"ga" to "Irish",
		"it" to "Italian",
		"ja" to "Japanese",
		"jv" to "Javanese",
		"kn" to "Kannada",
		"kk" to "Kazakh",
		"km" to "Khmer",
		"ko" to "Korean",
		"ku" to "Kurdish",
		"ky" to "Kyrgyz",
		"lo" to "Lao",
		"la" to "Latin",
		"lv" to "Latvian",
		"lt" to "Lithuanian",
		"lb" to "Luxembourgish",
		"mk" to "Macedonian",
		"mg" to "Malagasy",
		"ms" to "Malay",
		"ml" to "Malayalam",
		"mt" to "Maltese",
		"mi" to "Maori",
		"mr" to "Marathi",
		"mn" to "Mongolian",
		"my" to "Myanmar (Burmese)",
		"ne" to "Nepali",
		"no" to "Norwegian",
		"ny" to "Nyanja",
		"or" to "Odia",
		"ps" to "Pashto",
		"fa" to "Persian",
		"pl" to "Polish",
		"pt" to "Portuguese",
		"pa" to "Punjabi",
		"ro" to "Romanian",
		"ru" to "Russian",
		"sm" to "Samoan",
		"gd" to "Scots Gaelic",
		"sr" to "Serbian",
		"st" to "Sesotho",
		"sn" to "Shona",
		"sd" to "Sindhi",
		"si" to "Sinhala",
		"sk" to "Slovak",
		"sl" to "Slovenian",
		"so" to "Somali",
		"es" to "Spanish",
		"su" to "Sundanese",
		"sw" to "Swahili",
		"sv" to "Swedish",
		"tl" to "Tagalog / Filipino",
		"tg" to "Tajik",
		"ta" to "Tamil",
		"te" to "Telugu",
		"th" to "Thai",
		"tr" to "Turkish",
		"uk" to "Ukrainian",
		"ur" to "Urdu",
		"ug" to "Uyghur",
		"uz" to "Uzbek",
		"vi" to "Vietnamese",
		"cy" to "Welsh",
		"xh" to "Xhosa",
		"yi" to "Yiddish",
		"yo" to "Yoruba",
		"zu" to "Zulu",
	).map { NovelTranslationLanguage(it.first, it.second) }

	val byCode: Map<String, NovelTranslationLanguage> = common.associateBy { it.code.lowercase(Locale.ROOT) }

	fun displayName(code: String): String {
		if (code.equals(NovelTranslationSettings.LANGUAGE_AUTO, true)) return auto.name
		return byCode[code.lowercase(Locale.ROOT)]?.name ?: code
	}

	fun sourceChoices(): List<NovelTranslationLanguage> = listOf(auto) + common
	fun targetChoices(): List<NovelTranslationLanguage> = common
}
