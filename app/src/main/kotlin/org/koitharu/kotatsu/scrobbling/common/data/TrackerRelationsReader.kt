package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerMediaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPage
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelation
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerResult
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerTarget
import javax.inject.Inject
import javax.inject.Singleton

private const val ANILIST_ENDPOINT = "https://graphql.anilist.co"
private const val KITSU_RELATIONS_ENDPOINT = "https://kitsu.app/api/edge/media-relationships"
private const val MAL_API_BASE = "https://api.myanimelist.net/v2"

/**
 * Read-only official relation metadata. This stays separate from provider recommendations: the
 * caller owns relation presentation and only uses a recommendation-shaped proxy for the existing
 * safe source-resolution path.
 */
@Singleton
internal class TrackerRelationsReader @Inject constructor(
	@ScrobblerType(ScrobblerService.ANILIST) aniListClient: OkHttpClient,
	@ScrobblerType(ScrobblerService.KITSU) kitsuClient: OkHttpClient,
	@ScrobblerType(ScrobblerService.MAL) malClient: OkHttpClient,
) {
	private val aniList = trackerDetailsClient(aniListClient)
	private val kitsu = trackerDetailsClient(kitsuClient)
	private val mal = trackerDetailsClient(malClient)

	fun supports(service: ScrobblerService): Boolean = service == ScrobblerService.ANILIST ||
		service == ScrobblerService.KITSU || service == ScrobblerService.MAL

	suspend fun load(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerRelation> {
		if (!supports(target.service)) return TrackerResult.Unsupported
		require(page.number == 1 && page.url == null)
		return when (target.service) {
			ScrobblerService.ANILIST -> loadAniList(target)
			ScrobblerService.KITSU -> loadKitsu(target)
			ScrobblerService.MAL -> loadMal(target)
			else -> TrackerResult.Unsupported
		}
	}

	private suspend fun loadAniList(target: TrackerTarget): TrackerResult<TrackerRelation> {
		val query = """
			query {
				Media(id: ${target.id}, type: MANGA) {
					id
					relations {
						edges {
							relationType
							node {
								id
								type
								format
								title { userPreferred romaji native }
								coverImage { large medium }
								siteUrl
							}
						}
					}
				}
			}
		""".trimIndent()
		val body = JSONObject().put("query", query).toString()
		val request = Request.Builder().url(ANILIST_ENDPOINT)
			.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
		val root = aniList.newCall(request).awaitTrackerDetails {
			Json.parseToJsonElement(it.parseJson().toString()).jsonObject
		}
		return parseAniListRelations(root, target)
	}

	private suspend fun loadKitsu(target: TrackerTarget): TrackerResult<TrackerRelation> {
		val url = KITSU_RELATIONS_ENDPOINT.toHttpUrl().newBuilder()
			.addQueryParameter("filter[sourceType]", "Manga")
			.addQueryParameter("filter[sourceId]", target.id)
			.addQueryParameter("include", "destination")
			.addQueryParameter("page[limit]", "20")
			.build()
		val request = Request.Builder().url(url).get().build()
		val root = kitsu.newCall(request).awaitTrackerDetails {
			Json.parseToJsonElement(it.parseJson().toString()).jsonObject
		}
		return parseKitsuRelations(root, target)
	}

	private suspend fun loadMal(target: TrackerTarget): TrackerResult<TrackerRelation> {
		val url = "$MAL_API_BASE/manga/${target.id}".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "related_anime,related_manga")
			.build()
		val request = Request.Builder().url(url).get().build()
		val root = mal.newCall(request).awaitTrackerDetails {
			Json.parseToJsonElement(it.parseJson().toString()).jsonObject
		}
		return parseMalRelations(root, target)
	}
}

internal fun parseAniListRelations(root: JsonObject, target: TrackerTarget): TrackerResult<TrackerRelation> {
	val media = root.obj("data")?.obj("Media") ?: return invalidTrackerResponse()
	if (media.id() != target.id) return invalidTrackerResponse()
	val edges = media.obj("relations")?.array("edges")
	return trackerPage(edges, 40, providerError = root.hasErrors()) { edge ->
		val node = edge.obj("node") ?: return@trackerPage null
		val id = node.id() ?: return@trackerPage null
		val titles = node.obj("title") ?: return@trackerPage null
		val title = titles.text("userPreferred") ?: titles.text("romaji") ?: titles.text("native") ?: return@trackerPage null
		val mediaType = when (node.text("type")?.uppercase()) {
			"MANGA" -> TrackerMediaType.MANGA
			"ANIME" -> TrackerMediaType.ANIME
			else -> TrackerMediaType.OTHER
		}
		val relation = edge.text("relationType")
		TrackerRelation(
			target = TrackerTarget(ScrobblerService.ANILIST, id, node.text("siteUrl")),
			title = title,
			image = node.obj("coverImage")?.text("large") ?: node.obj("coverImage")?.text("medium"),
			relation = trackerRelationType(relation),
			mediaType = mediaType,
			format = node.text("format"),
			providerRelation = relation,
		)
	}
}

internal fun parseKitsuRelations(root: JsonObject, target: TrackerTarget): TrackerResult<TrackerRelation> {
	val included = root.array("included").orEmpty().filterIsInstance<JsonObject>()
		.associateBy { it.text("type") to it.id() }
	val next = root.obj("links")?.text("next")?.let { TrackerPage(2, it) }
	return trackerPage(root.array("data"), 20, next, root.hasErrors()) { edge ->
		if (edge.text("type") != "mediaRelationships") return@trackerPage null
		val ref = edge.obj("relationships")?.obj("destination")?.obj("data") ?: return@trackerPage null
		val destinationType = ref.text("type") ?: return@trackerPage null
		val id = ref.id() ?: return@trackerPage null
		val destination = included[destinationType to id] ?: return@trackerPage null
		val attrs = destination.obj("attributes") ?: return@trackerPage null
		val title = attrs.text("canonicalTitle") ?: attrs.obj("titles")?.text("en")
			?: attrs.obj("titles")?.text("en_jp") ?: attrs.obj("titles")?.text("ja_jp") ?: return@trackerPage null
		val mediaType = when (destinationType.lowercase()) {
			"manga" -> TrackerMediaType.MANGA
			"anime" -> TrackerMediaType.ANIME
			else -> TrackerMediaType.OTHER
		}
		val slug = attrs.text("slug")
		val providerUrl = slug?.takeIf { mediaType != TrackerMediaType.OTHER }
			?.let { "https://kitsu.app/${destinationType.lowercase()}/$it" }
		val relation = edge.obj("attributes")?.text("role")
		TrackerRelation(
			target = TrackerTarget(ScrobblerService.KITSU, id, providerUrl),
			title = title,
			image = attrs.obj("posterImage")?.text("large") ?: attrs.obj("posterImage")?.text("medium")
				?: attrs.obj("posterImage")?.text("small"),
			relation = trackerRelationType(relation),
			mediaType = mediaType,
			format = attrs.text("subtype"),
			providerRelation = relation,
		)
	}
}

internal fun parseMalRelations(root: JsonObject, target: TrackerTarget): TrackerResult<TrackerRelation> {
	if (root.id() != target.id) return invalidTrackerResponse()
	val rows = buildJsonArray {
		root.array("related_manga").orEmpty().forEach { edge ->
			if (edge is JsonObject) add(buildJsonObject {
				put("mediaType", JsonPrimitive("MANGA"))
				put("edge", edge)
			})
		}
		root.array("related_anime").orEmpty().forEach { edge ->
			if (edge is JsonObject) add(buildJsonObject {
				put("mediaType", JsonPrimitive("ANIME"))
				put("edge", edge)
			})
		}
	}
	return trackerPage(rows, 50) { wrapped ->
		val edge = wrapped.obj("edge") ?: return@trackerPage null
		val node = edge.obj("node") ?: return@trackerPage null
		val id = node.id() ?: return@trackerPage null
		val title = node.text("title") ?: return@trackerPage null
		val mediaType = if (wrapped.text("mediaType") == "ANIME") TrackerMediaType.ANIME else TrackerMediaType.MANGA
		val relation = edge.text("relation_type")
		val providerUrl = when (mediaType) {
			TrackerMediaType.MANGA -> "https://myanimelist.net/manga/$id"
			TrackerMediaType.ANIME -> "https://myanimelist.net/anime/$id"
			TrackerMediaType.OTHER -> null
		}
		TrackerRelation(
			target = TrackerTarget(ScrobblerService.MAL, id, providerUrl),
			title = title,
			image = node.obj("main_picture")?.text("large") ?: node.obj("main_picture")?.text("medium"),
			relation = trackerRelationType(relation),
			mediaType = mediaType,
			providerRelation = edge.text("relation_type_formatted") ?: relation,
		)
	}
}

internal fun trackerRelationType(value: String?): TrackerRelationType {
	val key = value.orEmpty().trim().uppercase().replace('-', '_').replace(' ', '_')
	return when (key) {
		"ADAPTATION" -> TrackerRelationType.ADAPTATION
		"PREQUEL" -> TrackerRelationType.PREQUEL
		"SEQUEL" -> TrackerRelationType.SEQUEL
		"PARENT", "PARENT_STORY", "FULL_STORY" -> TrackerRelationType.PARENT
		"SIDE_STORY" -> TrackerRelationType.SIDE_STORY
		"CHARACTER" -> TrackerRelationType.CHARACTER
		"SUMMARY" -> TrackerRelationType.SUMMARY
		"ALTERNATIVE", "ALTERNATIVE_SETTING", "ALTERNATIVE_VERSION" -> TrackerRelationType.ALTERNATIVE
		"SPIN_OFF", "SPINOFF" -> TrackerRelationType.SPIN_OFF
		"SOURCE" -> TrackerRelationType.SOURCE
		"COMPILATION" -> TrackerRelationType.COMPILATION
		"CONTAINS" -> TrackerRelationType.CONTAINS
		"SAME_UNIVERSE" -> TrackerRelationType.SAME_UNIVERSE
		else -> TrackerRelationType.OTHER
	}
}
