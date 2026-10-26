package org.koitharu.kotatsu.scrobbling.mangabaka.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.array
import org.koitharu.kotatsu.scrobbling.common.data.awaitTrackerDetails
import org.koitharu.kotatsu.scrobbling.common.data.id
import org.koitharu.kotatsu.scrobbling.common.data.invalidTrackerResponse
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.text
import org.koitharu.kotatsu.scrobbling.common.data.trackerDetailsClient
import org.koitharu.kotatsu.scrobbling.common.data.trackerPage
import org.koitharu.kotatsu.scrobbling.common.data.trackerRelationType
import org.koitharu.kotatsu.scrobbling.common.domain.TrackerDetailsProvider
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerContent
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerMediaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPage
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPartialReason
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPerson
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRecommendation
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRecommendationKind
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelation
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerResult
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerTarget
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val MANGABAKA_API_V2 = "https://api.mangabaka.org/v2"
private const val RELATION_LIMIT = 40
private const val RECOMMENDATION_LIMIT = 12

internal val MANGABAKA_DETAILS_CAPABILITIES = setOf(
	TrackerContent.STAFF,
	TrackerContent.RECOMMENDATIONS,
)

/**
 * Tracker Home adapter for MangaBaka. Tracking writes stay on [MangaBakaRepository]; this wrapper
 * only adds bounded public catalog reads and delegates every existing scrobbling operation.
 */
@Singleton
class MangaBakaTrackerRepository @Inject constructor(
	private val repository: MangaBakaRepository,
	@BaseHttpClient baseHttpClient: OkHttpClient,
	@ScrobblerType(ScrobblerService.MANGABAKA) private val storage: ScrobblerStorage,
) : ScrobblerRepository by repository, TrackerDetailsProvider {

	private val detailsHttpClient by lazy { trackerDetailsClient(baseHttpClient) }

	override val detailsService = ScrobblerService.MANGABAKA
	override val detailsCapabilities = MANGABAKA_DETAILS_CAPABILITIES
	override val isAuthorized get() = repository.isAuthorized
	override val detailsSessionGeneration get() = storage.sessionGeneration

	// MangaBaka does not currently expose linked character records; do not fabricate Characters.
	override suspend fun loadStaff(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> {
		requireMangaBakaFirstPage(target, page)
		return mangaBakaStaff(request(seriesUrl(target, full = true)), target)
	}

	override suspend fun loadRecommendations(
		target: TrackerTarget,
		page: TrackerPage,
	): TrackerResult<TrackerRecommendation> {
		requireMangaBakaFirstPage(target, page)
		return mangaBakaRecommendations(request(readersAlsoLikeUrl(target)), target)
	}

	internal suspend fun loadRelations(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerRelation> {
		requireMangaBakaFirstPage(target, page)
		val details = request(seriesUrl(target, full = true))
		val relatedIds = mangaBakaRelationTargetIds(details, target)
		val related = if (relatedIds.isEmpty()) null else request(batchSeriesUrl(relatedIds))
		return mangaBakaRelations(details, related, target)
	}

	private suspend fun request(url: HttpUrl): JsonObject {
		val request = Request.Builder().url(url).get().build()
		return detailsHttpClient.newCall(request).awaitTrackerDetails { response ->
			if (!response.isSuccessful) throw IOException("MangaBaka supplemental HTTP ${response.code}")
			Json.parseToJsonElement(checkNotNull(response.body).string()).jsonObject
		}
	}
}

private fun requireMangaBakaFirstPage(target: TrackerTarget, page: TrackerPage) {
	require(target.service == ScrobblerService.MANGABAKA)
	require(target.id.toIntOrNull()?.let { it > 0 } == true)
	require(page.number == 1 && page.url == null)
}

private fun seriesUrl(target: TrackerTarget, full: Boolean): HttpUrl =
	"$MANGABAKA_API_V2/series/${target.id}".toHttpUrl().newBuilder().apply {
		if (full) addQueryParameter("schema", "full")
	}.build()

private fun readersAlsoLikeUrl(target: TrackerTarget): HttpUrl =
	"$MANGABAKA_API_V2/series/${target.id}/readers-also-like".toHttpUrl().newBuilder()
		.addQueryParameter("limit", RECOMMENDATION_LIMIT.toString())
		.build()

private fun batchSeriesUrl(ids: List<String>): HttpUrl =
	"$MANGABAKA_API_V2/series/batch".toHttpUrl().newBuilder().apply {
		ids.distinct().take(RELATION_LIMIT).forEach { addQueryParameter("id", it) }
	}.build()

internal fun mangaBakaStaff(root: JsonObject, target: TrackerTarget): TrackerResult<TrackerPerson> {
	val series = root.obj("data") ?: return invalidTrackerResponse()
	if (series.id() != target.id) return invalidTrackerResponse()
	val items = ArrayList<TrackerPerson>()
	series.stringArray("authors").forEach { name ->
		items += TrackerPerson(id = null, name = name, image = null, roles = listOf("Author"), url = null)
	}
	series.stringArray("artists").forEach { name ->
		items += TrackerPerson(id = null, name = name, image = null, roles = listOf("Artist"), url = null)
	}
	return if (items.isEmpty()) TrackerResult.Empty() else TrackerResult.Success(items)
}

internal fun mangaBakaRecommendations(
	root: JsonObject,
	target: TrackerTarget,
): TrackerResult<TrackerRecommendation> = trackerPage(root.array("data"), RECOMMENDATION_LIMIT) { row ->
	val series = row.obj("series") ?: return@trackerPage null
	val id = series.id() ?: return@trackerPage null
	if (id == target.id) return@trackerPage null
	val title = mangaBakaTitle(series) ?: return@trackerPage null
	TrackerRecommendation(
		target = TrackerTarget(ScrobblerService.MANGABAKA, id, mangaBakaSeriesUrl(series, id)),
		title = title,
		image = mangaBakaCover(series),
		kind = TrackerRecommendationKind.RECOMMENDATION,
	)
}

internal fun mangaBakaRelationTargetIds(root: JsonObject, target: TrackerTarget): List<String> {
	val series = root.obj("data") ?: throw IOException("Invalid MangaBaka supplemental response")
	if (series.id() != target.id) throw IOException("MangaBaka target identity changed")
	return series.array("relationships").orEmpty().take(RELATION_LIMIT).mapNotNull { edge ->
		(edge as? JsonObject)?.id("to_series_id")
	}.distinct()
}

internal fun mangaBakaRelations(
	root: JsonObject,
	relatedRoot: JsonObject?,
	target: TrackerTarget,
): TrackerResult<TrackerRelation> {
	val series = root.obj("data") ?: return invalidTrackerResponse()
	if (series.id() != target.id) return invalidTrackerResponse()
	val edges = series.array("relationships").orEmpty()
	if (edges.isEmpty()) return TrackerResult.Empty()

	val related = relatedRoot?.array("data").orEmpty().filterIsInstance<JsonObject>()
		.mapNotNull { item -> item.id()?.let { it to item } }.toMap()
	val reasons = mutableSetOf<TrackerPartialReason>()
	if (edges.size > RELATION_LIMIT) reasons += TrackerPartialReason.TRUNCATED
	val items = edges.take(RELATION_LIMIT).mapNotNull { raw ->
		val edge = raw as? JsonObject
		val id = edge?.id("to_series_id")
		val destination = id?.let(related::get)
		if (edge == null || id == null || destination == null) {
			reasons += TrackerPartialReason.MISSING_RECORDS
			return@mapNotNull null
		}
		val title = mangaBakaTitle(destination)
		if (title == null) {
			reasons += TrackerPartialReason.MISSING_RECORDS
			return@mapNotNull null
		}
		val rawRelation = edge.text("relation_type")
		TrackerRelation(
			target = TrackerTarget(ScrobblerService.MANGABAKA, id, mangaBakaSeriesUrl(destination, id)),
			title = title,
			image = mangaBakaCover(destination),
			relation = trackerRelationType(rawRelation),
			mediaType = mangaBakaMediaType(destination.text("type")),
			format = destination.text("type"),
			providerRelation = rawRelation,
		)
	}
	return when {
		reasons.isNotEmpty() -> TrackerResult.Partial(items, reasons)
		items.isEmpty() -> TrackerResult.Empty()
		else -> TrackerResult.Success(items)
	}
}

private fun mangaBakaTitle(series: JsonObject): String? {
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

private fun mangaBakaCover(series: JsonObject): String? = series.obj("cover")?.let { cover ->
	cover.text("x350") ?: cover.text("x250") ?: cover.text("x150") ?: cover.text("raw")
}

private fun mangaBakaSeriesUrl(series: JsonObject, id: String): String =
	series.text("canonical_url") ?: "https://mangabaka.org/$id"

private fun mangaBakaMediaType(value: String?): TrackerMediaType = when (value?.lowercase()) {
	"manga", "novel", "manhwa", "manhua", "oel" -> TrackerMediaType.MANGA
	else -> TrackerMediaType.OTHER
}

private fun JsonObject.stringArray(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { item ->
	(item as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
}
