@file:OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)

package org.koitharu.kotatsu.kotatsu

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import java.util.EnumSet
import java.util.Locale

internal fun Manga.toKotatsuRuntimeJson(): String = JSONObject()
	.put("id", id).put("title", title).put("altTitles", JSONArray(altTitles)).put("url", url)
	.put("publicUrl", publicUrl).put("rating", rating.toDouble()).put("contentRating", contentRating?.name)
	.put("coverUrl", coverUrl).put("largeCoverUrl", largeCoverUrl).put("tags", JSONArray(tags.map { it.toRuntimeTag() }))
	.put("state", state?.name).put("authors", JSONArray(authors)).put("description", description)
	.put("chapters", chapters?.let { JSONArray(it.map(MangaChapter::toRuntimeChapter)) } ?: JSONObject.NULL)
	.toString()

internal fun MangaChapter.toKotatsuRuntimeJson(): String = toRuntimeChapter().toString()
internal fun MangaPage.toKotatsuRuntimeJson(): String = JSONObject().put("id", id).put("url", url).put("preview", preview).toString()

internal fun MangaListFilter?.toKotatsuRuntimeJson(): String {
	val value = this ?: MangaListFilter.EMPTY
	return JSONObject()
		.put("query", value.query)
		.put("tags", JSONArray(value.tags.map { it.toRuntimeTag() }))
		.put("tagsExclude", JSONArray(value.tagsExclude.map { it.toRuntimeTag() }))
		.put("locale", value.locale?.toLanguageTag())
		.put("originalLocale", value.originalLocale?.toLanguageTag())
		.put("states", JSONArray(value.states.map { it.name }))
		.put("contentRatings", JSONArray(value.contentRating.map { it.name }))
		.put("contentTypes", JSONArray(value.types.map { it.name }))
		.put("demographics", JSONArray(value.demographics.map { it.name }))
		.put("year", value.year).put("yearFrom", value.yearFrom).put("yearTo", value.yearTo)
		.put("author", value.author).toString()
}

internal fun decodeRuntimeMangaList(json: String, source: KotatsuMangaSource): List<Manga> =
	JSONArray(json).objects().map { it.toManga(source) }

internal fun decodeRuntimeManga(json: String, source: KotatsuMangaSource): Manga = JSONObject(json).toManga(source)
internal fun decodeRuntimePages(json: String, source: KotatsuMangaSource): List<MangaPage> =
	JSONArray(json).objects().map { MangaPage(it.getLong("id"), it.getString("url"), it.stringOrNull("preview"), source) }

internal fun JSONObject.toRuntimeCapabilities(): MangaListFilterCapabilities = MangaListFilterCapabilities(
	isMultipleTagsSupported = optBoolean("multipleTags"),
	isTagsExclusionSupported = optBoolean("tagsExclusion"),
	isSearchSupported = optBoolean("search"),
	isSearchWithFiltersSupported = optBoolean("searchWithFilters"),
	isYearSupported = optBoolean("year"),
	isYearRangeSupported = optBoolean("yearRange"),
	isOriginalLocaleSupported = optBoolean("originalLocale"),
	isAuthorSearchSupported = optBoolean("author"),
)

internal fun JSONObject.toRuntimeOptions(source: KotatsuMangaSource): MangaListFilterOptions = MangaListFilterOptions(
	availableTags = optJSONArray("tags").objects().mapTo(LinkedHashSet()) { it.toTag(source) },
	availableStates = enumSet(optJSONArray("states"), MangaState::valueOf),
	availableContentRating = enumSet(optJSONArray("contentRatings"), ContentRating::valueOf),
	availableContentTypes = enumSet(optJSONArray("contentTypes"), ContentType::valueOf),
	availableDemographics = enumSet(optJSONArray("demographics"), Demographic::valueOf),
	availableLocales = optJSONArray("locales")?.let { array ->
		buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let { add(Locale.forLanguageTag(it)) } }
	}.orEmpty(),
)

private fun MangaChapter.toRuntimeChapter(): JSONObject = JSONObject()
	.put("id", id).put("title", title).put("number", number.toDouble()).put("volume", volume)
	.put("url", url).put("scanlator", scanlator).put("uploadDate", uploadDate).put("branch", branch)

private fun MangaTag.toRuntimeTag(): JSONObject = JSONObject().put("title", title).put("key", key)
private fun JSONObject.toTag(source: KotatsuMangaSource): MangaTag = MangaTag(getString("title"), getString("key"), source)

private fun JSONObject.toManga(source: KotatsuMangaSource): Manga = Manga(
	id = getLong("id"), title = getString("title"), altTitles = strings("altTitles"), url = getString("url"),
	publicUrl = optString("publicUrl", getString("url")), rating = optDouble("rating", -1.0).toFloat(),
	contentRating = stringOrNull("contentRating")?.let { runCatching { ContentRating.valueOf(it) }.getOrNull() },
	coverUrl = stringOrNull("coverUrl"), tags = optJSONArray("tags").objects().mapTo(LinkedHashSet()) { it.toTag(source) },
	state = stringOrNull("state")?.let { runCatching { MangaState.valueOf(it) }.getOrNull() },
	authors = strings("authors"), largeCoverUrl = stringOrNull("largeCoverUrl"), description = stringOrNull("description"),
	chapters = optJSONArray("chapters")?.objects()?.map { it.toChapter(source) }, source = source,
)

private fun JSONObject.toChapter(source: KotatsuMangaSource): MangaChapter = MangaChapter(
	id = getLong("id"), title = stringOrNull("title"), number = optDouble("number", 0.0).toFloat(),
	volume = optInt("volume", 0), url = getString("url"), scanlator = stringOrNull("scanlator"),
	uploadDate = optLong("uploadDate", 0L), branch = stringOrNull("branch"), source = source,
)

private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.strings(key: String): Set<String> = optJSONArray(key)?.let { array ->
	buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
}.orEmpty()
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun <T : Enum<T>> enumSet(array: JSONArray?, parser: (String) -> T): Set<T> {
	if (array == null || array.length() == 0) return emptySet()
	val values = array.objectsOrStrings().mapNotNull { runCatching { parser(it) }.getOrNull() }
	return if (values.isEmpty()) emptySet() else EnumSet.copyOf(values)
}
private fun JSONArray.objectsOrStrings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
