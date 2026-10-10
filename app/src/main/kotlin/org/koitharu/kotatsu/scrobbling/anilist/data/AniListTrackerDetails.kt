package org.koitharu.kotatsu.scrobbling.anilist.data

import kotlinx.serialization.json.*
import org.koitharu.kotatsu.scrobbling.common.data.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

internal fun aniListDetailsQuery(target: TrackerTarget, content: TrackerContent, page: TrackerPage): String {
	require(target.service == ScrobblerService.ANILIST && page.url == null)
	val field = when (content) {
		TrackerContent.CHARACTERS -> "characters(page: ${page.number}, perPage: 25, sort: [ROLE, FAVOURITES_DESC]) { pageInfo { hasNextPage } edges { role node { id name { userPreferred full } image { large medium } siteUrl } } }"
		TrackerContent.STAFF -> "staff(page: ${page.number}, perPage: 25) { pageInfo { hasNextPage } edges { role node { id name { userPreferred full } image { large medium } siteUrl } } }"
		TrackerContent.RECOMMENDATIONS -> "recommendations(page: ${page.number}, perPage: 25, sort: RATING_DESC) { pageInfo { hasNextPage } edges { node { mediaRecommendation { id title { userPreferred romaji native } coverImage { large medium } siteUrl } } } }"
	}
	return "query { Media(id: ${target.id}, type: MANGA) { id $field } }"
}

private fun aniListConnection(root: JsonObject, target: TrackerTarget, field: String): JsonObject? {
	val media = root.obj("data")?.obj("Media") ?: return null
	if (media.id() != target.id) return null
	return media.obj(field)
}

internal fun aniListPeople(root: JsonObject, target: TrackerTarget, content: TrackerContent, page: TrackerPage): TrackerResult<TrackerPerson> {
	val field = when (content) {
		TrackerContent.CHARACTERS -> "characters"
		TrackerContent.STAFF -> "staff"
		else -> return TrackerResult.Unsupported
	}
	val connection = aniListConnection(root, target, field) ?: return invalidTrackerResponse()
	val next = if (connection.obj("pageInfo")?.get("hasNextPage") == JsonPrimitive(true) && page.number < 1000) TrackerPage(page.number + 1) else null
	return trackerPage(connection.array("edges"), 25, next, root.hasErrors()) { edge ->
		val node = edge.obj("node") ?: return@trackerPage null
		val name = node.obj("name")?.text("userPreferred") ?: node.obj("name")?.text("full") ?: return@trackerPage null
		TrackerPerson(node.id(), name, node.obj("image")?.text("large") ?: node.obj("image")?.text("medium"), listOfNotNull(edge.text("role")), node.text("siteUrl"))
	}.mergePersonRoles()
}

internal fun aniListRecommendations(root: JsonObject, target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerRecommendation> {
	val connection = aniListConnection(root, target, "recommendations") ?: return invalidTrackerResponse()
	val next = if (connection.obj("pageInfo")?.get("hasNextPage") == JsonPrimitive(true) && page.number < 1000) TrackerPage(page.number + 1) else null
	return trackerPage(connection.array("edges"), 25, next, root.hasErrors()) { edge ->
		val media = edge.obj("node")?.obj("mediaRecommendation") ?: return@trackerPage null
		val id = media.id() ?: return@trackerPage null
		val titles = media.obj("title") ?: return@trackerPage null
		val title = titles.text("userPreferred") ?: titles.text("romaji") ?: titles.text("native") ?: return@trackerPage null
		TrackerRecommendation(
			TrackerTarget(ScrobblerService.ANILIST, id, media.text("siteUrl")), title,
			media.obj("coverImage")?.text("large") ?: media.obj("coverImage")?.text("medium"), TrackerRecommendationKind.RECOMMENDATION,
		)
	}
}

