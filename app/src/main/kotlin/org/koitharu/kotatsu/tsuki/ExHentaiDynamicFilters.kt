package org.koitharu.kotatsu.tsuki

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.koitharu.kotatsu.mihon.MihonFilterMapper
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Native dynamic filter model for the canonical Miyorare Global ExHentai source.
 *
 * The visual/filter hierarchy intentionally mirrors the long-standing E-Hentai Mihon/Tachiyomi
 * extension. The dynamic UI state is decoded in the host app and translated to semantic control
 * tags before it crosses the Tsuki ABI. The Gekkoushi overlay consumes only those control tags;
 * they must never be treated as gallery tags.
 */
internal object ExHentaiDynamicFilters {

	const val CONTROL_PREFIX = "__miyorare_exhentai__:"

	private const val CONTROL_FAVORITES = "path_favorites"
	private const val CONTROL_WATCHED = "path_watched"
	private const val CONTROL_QUERY_TAG = "q_tag"
	private const val CONTROL_QUERY_FEMALE = "q_female"
	private const val CONTROL_QUERY_MALE = "q_male"
	private const val CONTROL_QUERY_LANGUAGE = "q_language"

	private val genreParams = linkedMapOf(
		"Dōjinshi" to "f_doujinshi",
		"Manga" to "f_manga",
		"Artist CG" to "f_artistcg",
		"Game CG" to "f_gamecg",
		"Western" to "f_western",
		"Non-H" to "f_non-h",
		"Image Set" to "f_imageset",
		"Cosplay" to "f_cosplay",
		"Asian Porn" to "f_asianporn",
		"Misc" to "f_misc",
	)

	private val supportedLanguageTerms = mapOf(
		"ja" to "japanese",
		"en" to "english",
		"zh" to "chinese",
		"nl" to "dutch",
		"fr" to "french",
		"de" to "german",
		"hu" to "hungarian",
		"it" to "italian",
		"ko" to "korean",
		"pl" to "polish",
		"pt" to "portuguese",
		"ru" to "russian",
		"es" to "spanish",
		"th" to "thai",
		"vi" to "vietnamese",
	)

	private class Check(name: String, state: Boolean = false) : Filter.CheckBox(name, state)
	private class Text(name: String) : Filter.Text(name)
	private class Choice(name: String, values: Array<String>) : Filter.Select<String>(name, values)
	private class Group(name: String, filters: List<Filter<*>>) : Filter.Group<Filter<*>>(name, filters)

	fun create(): FilterList = FilterList(
		Check("Enforce language"),
		Check("Favorites"),
		Check("Watched List"),
		Group(
			"Genres",
			genreParams.keys.map { Check(it) },
		),
		Filter.Header("Separate tags with commas (,)"),
		Filter.Header("Prepend with dash (-) to exclude"),
		Filter.Header("Use 'Female Tags' or 'Male Tags' for specific categories. 'Tags' searches all categories."),
		Text("Tags"),
		Text("Female Tags"),
		Text("Male Tags"),
		Group(
			"Advanced Options",
			listOf(
				Check("Search Gallery Name", true),
				Check("Search Gallery Tags", true),
				Check("Search Gallery Description"),
				Check("Search Torrent Filenames"),
				Check("Only Show Galleries With Torrents"),
				Check("Search Low-Power Tags"),
				Check("Search Downvoted Tags"),
				Check("Show Expunged Galleries"),
				Choice("Minimum Rating", arrayOf("Any", "2 stars", "3 stars", "4 stars", "5 stars")),
				Text("Minimum Pages"),
				Text("Maximum Pages"),
			),
		),
	)

	fun toParserFilter(
		encoded: MangaListFilter,
		source: MangaSource,
		preferredLocale: Locale = Locale.getDefault(),
	): MangaListFilter {
		val filters = create()
		MihonFilterMapper.decode(filters, encoded)
		val controls = LinkedHashSet<MangaTag>()

		fun control(name: String, value: String) {
			controls += MangaTag(
				title = name,
				key = CONTROL_PREFIX + name + "=" + encode(value),
				source = source,
			)
		}

		val enforceLanguage = (filters[0] as Filter.CheckBox).state
		if (enforceLanguage) {
			supportedLanguageTerms[preferredLocale.language.lowercase(Locale.ROOT)]?.let {
				control(CONTROL_QUERY_LANGUAGE, it)
			}
		}
		if ((filters[1] as Filter.CheckBox).state) control(CONTROL_FAVORITES, "1")
		if ((filters[2] as Filter.CheckBox).state) control(CONTROL_WATCHED, "1")

		val genreGroup = filters[3] as Filter.Group<*>
		val genres = genreGroup.state.filterIsInstance<Filter.CheckBox>()
		val anyGenreSelected = genres.any { it.state }
		genres.forEach { option ->
			val parameter = genreParams[option.name] ?: return@forEach
			// E-Hentai's own extension treats "nothing selected" as "all categories selected".
			control(parameter, if (!anyGenreSelected || option.state) "1" else "0")
		}

		val tags = (filters[7] as Filter.Text).state.trim()
		val female = (filters[8] as Filter.Text).state.trim()
		val male = (filters[9] as Filter.Text).state.trim()
		if (tags.isNotEmpty()) control(CONTROL_QUERY_TAG, tags)
		if (female.isNotEmpty()) control(CONTROL_QUERY_FEMALE, female)
		if (male.isNotEmpty()) control(CONTROL_QUERY_MALE, male)

		val advanced = (filters[10] as Filter.Group<*>).state.filterIsInstance<Filter<*>>()
		val advancedParams = listOf(
			"f_sname",
			"f_stags",
			"f_sdesc",
			"f_storr",
			"f_sto",
			"f_sdt1",
			"f_sdt2",
			"f_sh",
		)
		advanced.take(8).forEachIndexed { index, item ->
			if ((item as Filter.CheckBox).state) control(advancedParams[index], "on")
		}
		val rating = (advanced[8] as Filter.Select<*>).state
		if (rating > 0) {
			control("f_sr", "on")
			control("f_srdd", (rating + 1).toString())
		}
		val minimumPages = (advanced[9] as Filter.Text).state.trim()
		val maximumPages = (advanced[10] as Filter.Text).state.trim()
		if (minimumPages.isNotEmpty() || maximumPages.isNotEmpty()) {
			control("f_sp", "on")
			if (minimumPages.isNotEmpty()) control("f_spf", minimumPages)
			if (maximumPages.isNotEmpty()) control("f_spt", maximumPages)
		}

		return MangaListFilter(
			query = encoded.query,
			tags = controls,
		)
	}

	private fun encode(value: String): String =
		URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
