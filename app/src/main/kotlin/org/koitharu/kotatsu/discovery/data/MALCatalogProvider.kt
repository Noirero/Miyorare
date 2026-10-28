package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.data.array
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.text
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

internal const val MAL_CATALOG_FIELDS = "media_type,nsfw,mean"

class MALCatalogProvider(private val http: CatalogHttp, private val clientId: String) : TrackerCatalogProvider {
	override val catalogService = ScrobblerService.MAL
	override val catalogCapabilities = setOf(
		CatalogCapability.HOME,
		CatalogCapability.SEARCH,
		CatalogCapability.DETAILS,
		CatalogCapability.SECTION_PAGING,
		CatalogCapability.MANGA,
		CatalogCapability.NOVEL,
		CatalogCapability.STAFF,
		CatalogCapability.RECOMMENDATIONS,
		CatalogCapability.CHARACTERS,
		CatalogCapability.RELATIONS,
	)
	private val sectionTypes = listOf(CatalogSectionType.TOP_RANKED, CatalogSectionType.POPULAR, CatalogSectionType.MOST_FAVORITED)

	internal fun rankingType(key: CatalogSectionKey): String = when (key.type) {
		CatalogSectionType.TOP_RANKED -> if (key.kind == CatalogMediaKind.NOVEL) "novels" else "manga"
		CatalogSectionType.POPULAR -> "bypopularity"
		CatalogSectionType.MOST_FAVORITED -> "favorite"
		else -> throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
	}

	override suspend fun home(filter: CatalogFilter, allowAdult: Boolean): TrackerCatalogHome = coroutineScope {
		val keys = filter.kinds.flatMap { kind -> sectionTypes.map { CatalogSectionKey(catalogService, kind, it) } }
		TrackerCatalogHome(keys.map { key -> async {
			try {
				val page = ranking(key, allowAdult, 1, CatalogCachePolicy.HOME_PAGE_SIZE)
				TrackerCatalogSection(key, page.items, page.nextPage)
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { TrackerCatalogSection(key, emptyList(), failure = e.catalogFailure()) }
		} }.awaitAll())
	}

	override suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int): TrackerCatalogPage =
		ranking(key, allowAdult, page, CatalogCachePolicy.PAGE_SIZE)

	private suspend fun ranking(key: CatalogSectionKey, allowAdult: Boolean, page: Int, size: Int): TrackerCatalogPage {
		require(key.service == catalogService)
		catalogPageNumber(page)
		val url = url("manga/ranking", allowAdult, page, size).addQueryParameter("ranking_type", rankingType(key)).build()
		return parsePage(request(url), allowAdult, page, setOf(key.kind))
	}

	override suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int): TrackerCatalogPage {
		catalogPageNumber(page)
		val url = url("manga", allowAdult, page, CatalogCachePolicy.PAGE_SIZE).addQueryParameter("q", query.take(64)).build()
		return parsePage(request(url), allowAdult, page, filter.kinds.toSet())
	}

	override suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean): TrackerCatalogDetails {
		require(key.service == catalogService)
		val fields = "$MAL_CATALOG_FIELDS,alternative_titles,synopsis,rank,popularity,num_list_users,status,start_date,end_date,num_chapters,num_volumes,genres," +
			"authors{node{first_name,last_name},role},serialization{name}," +
			"recommendations{node{id,title,media_type,nsfw,mean,main_picture}}," +
			"related_manga{node{id,title,media_type,nsfw,main_picture},relation_type,relation_type_formatted}," +
			"related_anime{node{id,title,nsfw,main_picture},relation_type,relation_type_formatted}"
		val root = request("https://api.myanimelist.net/v2/manga/${key.remoteId}".toHttpUrl().newBuilder().addQueryParameter("fields", fields).build())
		val item = parseMALCard(root, allowAdult) ?: throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
		if (item.key != key) invalidCatalog()
		val alternatives = root.obj("alternative_titles")?.let { titles -> listOfNotNull(titles.text("en"), titles.text("ja")) + titles.catalogStrings("synonyms") }.orEmpty().distinct()
		val staffPeople = root.array("authors").orEmpty().mapNotNull { row ->
			val edge = row as? JsonObject ?: return@mapNotNull null
			val person = edge.obj("node") ?: return@mapNotNull null
			val name = listOfNotNull(person.text("first_name"), person.text("last_name")).joinToString(" ").ifBlank { null } ?: return@mapNotNull null
			TrackerCatalogPerson(
				id = null,
				name = name,
				imageUrl = null,
				roles = listOfNotNull(edge.text("role")),
				providerUrl = null,
			)
		}
		val supplemental = loadMALCatalogSupplemental(http, key)
		var partial = supplemental.partial
		val recommendations = try {
			root.array("recommendations")?.let { rows -> catalogRows(rows) { row -> row.obj("node")?.let { parseMALCard(it, allowAdult) } } }.orEmpty()
		} catch (_: CatalogException) { partial = true; emptyList() }
		val relatedWorks = try { malCatalogRelatedWorks(root, allowAdult) } catch (_: Exception) { partial = true; emptyList() }
		val rank = root.catalogInt("rank")
		val popularity = root.catalogInt("popularity")
		val serialization = root.array("serialization").orEmpty().mapNotNull { row ->
			(row as? JsonObject)?.obj("node")?.text("name")
		}.distinct().joinToString(", ").takeIf(String::isNotBlank)
		return TrackerCatalogDetails(
			item = item,
			alternativeTitles = alternatives,
			description = root.text("synopsis"),
			genres = root.array("genres").orEmpty().mapNotNull { (it as? JsonObject)?.text("name") },
			staff = staffPeople.map { (listOf(it.name) + it.roles).joinToString(" · ") },
			status = root.text("status"),
			startDate = root.text("start_date"),
			endDate = root.text("end_date"),
			chapterCount = root.catalogInt("num_chapters")?.takeIf { it > 0 },
			volumeCount = root.catalogInt("num_volumes")?.takeIf { it > 0 },
			serialization = serialization,
			stats = buildList {
				root.catalogDouble("mean")?.takeIf { it in 0.0..10.0 }?.let { add(TrackerCatalogStat(CatalogStatKind.MEAN_SCORE, it, 10.0)) }
				rank?.takeIf { it > 0 }?.let { add(TrackerCatalogStat(CatalogStatKind.RANK, it.toDouble())) }
				popularity?.takeIf { it > 0 }?.let { add(TrackerCatalogStat(CatalogStatKind.POPULARITY_RANK, it.toDouble())) }
				root.catalogInt("num_list_users")?.takeIf { it > 0 }?.let { add(TrackerCatalogStat(CatalogStatKind.MEMBERS, it.toDouble())) }
			},
			rank = rank,
			popularity = popularity,
			recommendations = recommendations,
			partial = partial,
			characters = supplemental.characters,
			staffPeople = staffPeople,
			relatedWorks = relatedWorks,
			statistics = supplemental.statistics,
		)
	}

	private fun url(path: String, adult: Boolean, page: Int, size: Int) = "https://api.myanimelist.net/v2/$path".toHttpUrl().newBuilder()
		.addQueryParameter("limit", size.toString()).addQueryParameter("offset", ((page - 1) * size).toString())
		.addQueryParameter("fields", MAL_CATALOG_FIELDS).addQueryParameter("nsfw", adult.toString())

	private suspend fun request(url: HttpUrl) = http.request(catalogService, Request.Builder().url(url)
		.header("X-MAL-CLIENT-ID", clientId).header("Accept", "application/json").get().build())

	private fun parsePage(root: JsonObject, allowAdult: Boolean, page: Int, kinds: Set<CatalogMediaKind>): TrackerCatalogPage {
		val items = catalogRows(root.array("data") ?: invalidCatalog()) { row -> parseMALCard(row.obj("node") ?: invalidCatalog(), allowAdult)?.takeIf { it.key.kind in kinds } }
		return TrackerCatalogPage(items, (page + 1).takeIf { page < CatalogCachePolicy.MAX_PAGE && root.obj("paging")?.text("next") != null })
	}
}
