package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import kotlinx.serialization.json.*
import org.koitharu.kotatsu.scrobbling.common.data.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException
import kotlin.math.roundToInt

internal data class MangaUpdatesList(val id: Long, val type: String, val custom: Boolean)
internal data class MangaUpdatesRemoteState(val seriesId: Long, val listId: Long, val type: String, val chapter: Int, val volume: Int, val priority: Int)

internal fun JsonElement.muObject(): JsonObject = this as? JsonObject ?: throw IOException("Malformed MangaUpdates response")
internal fun JsonObject.muLong(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: throw IOException("Missing MangaUpdates identity")
internal fun JsonObject.muProgress(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: throw IOException("Invalid MangaUpdates progress")
internal fun JsonObject.muImage(key: String = "image") = obj(key)?.obj("url")?.text("original") ?: obj(key)?.obj("url")?.text("thumb")

internal fun mangaUpdatesUser(root: JsonObject): ScrobblerUser = ScrobblerUser(
	root.muLong("user_id").also { require(it > 0) { "Invalid MangaUpdates account" } },
	root.text("username") ?: throw IOException("Invalid MangaUpdates account"),
	root.obj("avatar")?.text("url") ?: root.text("avatar_url"), ScrobblerService.MANGAUPDATES,
)

internal fun mangaUpdatesLoginToken(root: JsonObject): String {
	fun JsonObject.token() = (this["session_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
	val token = root.token() ?: root.obj("context")?.token() ?: root.obj("data")?.token()
		?: throw IOException("MangaUpdates did not return a reusable session")
	return validMangaUpdatesToken(token)
}

internal fun mangaUpdatesLists(root: JsonElement): List<MangaUpdatesList> = (root as? JsonArray)?.map {
	val list = it.muObject()
	MangaUpdatesList(list.muLong("list_id").also { id -> require(id >= 0) }, list.text("type") ?: throw IOException("Missing MangaUpdates list type"),
		(list["custom"] as? JsonPrimitive)?.booleanOrNull ?: throw IOException("Missing MangaUpdates list kind"))
} ?: throw IOException("Malformed MangaUpdates lists")

internal fun mangaUpdatesRemoteState(root: JsonObject, targetId: Long): MangaUpdatesRemoteState {
	val id = root.obj("series")?.muLong("id") ?: throw IOException("Missing MangaUpdates series")
	if (id != targetId) throw IOException("MangaUpdates returned a different series")
	val status = root.obj("status") ?: throw IOException("Missing MangaUpdates progress")
	return MangaUpdatesRemoteState(id, root.muLong("list_id").also { require(it >= 0) }, root.text("list_type") ?: throw IOException("Missing MangaUpdates list type"),
		status.muProgress("chapter"), status.muProgress("volume"), (root["priority"] as? JsonPrimitive)?.intOrNull ?: throw IOException("Missing MangaUpdates priority"))
}

internal fun MangaUpdatesRemoteState.writeBody(): JsonArray = buildJsonArray { add(buildJsonObject {
	put("series", buildJsonObject { put("id", seriesId) }); put("list_id", listId)
	put("status", buildJsonObject { put("chapter", chapter); put("volume", volume) }); put("priority", priority)
}) }

internal fun mangaUpdatesRating(root: JsonObject): Float {
	val value = (root["rating"] as? JsonPrimitive)?.floatOrNull ?: throw IOException("Invalid MangaUpdates rating")
	if (!value.isFinite() || value !in 0f..10f) throw IOException("Invalid MangaUpdates rating")
	return value / 10f
}

internal fun mangaUpdatesRatingValue(normalized: Float): Int {
	require(normalized.isFinite() && normalized in 0f..1f) { "Invalid MangaUpdates rating" }
	return if (normalized == 0f) 0 else (normalized * 10f).roundToInt().coerceIn(1, 10)
}

internal fun mangaUpdatesStaff(root: JsonObject, target: TrackerTarget): TrackerResult<TrackerPerson> {
	if (root.id("series_id") != target.id) return invalidTrackerResponse()
	return trackerPage(root.array("authors"), 25) { author ->
		val name = author.text("name") ?: return@trackerPage null
		TrackerPerson(author.id("author_id"), name, null, listOfNotNull(author.text("type")), author.text("url"))
	}.mergePersonRoles()
}

internal fun mangaUpdatesRecommendations(root: JsonObject, target: TrackerTarget): TrackerResult<TrackerRecommendation> {
	if (root.id("series_id") != target.id) return invalidTrackerResponse()
	return trackerPage(root.array("recommendations"), 25) { row ->
		val id = row.id("series_id") ?: return@trackerPage null
		val title = row.text("series_name") ?: return@trackerPage null
		TrackerRecommendation(TrackerTarget(ScrobblerService.MANGAUPDATES, id, row.text("series_url")), title, row.muImage("series_image"), TrackerRecommendationKind.RECOMMENDATION)
	}
}
