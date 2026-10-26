package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.koitharu.kotatsu.discovery.domain.CatalogCapability
import org.koitharu.kotatsu.discovery.domain.CatalogCachePolicy
import org.koitharu.kotatsu.discovery.domain.CatalogException
import org.koitharu.kotatsu.discovery.domain.CatalogFailure
import org.koitharu.kotatsu.discovery.domain.CatalogFailureKind
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogSectionKey
import org.koitharu.kotatsu.discovery.domain.CatalogSectionType
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogDetails
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogHome
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogPage
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogPerson
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogProvider
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogRelatedWork
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogSection
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.catalogFailure
import org.koitharu.kotatsu.scrobbling.common.data.array
import org.koitharu.kotatsu.scrobbling.common.data.id
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.text
import org.koitharu.kotatsu.scrobbling.common.data.trackerRelationType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

private const val MANGABAKA_API_V2 = "https://api.mangabaka.org/v2"
private const val MANGABAKA_RELATION_LIMIT = 40
private const val MANGABAKA_RECOMMENDATION_LIMIT = 10
private const val MANGABAKA_DISCOVERY_FILL_PAGES = 4
private const val MANGABAKA_DISCOVERY_FILL_PAGE_SIZE = 24

/** Public, credential-free MangaBaka catalogue for Tracker Home. */
class MangaBakaCatalogProvider(private val http: CatalogHttp) : TrackerCatalogProvider {
	override val catalogService = ScrobblerService.MANGABAKA
	override val catalogCapabilities = setOf(
		CatalogCapability.HOME,
		CatalogCapability.SEARCH,
		CatalogCapability.DETAILS,
		CatalogCapability.SECTION_PAGING,
		CatalogCapability.MANGA,
		CatalogCapability.NOVEL,
		CatalogCapability.STAFF,
		CatalogCapability.RECOMMENDATIONS,
		CatalogCapability.RELATIONS,
	)

	private val sectionTypes = listOf(
		CatalogSectionType.RISING,
		CatalogSectionType.HIDDEN_GEMS,
		CatalogSectionType.NEW_RELEASES,
	)

	override suspend fun home(filter: CatalogFilter, allowAdult: Boolean): TrackerCatalogHome = coroutineScope {
		val kind = when (filter) {
			CatalogFilter.ALL -> CatalogMediaKind.MIXED
			CatalogFilter.MANGA -> CatalogMediaKind.MANGA
			CatalogFilter.NOVEL -> CatalogMediaKind.NOVEL
		}
		val keys = sectionTypes.map { CatalogSectionKey(catalogService, kind, it) }
		val sections = keys.map { key ->
			async {
				try {
					val page = listing(key, allowAdult, 1, CatalogCachePolicy.HOME_PAGE_SIZE, fillHome = true)
					TrackerCatalogSection(key, page.items.take(CatalogCachePolicy.HOME_PAGE_SIZE), page.nextPage)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					TrackerCatalogSection(key, emptyList(), failure = e.catalogFailure())
				}
			}
		}.map { it.await() }
		TrackerCatalogHome(sections)
	}

	override suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int): TrackerCatalogPage {
		require(key.service == catalogService && key.type in sectionTypes)
		catalogPageNumber(page)
		return listing(key, allowAdult, page, CatalogCachePolicy.PAGE_SIZE, fillHome = false)
	}

	override suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int): TrackerCatalogPage {
		catalogPageNumber(page)
		val url = seriesListUrl("$MANGABAKA_API_V2/series/search", filter, allowAdult, page, CatalogCachePolicy.PAGE_SIZE)
			.addQueryParameter("q", query.take(128))
			.build()
		return parsePage(request(url), filter, allowAdult, page, CatalogCachePolicy.PAGE_SIZE)
	}

	override suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean): TrackerCatalogDetails = coroutineScope {
		require(key.service == catalogService)
		val detailsRoot = request(
			"$MANGABAKA_API_V2/series/${key.remoteId}".toHttpUrl().newBuilder()
				.addQueryParameter("schema", "full")
				.build(),
		)
		val series = detailsRoot.obj("data") ?: invalidCatalog()
		val item = parseMangaBakaCatalogCard(series, allowAdult)
			?: throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
		if (item.key != key) invalidCatalog()

		val relationDeferred = async {
			try {
				CatalogSupplementalResult(loadRelations(series, key, allowAdult))
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				CatalogSupplementalResult(emptyList(), partial = true)
			}
		}
		val recommendationDeferred = async {
			try {
				CatalogSupplementalResult(loadRecommendations(key, allowAdult))
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				CatalogSupplementalResult(emptyList(), partial = true)
			}
		}

		val relations = relationDeferred.await()
		val recommendations = recommendationDeferred.await()
		val people = mangaBakaCatalogStaff(series)
		TrackerCatalogDetails(
			item = item,
			alternativeTitles = mangaBakaCatalogTitles(series).filterNot { it == item.title },
			description = series.text("description"),
			genres = mangaBakaCatalogGenres(series),
			staff = people.map { (listOf(it.name) + it.roles).joinToString(" · ") },
			status = series.text("status"),
			startDate = series.obj("published")?.text("start_date"),
			recommendations = recommendations.items,
			partial = relations.partial || recommendations.partial,
			characters = emptyList(),
			staffPeople = people,
			relatedWorks = relations.items,
		)
	}

	private suspend fun listing(
		key: CatalogSectionKey,
		allowAdult: Boolean,
		page: Int,
		limit: Int,
		fillHome: Boolean,
	): TrackerCatalogPage {
		val filter = when (key.kind) {
			CatalogMediaKind.MIXED -> CatalogFilter.ALL
			CatalogMediaKind.NOVEL -> CatalogFilter.NOVEL
			CatalogMediaKind.MANGA -> CatalogFilter.MANGA
		}
		return when (key.type) {
			CatalogSectionType.RISING -> discoverPage(
				"$MANGABAKA_API_V2/series/discover/rising",
				filter,
				allowAdult,
				page,
				limit,
				fillHome,
			)
			CatalogSectionType.HIDDEN_GEMS -> discoverPage(
				"$MANGABAKA_API_V2/series/discover/hidden-gems",
				filter,
				allowAdult,
				page,
				limit,
				fillHome,
			)
			CatalogSectionType.NEW_RELEASES -> {
				val url = seriesListUrl("$MANGABAKA_API_V2/series/search", filter, allowAdult, page, limit)
					.addQueryParameter("sort_by", "latest")
					.build()
				parsePage(request(url), filter, allowAdult, page, limit)
			}
			else -> throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
		}
	}

	/**
	 * MangaBaka's Rising and Hidden Gems endpoints do not advertise type/type_not filters. For the
	 * small Home preview we therefore fill from a bounded number of provider pages and post-filter.
	 * Section paging stays one provider page per requested page so it never skips or repeats rows.
	 */
	private suspend fun discoverPage(
		base: String,
		filter: CatalogFilter,
		allowAdult: Boolean,
		page: Int,
		limit: Int,
		fillHome: Boolean,
	): TrackerCatalogPage {
		if (!fillHome || filter == CatalogFilter.ALL) {
			val url = discoveryListUrl(base, allowAdult, page, limit).build()
			return parsePage(request(url), filter, allowAdult, page, limit)
		}
		val items = LinkedHashMap<String, TrackerCatalogItem>()
		var hasNext = false
		var sourcePage = 1
		while (sourcePage <= MANGABAKA_DISCOVERY_FILL_PAGES && items.size < limit) {
			val url = discoveryListUrl(base, allowAdult, sourcePage, MANGABAKA_DISCOVERY_FILL_PAGE_SIZE).build()
			val root = request(url)
			val rows = root.array("data") ?: invalidCatalog()
			catalogRows(rows) { row ->
				val series = row.obj("series") ?: row
				parseMangaBakaCatalogCard(series, allowAdult)?.takeIf { it.key.kind in filter.kinds }
			}.forEach { items.putIfAbsent(it.key.stableId, it) }
			hasNext = mangaBakaHasNext(root.obj("pagination"), sourcePage, rows.size, MANGABAKA_DISCOVERY_FILL_PAGE_SIZE)
			if (!hasNext) break
			sourcePage++
		}
		return TrackerCatalogPage(items.values.take(limit), nextPage = if (hasNext) 2 else null)
	}

	private fun seriesListUrl(
		base: String,
		filter: CatalogFilter,
		allowAdult: Boolean,
		page: Int,
		limit: Int,
	): HttpUrl.Builder = discoveryListUrl(base, allowAdult, page, limit).apply {
		when (filter) {
			CatalogFilter.ALL -> Unit
			CatalogFilter.NOVEL -> addQueryParameter("type", "novel")
			CatalogFilter.MANGA -> addQueryParameter("type_not", "novel")
		}
	}

	private fun discoveryListUrl(
		base: String,
		allowAdult: Boolean,
		page: Int,
		limit: Int,
	): HttpUrl.Builder = base.toHttpUrl().newBuilder().apply {
		addQueryParameter("page", page.toString())
		addQueryParameter("limit", limit.toString())
		addQueryParameter("content_rating", "safe")
		addQueryParameter("content_rating", "suggestive")
		if (allowAdult) {
			addQueryParameter("content_rating", "erotica")
			addQueryParameter("content_rating", "pornographic")
		}
	}

	private suspend fun loadRecommendations(key: TrackerCatalogKey, allowAdult: Boolean): List<TrackerCatalogItem> {
		val root = request(
			"$MANGABAKA_API_V2/series/${key.remoteId}/readers-also-like".toHttpUrl().newBuilder()
				.addQueryParameter("limit", MANGABAKA_RECOMMENDATION_LIMIT.toString())
				.build(),
		)
		return root.array("data").orEmpty().mapNotNull { raw ->
			val row = raw as? JsonObject ?: return@mapNotNull null
			val series = row.obj("series") ?: row
			parseMangaBakaCatalogCard(series, allowAdult)?.takeIf { it.key != key }
		}.distinctBy { it.key }
	}

	private suspend fun loadRelations(
		series: JsonObject,
		key: TrackerCatalogKey,
		allowAdult: Boolean,
	): List<TrackerCatalogRelatedWork> {
		val edges = series.array("relationships").orEmpty().filterIsInstance<JsonObject>().take(MANGABAKA_RELATION_LIMIT)
		if (edges.isEmpty()) return emptyList()
		val ids = edges.mapNotNull { it.id("to_series_id") }.distinct()
		if (ids.isEmpty()) return emptyList()
		val batchUrl = "$MANGABAKA_API_V2/series/batch".toHttpUrl().newBuilder().apply {
			ids.forEach { addQueryParameter("id", it) }
		}.build()
		val related = request(batchUrl).array("data").orEmpty().filterIsInstance<JsonObject>()
			.mapNotNull { row -> row.id()?.let { it to row } }
			.toMap()
		return edges.mapNotNull { edge ->
			val id = edge.id("to_series_id") ?: return@mapNotNull null
			if (id == key.remoteId) return@mapNotNull null
			val destination = related[id] ?: return@mapNotNull null
			mangaBakaCatalogRelatedWork(destination, edge.text("relation_type"), allowAdult)
		}.distinctBy { "${it.mediaKind}:${it.remoteId}:${it.relation}" }
	}

	private fun parsePage(
		root: JsonObject,
		filter: CatalogFilter,
		allowAdult: Boolean,
		page: Int,
		limit: Int,
	): TrackerCatalogPage {
		val rows = root.array("data") ?: invalidCatalog()
		val items = catalogRows(rows) { row ->
			val series = row.obj("series") ?: row
			parseMangaBakaCatalogCard(series, allowAdult)?.takeIf { it.key.kind in filter.kinds }
		}
		val hasNext = mangaBakaHasNext(root.obj("pagination"), page, rows.size, limit)
		return TrackerCatalogPage(items, (page + 1).takeIf { page < CatalogCachePolicy.MAX_PAGE && hasNext })
	}

	private suspend fun request(url: HttpUrl): JsonObject =
		http.request(catalogService, Request.Builder().url(url).get().build())
}

internal fun mangaBakaHasNext(pagination: JsonObject?, page: Int, rowCount: Int, limit: Int): Boolean {
	if (pagination == null) return rowCount >= limit
	val next = pagination["next"] as? JsonPrimitive
	next?.booleanOrNull?.let { return it }
	next?.intOrNull?.let { return it > page }
	next?.contentOrNull?.trim()?.takeIf {
		it.isNotEmpty() && !it.equals("null", ignoreCase = true) && !it.equals("false", ignoreCase = true)
	}?.let { return true }
	pagination.catalogInt("next_page")?.let { return it > page }
	val current = pagination.catalogInt("page")
	val pages = pagination.catalogInt("pages")
	return current != null && pages != null && current < pages
}

internal fun parseMangaBakaCatalogCard(root: JsonObject, allowAdult: Boolean): TrackerCatalogItem? {
	val type = root.text("type")?.lowercase() ?: invalidCatalog()
	val kind = when (type) {
		"novel" -> CatalogMediaKind.NOVEL
		"manga", "manhwa", "manhua", "oel", "other" -> CatalogMediaKind.MANGA
		else -> return null
	}
	val rating = when (root.text("content_rating")?.lowercase()) {
		"safe", "suggestive" -> false
		"erotica", "pornographic" -> true
		else -> null
	}
	val id = root.catalogId()
	return TrackerCatalogItem(
		key = TrackerCatalogKey(ScrobblerService.MANGABAKA, kind, id),
		title = mangaBakaCatalogTitle(root) ?: invalidCatalog(),
		coverUrl = mangaBakaCatalogCover(root),
		providerUrl = catalogUrl(root.text("canonical_url")) ?: "https://mangabaka.org/$id",
		score = root.catalogDouble("rating")?.takeIf { it in 0.0..100.0 }?.div(10.0),
		adult = rating,
		providerType = type,
	).forPolicy(allowAdult)
}

internal fun mangaBakaCatalogStaff(series: JsonObject): List<TrackerCatalogPerson> {
	val result = ArrayList<TrackerCatalogPerson>()
	mangaBakaStringArray(series, "authors").forEach { name ->
		result += TrackerCatalogPerson(name = name, roles = listOf("Author"))
	}
	mangaBakaStringArray(series, "artists").forEach { name ->
		result += TrackerCatalogPerson(name = name, roles = listOf("Artist"))
	}
	return result.distinctBy { "${it.name}|${it.roles.joinToString()}" }
}

internal fun mangaBakaCatalogRelatedWork(
	series: JsonObject,
	rawRelation: String?,
	allowAdult: Boolean,
): TrackerCatalogRelatedWork? {
	val card = parseMangaBakaCatalogCard(series, allowAdult) ?: return null
	val type = series.text("type")?.lowercase()
	val mediaKind = when (type) {
		"novel" -> CatalogRelatedMediaKind.NOVEL
		"manga", "manhwa", "manhua", "oel" -> CatalogRelatedMediaKind.MANGA
		else -> CatalogRelatedMediaKind.OTHER
	}
	return TrackerCatalogRelatedWork(
		service = ScrobblerService.MANGABAKA,
		remoteId = card.key.remoteId,
		title = card.title,
		coverUrl = card.coverUrl,
		providerUrl = card.providerUrl,
		relation = trackerRelationType(rawRelation),
		mediaKind = mediaKind,
		format = type,
		providerRelation = rawRelation,
	)
}

internal fun mangaBakaCatalogTitles(series: JsonObject): List<String> {
	val result = ArrayList<String>()
	series.array("titles").orEmpty().filterIsInstance<JsonObject>().forEach { title ->
		title.text("title")?.takeIf(String::isNotBlank)?.let(result::add)
	}
	listOf("title", "romanized_title", "native_title").forEach { key ->
		series.text(key)?.takeIf(String::isNotBlank)?.let(result::add)
	}
	return result.distinct()
}

private fun mangaBakaCatalogTitle(series: JsonObject): String? {
	val titles = series.array("titles").orEmpty().filterIsInstance<JsonObject>()
	fun primary(item: JsonObject): Boolean = (item["is_primary"] as? JsonPrimitive)?.booleanOrNull == true
	fun first(language: String, primaryOnly: Boolean): String? = titles.firstOrNull { item ->
		item.text("language")?.equals(language, ignoreCase = true) == true && (!primaryOnly || primary(item))
	}?.text("title")
	return first("en", true)
		?: first("en", false)
		?: first("ja-Latn", true)
		?: first("ja-Latn", false)
		?: titles.firstOrNull(::primary)?.text("title")
		?: titles.firstOrNull()?.text("title")
		?: series.text("title")
		?: series.text("romanized_title")
		?: series.text("native_title")
}

private fun mangaBakaCatalogCover(series: JsonObject): String? = series.obj("cover")?.let { cover ->
	catalogUrl(cover.text("x350") ?: cover.text("x250") ?: cover.text("x150") ?: cover.text("raw"))
}

private fun mangaBakaCatalogGenres(series: JsonObject): List<String> = series.array("tags").orEmpty().mapNotNull { raw ->
	val tag = raw as? JsonObject ?: return@mapNotNull null
	val isGenre = (tag["is_genre"] as? JsonPrimitive)?.booleanOrNull
	if (isGenre != true) return@mapNotNull null
	tag.text("name") ?: tag.text("title")
}.distinct().take(30)

private fun mangaBakaStringArray(series: JsonObject, key: String): List<String> =
	(series[key] as? JsonArray).orEmpty().mapNotNull { raw ->
		(raw as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
	}
