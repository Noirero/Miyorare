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
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import org.koitharu.kotatsu.scrobbling.kitsu.data.kitsuDetailsUrl
import org.koitharu.kotatsu.scrobbling.kitsu.data.kitsuPeople

internal const val KITSU_CATALOG_FIELDS = "canonicalTitle,titles,posterImage,subtype,ageRating,averageRating,slug"

class KitsuCatalogProvider(private val http: CatalogHttp) : TrackerCatalogProvider {
	override val catalogService = ScrobblerService.KITSU
	override val catalogCapabilities = setOf(
		CatalogCapability.HOME,
		CatalogCapability.SEARCH,
		CatalogCapability.DETAILS,
		CatalogCapability.SECTION_PAGING,
		CatalogCapability.MANGA,
		CatalogCapability.NOVEL,
		CatalogCapability.STAFF,
		CatalogCapability.CHARACTERS,
		CatalogCapability.RELATIONS,
	)
	private val sectionTypes = listOf(CatalogSectionType.TRENDING, CatalogSectionType.POPULAR, CatalogSectionType.TOP_RATED)

	override suspend fun home(filter: CatalogFilter, allowAdult: Boolean): TrackerCatalogHome = coroutineScope {
		// One unpaged Trending collection supplies both kinds; no fictitious Trending pagination.
		val trending = async { try { Result.success(request(trendingUrl())) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) } }
		val keys = filter.kinds.flatMap { kind -> sectionTypes.map { CatalogSectionKey(catalogService, kind, it) } }
		TrackerCatalogHome(keys.map { key -> async {
			try {
				val page = if (key.type == CatalogSectionType.TRENDING) parsePage(trending.await().getOrThrow(), key.kind, allowAdult, 1, paged = false)
					else listing(key, allowAdult, 1, CatalogCachePolicy.HOME_PAGE_SIZE)
				TrackerCatalogSection(key, page.items.take(CatalogCachePolicy.HOME_PAGE_SIZE), page.nextPage)
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { TrackerCatalogSection(key, emptyList(), failure = e.catalogFailure()) }
		} }.awaitAll())
	}

	override suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int): TrackerCatalogPage {
		require(key.service == catalogService)
		catalogPageNumber(page)
		return if (key.type == CatalogSectionType.TRENDING) {
			if (page != 1) throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
			parsePage(request(trendingUrl()), key.kind, allowAdult, page, paged = false)
		} else listing(key, allowAdult, page, CatalogCachePolicy.PAGE_SIZE)
	}

	private suspend fun listing(key: CatalogSectionKey, adult: Boolean, page: Int, size: Int): TrackerCatalogPage {
		val sort = when (key.type) { CatalogSectionType.POPULAR -> "-userCount"; CatalogSectionType.TOP_RATED -> "-averageRating"; else -> throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED)) }
		return parsePage(request(listUrl(key.kind, adult, page, size).addQueryParameter("sort", sort).build()), key.kind, adult, page)
	}

	override suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int): TrackerCatalogPage {
		catalogPageNumber(page)
		val kind = when (filter) { CatalogFilter.ALL -> null; CatalogFilter.MANGA -> CatalogMediaKind.MANGA; CatalogFilter.NOVEL -> CatalogMediaKind.NOVEL }
		val root = request(listUrl(kind, allowAdult, page, CatalogCachePolicy.PAGE_SIZE).addQueryParameter("filter[text]", query.take(128)).build())
		return parsePage(root, kind, allowAdult, page)
	}

	override suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean): TrackerCatalogDetails = coroutineScope {
		require(key.service == catalogService)
		val root = request("https://kitsu.app/api/edge/manga/${key.remoteId}".toHttpUrl().newBuilder().addQueryParameter("include", "categories").build())
		val record = root.obj("data") ?: invalidCatalog()
		val item = parseKitsuCard(record, allowAdult) ?: throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
		if (item.key != key) invalidCatalog()
		val attrs = record.obj("attributes") ?: invalidCatalog()
		val target = TrackerTarget(catalogService, key.remoteId)
		val charactersDeferred = async { loadPeople(target, TrackerContent.CHARACTERS) }
		val staffDeferred = async { loadPeople(target, TrackerContent.STAFF) }
		val relationsDeferred = async { loadRelations(key.remoteId, allowAdult) }
		val characters = charactersDeferred.await()
		val staffPeople = staffDeferred.await()
		val relations = relationsDeferred.await()
		val partial = characters.partial || staffPeople.partial || relations.partial
		TrackerCatalogDetails(
			item = item,
			alternativeTitles = attrs.obj("titles")?.values.orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf(String::isNotBlank) }.distinct(),
			description = attrs.text("synopsis") ?: attrs.text("description"),
			bannerUrl = if (allowAdult || item.adult != null) catalogUrl(attrs.obj("coverImage")?.text("large")) else null,
			genres = root.array("included").orEmpty().filterIsInstance<JsonObject>().filter { it.text("type") == "categories" }.mapNotNull { it.obj("attributes")?.text("title") },
			staff = staffPeople.items.map { (listOf(it.name) + it.roles).joinToString(" · ") },
			status = attrs.text("status"),
			startDate = attrs.text("startDate"),
			rank = attrs.catalogInt("ratingRank"),
			popularity = attrs.catalogInt("popularityRank"),
			partial = partial,
			characters = characters.items,
			staffPeople = staffPeople.items,
			relatedWorks = relations.items,
		)
	}

	private suspend fun loadPeople(target: TrackerTarget, content: TrackerContent): CatalogSupplementalResult<TrackerCatalogPerson> = try {
		val page = TrackerPage()
		catalogPeople(kitsuPeople(request(kitsuDetailsUrl(target, content, page)), target, content, page))
	} catch (e: CancellationException) {
		throw e
	} catch (_: Exception) {
		CatalogSupplementalResult(emptyList(), partial = true)
	}

	private suspend fun loadRelations(id: String, allowAdult: Boolean): CatalogSupplementalResult<TrackerCatalogRelatedWork> = try {
		val root = request(relationsUrl(id))
		val next = root.obj("links")?.let { links -> links.text("next") ?: links.obj("next")?.text("href") }
		CatalogSupplementalResult(kitsuCatalogRelatedWorks(root, allowAdult), partial = next != null)
	} catch (e: CancellationException) {
		throw e
	} catch (_: Exception) {
		CatalogSupplementalResult(emptyList(), partial = true)
	}

	private fun relationsUrl(id: String): HttpUrl = "https://kitsu.app/api/edge/media-relationships".toHttpUrl().newBuilder()
		.addQueryParameter("filter[sourceType]", "Manga")
		.addQueryParameter("filter[sourceId]", id)
		.addQueryParameter("include", "destination")
		.addQueryParameter("page[limit]", "20")
		.build()

	private fun listUrl(kind: CatalogMediaKind?, adult: Boolean, page: Int, size: Int): HttpUrl.Builder = "https://kitsu.app/api/edge/manga".toHttpUrl().newBuilder().apply {
		addQueryParameter("page[limit]", size.toString()); addQueryParameter("page[offset]", ((page - 1) * size).toString())
		addQueryParameter("fields[manga]", KITSU_CATALOG_FIELDS)
		if (kind != null) addQueryParameter("filter[subtype]", if (kind == CatalogMediaKind.NOVEL) "novel" else "manga,manhwa,manhua,oneshot,doujin,oel")
		if (!adult) addQueryParameter("filter[ageRating]", "G,PG,R")
	}
	private fun trendingUrl() = "https://kitsu.app/api/edge/trending/manga".toHttpUrl().newBuilder()
		.addQueryParameter("limit", CatalogCachePolicy.PAGE_SIZE.toString()).addQueryParameter("fields[manga]", KITSU_CATALOG_FIELDS).build()
	private suspend fun request(url: HttpUrl) = http.request(catalogService, Request.Builder().url(url).header("Accept", "application/vnd.api+json").get().build())
	private fun parsePage(root: JsonObject, kind: CatalogMediaKind?, adult: Boolean, page: Int, paged: Boolean = true): TrackerCatalogPage = TrackerCatalogPage(
		catalogRows(root.array("data") ?: invalidCatalog()) { parseKitsuCard(it, adult)?.takeIf { item -> kind == null || item.key.kind == kind } },
		(page + 1).takeIf { paged && page < CatalogCachePolicy.MAX_PAGE && root.obj("links")?.let { it.text("next") ?: it.obj("next")?.text("href") } != null },
	)
}
