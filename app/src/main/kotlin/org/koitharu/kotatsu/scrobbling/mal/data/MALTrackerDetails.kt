package org.koitharu.kotatsu.scrobbling.mal.data

import kotlinx.serialization.json.JsonObject
import org.koitharu.kotatsu.scrobbling.common.data.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

internal val MAL_DETAILS_CAPABILITIES = setOf(TrackerContent.STAFF, TrackerContent.RECOMMENDATIONS)

/** MAL embeds these collections without documented independent pagination or author images. */
internal fun malStaff(root: JsonObject, target: TrackerTarget): TrackerResult<TrackerPerson> {
	if (root.id() != target.id) return invalidTrackerResponse()
	return trackerPage(root.array("authors"), 25) { edge ->
		val node = edge.obj("node") ?: return@trackerPage null
		val name = listOfNotNull(node.text("first_name"), node.text("last_name")).joinToString(" ")
		if (name.isEmpty()) return@trackerPage null
		TrackerPerson(node.id(), name, null, listOfNotNull(edge.text("role")), node.id()?.let { "https://myanimelist.net/people/$it" })
	}.mergePersonRoles()
}

internal fun malRecommendations(root: JsonObject, target: TrackerTarget): TrackerResult<TrackerRecommendation> {
	if (root.id() != target.id) return invalidTrackerResponse()
	return trackerPage(root.array("recommendations"), 25) { edge ->
		val node = edge.obj("node") ?: return@trackerPage null
		val id = node.id() ?: return@trackerPage null
		val title = node.text("title") ?: return@trackerPage null
		TrackerRecommendation(
			TrackerTarget(ScrobblerService.MAL, id, "https://myanimelist.net/manga/$id"), title,
			node.obj("main_picture")?.text("large") ?: node.obj("main_picture")?.text("medium"),
			TrackerRecommendationKind.RECOMMENDATION,
		)
	}
}

