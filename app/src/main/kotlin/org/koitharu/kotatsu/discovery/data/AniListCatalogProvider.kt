package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.data.array
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.text
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.time.LocalDate
import java.time.ZoneOffset

internal const val ANILIST_CATALOG_CARD_FIELDS = "id type format isAdult title{userPreferred romaji native} coverImage{large} siteUrl averageScore"

class AniListCatalogProvider(private val http: CatalogHttp) : TrackerCatalogProvider {
	override val catalogService = ScrobblerService.ANILIST
	override val catalogCapabilities = CatalogCapability.entries.toSet()
	private val sectionTypes = listOf(CatalogSectionType.TRENDING, CatalogSectionType.RECENT, CatalogSectionType.POPULAR, CatalogSectionType.TOP_RATED)

	internal fun pageQuery(key: CatalogSectionKey, allowAdult: Boolean, page: Int, limit: Int): String {
		require(key.service == catalogService && key.type in sectionTypes)
		catalogPageNumber(page)
		val sort = when (key.type) {
			CatalogSectionType.TRENDING -> "TRENDING_DESC"
			CatalogSectionType.RECENT -> "START_DATE_DESC"
			CatalogSectionType.POPULAR -> "POPULARITY_DESC"
			CatalogSectionType.TOP_RATED -> "SCORE_DESC"
			else -> error("Unsupported section")
		}
		val format = if (key.kind == CatalogMediaKind.NOVEL) "format:NOVEL" else "format_not:NOVEL"
		val adult = if (allowAdult) "" else ",isAdult:false"
		val date = if (key.type == CatalogSectionType.RECENT) ",startDate_lesser:${LocalDate.now(ZoneOffset.UTC).plusDays(1).toString().replace("-", "")}" else ""
		return "Page(page:$page,perPage:$limit){pageInfo{hasNextPage} media(type:MANGA,$format,sort:$sort$adult$date){$ANILIST_CATALOG_CARD_FIELDS}}"
	}

	override suspend fun home(filter: CatalogFilter, allowAdult: Boolean): TrackerCatalogHome {
		val keys = filter.kinds.flatMap { kind -> sectionTypes.map { CatalogSectionKey(catalogService, kind, it) } }
		val query = keys.mapIndexed { index, key -> "s$index:${pageQuery(key, allowAdult, 1, CatalogCachePolicy.HOME_PAGE_SIZE)}" }.joinToString(" ")
		val root = request("{$query}")
		val data = root.obj("data") ?: throw CatalogException(CatalogFailure(CatalogFailureKind.PROVIDER))
		return TrackerCatalogHome(keys.mapIndexed { index, key ->
			try {
				val page = parsePage(data.obj("s$index") ?: invalidCatalog(), allowAdult, 1)
				TrackerCatalogSection(key, page.items.filter { it.key.kind == key.kind }, page.nextPage)
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { TrackerCatalogSection(key, emptyList(), failure = e.catalogFailure()) }
		})
	}

	override suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int): TrackerCatalogPage {
		val root = request("{${pageQuery(key, allowAdult, page, CatalogCachePolicy.PAGE_SIZE)}}")
		return parsePage(root.obj("data")?.obj("Page") ?: invalidCatalog(), allowAdult, page).let { it.copy(items = it.items.filter { item -> item.key.kind == key.kind }) }
	}

	override suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int): TrackerCatalogPage {
		catalogPageNumber(page)
		val format = when (filter) { CatalogFilter.ALL -> ""; CatalogFilter.NOVEL -> ",format:NOVEL"; CatalogFilter.MANGA -> ",format_not:NOVEL" }
		val adult = if (allowAdult) "" else ",isAdult:false"
		val root = request("{Page(page:$page,perPage:${CatalogCachePolicy.PAGE_SIZE}){pageInfo{hasNextPage} media(type:MANGA,search:${JsonPrimitive(query.take(128))}$format$adult,sort:SEARCH_MATCH){$ANILIST_CATALOG_CARD_FIELDS}}}")
		return parsePage(root.obj("data")?.obj("Page") ?: invalidCatalog(), allowAdult, page).let { it.copy(items = it.items.filter { item -> item.key.kind in filter.kinds }) }
	}

	override suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean): TrackerCatalogDetails {
		require(key.service == catalogService && key.remoteId.toIntOrNull() != null)
		val root = request("{Media(id:${key.remoteId},type:MANGA){$ANILIST_CATALOG_CARD_FIELDS synonyms description(asHtml:false) bannerImage genres status popularity startDate{year month day} staff(perPage:12){edges{role node{name{full}}}} recommendations(perPage:10,sort:RATING_DESC){nodes{mediaRecommendation{$ANILIST_CATALOG_CARD_FIELDS}}}}}")
		val media = root.obj("data")?.obj("Media") ?: invalidCatalog()
		val item = parseAniListCard(media, allowAdult) ?: throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
		if (item.key != key) invalidCatalog()
		val names = media.obj("title")?.values.orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
		val staff = media.obj("staff")?.array("edges").orEmpty().mapNotNull { edge ->
			val value = edge as? JsonObject ?: return@mapNotNull null
			value.obj("node")?.obj("name")?.text("full")?.let { name -> listOfNotNull(name, value.text("role")).joinToString(" · ") }
		}
		val date = media.obj("startDate")?.let { d ->
			val year = d.catalogInt("year")?.takeIf { it > 0 }
			val month = d.catalogInt("month")?.takeIf { it in 1..12 }
			val day = d.catalogInt("day")?.takeIf { it in 1..31 }
			year?.toString()?.let { y -> if (month == null) y else "%s-%02d".format(java.util.Locale.ROOT, y, month) + (day?.let { "-%02d".format(java.util.Locale.ROOT, it) } ?: "") }
		}
		var partial = !root.array("errors").isNullOrEmpty()
		val recommendations = try { media.obj("recommendations")?.array("nodes")?.let { rows -> catalogRows(rows) { row -> row.obj("mediaRecommendation")?.let { parseAniListCard(it, allowAdult) } } }.orEmpty() } catch (_: CatalogException) { partial = true; emptyList() }
		return TrackerCatalogDetails(item, (names + media.catalogStrings("synonyms")).distinct(), media.text("description"), if (item.adult != null || allowAdult) catalogUrl(media.text("bannerImage")) else null,
			media.catalogStrings("genres"), staff, media.text("status"), date, popularity = media.catalogInt("popularity"), recommendations = recommendations, partial = partial)
	}

	private fun parsePage(root: JsonObject, allowAdult: Boolean, page: Int): TrackerCatalogPage = TrackerCatalogPage(
		catalogRows(root.array("media") ?: invalidCatalog()) { parseAniListCard(it, allowAdult) },
		(page + 1).takeIf { page < CatalogCachePolicy.MAX_PAGE && root.obj("pageInfo")?.catalogBoolean("hasNextPage") == true },
	)

	private suspend fun request(query: String): JsonObject = http.request(catalogService,
		Request.Builder().url("https://graphql.anilist.co").post(buildJsonObject { put("query", JsonPrimitive(query)) }.toString().toRequestBody("application/json".toMediaType())).build())
}
