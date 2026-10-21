@file:OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)

package org.miyorare.kotatsu.runtime

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Miyorare-owned protocol-v1 bridge around the pinned Kotatsu-Redo parser runtime. */
class KotatsuRuntimeEntrypoint(context: Context) {
	private val loader = RuntimeLoaderContext(context.applicationContext)
	private val parsers = ConcurrentHashMap<MangaParserSource, MangaParser>()

	fun protocolVersion(): Int = 1

	fun healthCheck(): Boolean {
		val sources = MangaParserSource.entries
		return sources.isNotEmpty() && sources.map { it.name }.toSet().size == sources.size && sources.any { !it.isBroken }
	}

	fun catalogJson(): String = JSONArray().apply {
		MangaParserSource.entries.forEach { source ->
			put(JSONObject().put("key", source.name).put("title", source.title).put("locale", source.locale)
				.put("contentType", source.contentType.name).put("broken", source.isBroken))
		}
	}.toString()

	fun getListJson(sourceKey: String, offset: Int, order: String, filterJson: String): String = runBlocking {
		val source = source(sourceKey)
		val parser = parser(source)
		val selectedOrder = runCatching { SortOrder.valueOf(order) }.getOrNull()?.takeIf { it in parser.availableSortOrders }
			?: parser.availableSortOrders.first()
		JSONArray(parser.getList(offset, selectedOrder, decodeFilter(JSONObject(filterJson), source)).map(::encodeManga)).toString()
	}

	fun getDetailsJson(sourceKey: String, mangaJson: String): String = runBlocking {
		val source = source(sourceKey)
		encodeManga(parser(source).getDetails(decodeManga(JSONObject(mangaJson), source))).toString()
	}

	fun getPagesJson(sourceKey: String, chapterJson: String): String = runBlocking {
		val source = source(sourceKey)
		JSONArray(parser(source).getPages(decodeChapter(JSONObject(chapterJson), source)).map(::encodePage)).toString()
	}

	fun getPageUrl(sourceKey: String, pageJson: String): String = runBlocking {
		val source = source(sourceKey)
		parser(source).getPageUrl(decodePage(JSONObject(pageJson), source))
	}

	fun getRelatedJson(sourceKey: String, mangaJson: String): String = runBlocking {
		val source = source(sourceKey)
		JSONArray(parser(source).getRelatedManga(decodeManga(JSONObject(mangaJson), source)).map(::encodeManga)).toString()
	}

	fun sortOrdersJson(sourceKey: String): String = JSONArray(parser(source(sourceKey)).availableSortOrders.map { it.name }).toString()

	fun filterCapabilitiesJson(sourceKey: String): String {
		val value = parser(source(sourceKey)).filterCapabilities
		return JSONObject()
			.put("multipleTags", value.isMultipleTagsSupported)
			.put("tagsExclusion", value.isTagsExclusionSupported)
			.put("search", value.isSearchSupported)
			.put("searchWithFilters", value.isSearchWithFiltersSupported)
			.put("year", value.isYearSupported)
			.put("yearRange", value.isYearRangeSupported)
			.put("originalLocale", value.isOriginalLocaleSupported)
			.put("author", value.isAuthorSearchSupported)
			.toString()
	}

	fun filterOptionsJson(sourceKey: String): String = runBlocking {
		val source = source(sourceKey)
		val value = parser(source).getFilterOptions()
		JSONObject()
			.put("tags", JSONArray(value.availableTags.map(::encodeTag)))
			.put("states", JSONArray(value.availableStates.map { it.name }))
			.put("contentRatings", JSONArray(value.availableContentRating.map { it.name }))
			.put("contentTypes", JSONArray(value.availableContentTypes.map { it.name }))
			.put("demographics", JSONArray(value.availableDemographics.map { it.name }))
			.put("locales", JSONArray(value.availableLocales.map { it.toLanguageTag() }))
			.toString()
	}

	fun sourceConfigJson(sourceKey: String): String {
		val source = source(sourceKey)
		val parser = parser(source)
		val keys = ArrayList<ConfigKey<*>>().also(parser::onCreateConfig)
		val config = loader.getConfig(source)
		return JSONObject().apply {
			for (key in keys) put(key.key, JSONObject.wrap(configValue(config, key)))
		}.toString()
	}

	fun setSourceConfigJson(sourceKey: String, valuesJson: String): String {
		val source = source(sourceKey)
		val parser = parser(source)
		val keys = ArrayList<ConfigKey<*>>().also(parser::onCreateConfig).associateBy { it.key }
		val values = JSONObject(valuesJson)
		for (name in values.keys()) {
			val key = keys[name] ?: continue
			loader.putConfig(source, key, coerceConfigValue(key, values.opt(name)))
		}
		return sourceConfigJson(sourceKey)
	}

	fun requestHeadersJson(sourceKey: String): String = JSONObject().apply {
		for ((name, value) in parser(source(sourceKey)).getRequestHeaders()) append(name, value)
	}.toString()

	private fun source(key: String): MangaParserSource = runCatching { MangaParserSource.valueOf(key) }
		.getOrElse { throw IllegalArgumentException("Unknown Kotatsu source: $key", it) }

	private fun parser(source: MangaParserSource): MangaParser = parsers.getOrPut(source) { loader.newParserInstance(source) }

	@Suppress("UNCHECKED_CAST")
	private fun configValue(config: org.koitharu.kotatsu.parsers.config.MangaSourceConfig, key: ConfigKey<*>): Any? =
		config[key as ConfigKey<Any?>]

	private fun coerceConfigValue(key: ConfigKey<*>, raw: Any?): Any? = when (key.defaultValue) {
		is Boolean -> raw as? Boolean ?: raw?.toString()?.toBooleanStrictOrNull()
		is Int -> (raw as? Number)?.toInt()
		is Long -> (raw as? Number)?.toLong()
		is Float -> (raw as? Number)?.toFloat()
		is String -> raw?.takeUnless { it == JSONObject.NULL }?.toString()
		null -> raw?.takeUnless { it == JSONObject.NULL }?.toString()
		else -> key.defaultValue
	}

	private fun decodeFilter(json: JSONObject, source: MangaParserSource): MangaListFilter = MangaListFilter(
		query = json.stringOrNull("query"),
		tags = json.optJSONArray("tags").objects().mapTo(LinkedHashSet()) { decodeTag(it, source) },
		tagsExclude = json.optJSONArray("tagsExclude").objects().mapTo(LinkedHashSet()) { decodeTag(it, source) },
		locale = json.stringOrNull("locale")?.let(Locale::forLanguageTag),
		originalLocale = json.stringOrNull("originalLocale")?.let(Locale::forLanguageTag),
		states = json.names("states", MangaState.entries),
		contentRating = json.names("contentRatings", ContentRating.entries),
		types = json.names("contentTypes", ContentType.entries),
		demographics = json.names("demographics", Demographic.entries),
		year = json.optInt("year", 0),
		yearFrom = json.optInt("yearFrom", 0),
		yearTo = json.optInt("yearTo", 0),
		author = json.stringOrNull("author"),
	)

	private fun encodeManga(value: Manga): JSONObject = JSONObject()
		.put("id", value.id).put("title", value.title).put("altTitles", JSONArray(value.altTitles))
		.put("url", value.url).put("publicUrl", value.publicUrl).put("rating", value.rating.toDouble())
		.put("contentRating", value.contentRating?.name).put("coverUrl", value.coverUrl)
		.put("largeCoverUrl", value.largeCoverUrl).put("tags", JSONArray(value.tags.map(::encodeTag)))
		.put("state", value.state?.name).put("authors", JSONArray(value.authors)).put("description", value.description)
		.put("chapters", value.chapters?.let { JSONArray(it.map(::encodeChapter)) } ?: JSONObject.NULL)

	private fun decodeManga(json: JSONObject, source: MangaParserSource): Manga = Manga(
		id = json.getLong("id"), title = json.getString("title"), altTitles = json.strings("altTitles"),
		url = json.getString("url"), publicUrl = json.optString("publicUrl", json.getString("url")),
		rating = json.optDouble("rating", -1.0).toFloat(),
		contentRating = json.stringOrNull("contentRating")?.let { runCatching { ContentRating.valueOf(it) }.getOrNull() },
		coverUrl = json.stringOrNull("coverUrl"), tags = json.optJSONArray("tags").objects().mapTo(LinkedHashSet()) { decodeTag(it, source) },
		state = json.stringOrNull("state")?.let { runCatching { MangaState.valueOf(it) }.getOrNull() },
		authors = json.strings("authors"), largeCoverUrl = json.stringOrNull("largeCoverUrl"), description = json.stringOrNull("description"),
		chapters = json.optJSONArray("chapters")?.objects()?.map { decodeChapter(it, source) }, source = source,
	)

	private fun encodeChapter(value: MangaChapter): JSONObject = JSONObject()
		.put("id", value.id).put("title", value.title).put("number", value.number.toDouble()).put("volume", value.volume)
		.put("url", value.url).put("scanlator", value.scanlator).put("uploadDate", value.uploadDate).put("branch", value.branch)

	private fun decodeChapter(json: JSONObject, source: MangaParserSource): MangaChapter = MangaChapter(
		id = json.getLong("id"), title = json.stringOrNull("title"), number = json.optDouble("number", 0.0).toFloat(),
		volume = json.optInt("volume", 0), url = json.getString("url"), scanlator = json.stringOrNull("scanlator"),
		uploadDate = json.optLong("uploadDate", 0L), branch = json.stringOrNull("branch"), source = source,
	)

	private fun encodePage(value: MangaPage): JSONObject = JSONObject().put("id", value.id).put("url", value.url).put("preview", value.preview)
	private fun decodePage(json: JSONObject, source: MangaParserSource): MangaPage = MangaPage(json.getLong("id"), json.getString("url"), json.stringOrNull("preview"), source)
	private fun encodeTag(value: MangaTag): JSONObject = JSONObject().put("title", value.title).put("key", value.key)
	private fun decodeTag(json: JSONObject, source: MangaParserSource): MangaTag = MangaTag(json.getString("title"), json.getString("key"), source)
}

private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.strings(key: String): Set<String> = optJSONArray(key)?.let { array ->
	buildSet { for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(::add) }
}.orEmpty()
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private inline fun <reified T : Enum<T>> JSONObject.names(key: String, entries: List<T>): Set<T> {
	val byName = entries.associateBy { it.name }
	return optJSONArray(key)?.let { array -> buildSet { for (i in 0 until array.length()) byName[array.optString(i)]?.let(::add) } }.orEmpty()
}
