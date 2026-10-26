package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
import org.koitharu.kotatsu.discovery.domain.MangaBakaExploreType
import org.koitharu.kotatsu.discovery.domain.MangaBakaLibraryItem
import org.koitharu.kotatsu.discovery.domain.MangaBakaPersonalHomeSnapshot
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import java.io.IOException
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

private const val MANGABAKA_PERSONAL_API_V1 = "https://api.mangabaka.org/v1"
private const val MANGABAKA_PERSONAL_API_V2 = "https://api.mangabaka.org/v2"
private const val MANGABAKA_LIBRARY_PAGE_SIZE = 50
private const val MANGABAKA_LIBRARY_MAX_PAGES = 40
private const val MANGABAKA_PERSONAL_LIMIT = 12

/**
 * Signed-in MangaBaka Home companion.
 *
 * Account library/recommendation reads use the authenticated MangaBaka scrobbler client. Public
 * Explore-by-type reads deliberately go through [CatalogHttp], which strips credentials/cookies.
 * The generic public [TrackerCatalogRepository] remains unaware of personal account data.
 */
@Singleton
class MangaBakaPersonalHomeRepository @Inject constructor(
	@ScrobblerType(ScrobblerService.MANGABAKA) private val accountHttp: OkHttpClient,
	private val publicHttp: CatalogHttp,
) {
	suspend fun load(filter: CatalogFilter, allowAdult: Boolean): MangaBakaPersonalHomeSnapshot {
		val library = loadLibrary(filter, allowAdult)
		val recommendations = try {
			loadRecommendations(filter, allowAdult)
		} catch (e: CancellationException) {
			throw e
		} catch (e: CatalogAccountUnauthorizedException) {
			throw e
		} catch (_: Exception) {
			return MangaBakaPersonalHomeSnapshot(library = library, partial = true)
		}
		return MangaBakaPersonalHomeSnapshot(library, recommendations)
	}

	/** Public exact-type browsing. MangaBaka Search officially supports type/type_not. */
	suspend fun explore(type: MangaBakaExploreType, allowAdult: Boolean): List<TrackerCatalogItem> {
		val url = "$MANGABAKA_PERSONAL_API_V2/series/search".toHttpUrl().newBuilder()
			.addQueryParameter("type", type.apiValue)
			.addQueryParameter("sort_by", "popularity_asc")
			.addQueryParameter("page", "1")
			.addQueryParameter("limit", MANGABAKA_PERSONAL_LIMIT.toString())
			.apply { addContentRatings(allowAdult) }
			.build()
		val root = publicHttp.request(
			ScrobblerService.MANGABAKA,
			Request.Builder().url(url).get().build(),
		)
		return (root["data"] as? JsonArray).orEmpty()
			.mapNotNull { element ->
				val row = element as? JsonObject ?: return@mapNotNull null
				parseMangaBakaPersonalCard(row, allowAdult)
			}
			// Keep the provider response honest even if its search contract changes later.
			.filter { it.providerType == type.apiValue }
			.distinctBy { it.key }
			.take(MANGABAKA_PERSONAL_LIMIT)
	}

	private suspend fun loadLibrary(filter: CatalogFilter, allowAdult: Boolean): List<MangaBakaLibraryItem> {
		val result = LinkedHashMap<Long, MangaBakaLibraryItem>()
		var page = 1
		while (page <= MANGABAKA_LIBRARY_MAX_PAGES) {
			val url = "$MANGABAKA_PERSONAL_API_V2/my/library".toHttpUrl().newBuilder()
				.addQueryParameter("page", page.toString())
				.addQueryParameter("limit", MANGABAKA_LIBRARY_PAGE_SIZE.toString())
				.apply {
					when (filter) {
						CatalogFilter.ALL -> Unit
						CatalogFilter.MANGA -> addQueryParameter("type_not", "novel")
						CatalogFilter.NOVEL -> addQueryParameter("type", "novel")
					}
					addContentRatings(allowAdult)
				}
				.build()
			val root = accountRequest(url.toString())
			val rows = root.optJSONArray("data") ?: throw IOException("MangaBaka library response has no data")
			for (index in 0 until rows.length()) {
				val row = rows.optJSONObject(index)?.toKotlinxObject() ?: continue
				parseMangaBakaLibraryItem(row, filter, allowAdult)?.let { result[it.tracking.targetId] = it }
			}
			val pagination = root.optJSONObject("pagination")?.toKotlinxObject()
			if (!mangaBakaHasNext(pagination, page, rows.length(), MANGABAKA_LIBRARY_PAGE_SIZE)) break
			page++
		}
		return result.values.toList()
	}

	private suspend fun loadRecommendations(filter: CatalogFilter, allowAdult: Boolean): List<TrackerCatalogItem> {
		val url = "$MANGABAKA_PERSONAL_API_V1/my/series/recommendations".toHttpUrl().newBuilder()
			.addQueryParameter("page", "1")
			.addQueryParameter("limit", MANGABAKA_PERSONAL_LIMIT.toString())
			.apply {
				when (filter) {
					CatalogFilter.ALL -> Unit
					CatalogFilter.MANGA -> addQueryParameter("type_not", "novel")
					CatalogFilter.NOVEL -> addQueryParameter("type", "novel")
				}
				addContentRatings(allowAdult)
			}
			.build()
		val root = accountRequest(url.toString())
		val rows = root.optJSONArray("data") ?: return emptyList()
		return buildList {
			for (index in 0 until rows.length()) {
				val row = rows.optJSONObject(index)?.toKotlinxObject() ?: continue
				parseMangaBakaPersonalCard(row, allowAdult)?.takeIf { it.key.kind in filter.kinds }?.let(::add)
			}
		}.distinctBy { it.key }.take(MANGABAKA_PERSONAL_LIMIT)
	}

	private suspend fun accountRequest(url: String): JSONObject {
		val response = accountHttp.newCall(Request.Builder().url(url).get().build()).await()
		return response.requireMangaBakaAccountSuccess().parseJson()
	}
}

internal fun parseMangaBakaLibraryItem(
	row: JsonObject,
	filter: CatalogFilter,
	allowAdult: Boolean,
): MangaBakaLibraryItem? {
	val entry = row["entry"] as? JsonObject ?: return null
	val series = row["series"] as? JsonObject ?: row["data"] as? JsonObject ?: row
	val item = parseMangaBakaCatalogCard(series, allowAdult) ?: return null
	if (item.key.kind !in filter.kinds) return null
	val targetId = entry.positiveLong("series_id") ?: item.key.remoteId.toLongOrNull() ?: return null
	if (item.key.remoteId.toLongOrNull() != targetId) return null
	val tracking = CatalogTrackingEntry(
		service = ScrobblerService.MANGABAKA,
		targetId = targetId,
		// MangaBaka library mutation endpoints are keyed by series id, not the library row id.
		entryId = targetId,
		status = catalogTrackingStatus(ScrobblerService.MANGABAKA, entry.stringValue("state")),
		chapter = (entry.numberValue("progress_chapter") ?: 0.0).toInt().coerceAtLeast(0),
		rating = ((entry.numberValue("rating") ?: 0.0).toFloat() / 100f).coerceIn(0f, 1f),
	)
	return MangaBakaLibraryItem(item, tracking)
}

internal fun parseMangaBakaPersonalCard(row: JsonObject, allowAdult: Boolean): TrackerCatalogItem? {
	val series = row["series"] as? JsonObject ?: row
	return parseMangaBakaCatalogCard(series, allowAdult)
}

private fun okhttp3.HttpUrl.Builder.addContentRatings(allowAdult: Boolean) {
	addQueryParameter("content_rating", "safe")
	addQueryParameter("content_rating", "suggestive")
	if (allowAdult) {
		addQueryParameter("content_rating", "erotica")
		addQueryParameter("content_rating", "pornographic")
	}
}

private fun JSONObject.toKotlinxObject(): JsonObject = Json.parseToJsonElement(toString()).jsonObject

private fun JsonObject.positiveLong(name: String): Long? =
	(this[name] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

private fun JsonObject.stringValue(name: String): String? =
	(this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.numberValue(name: String): Double? =
	(this[name] as? JsonPrimitive)?.doubleOrNull

private fun Response.requireMangaBakaAccountSuccess(): Response {
	if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
		close()
		throw CatalogAccountUnauthorizedException(ScrobblerService.MANGABAKA)
	}
	if (!isSuccessful) {
		val status = code
		close()
		throw IOException("MangaBaka account request failed with HTTP $status")
	}
	return this
}
