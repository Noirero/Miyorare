package org.koitharu.kotatsu.scrobbling.kitsu.data

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.scrobbling.common.data.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

internal val KITSU_DETAILS_CAPABILITIES = setOf(TrackerContent.CHARACTERS, TrackerContent.STAFF)

internal fun kitsuDetailsUrl(target: TrackerTarget, content: TrackerContent, page: TrackerPage): HttpUrl {
	require(target.service == ScrobblerService.KITSU)
	val collection = when (content) {
		TrackerContent.CHARACTERS -> "characters"
		TrackerContent.STAFF -> "staff"
		else -> error("Unsupported Kitsu content")
	}
	val path = "/api/edge/manga/${target.id}/$collection"
	val first = "https://kitsu.app$path".toHttpUrl()
	val url = page.url?.let { first.resolve(it) } ?: first.also { require(page.number == 1 && page.url == null) }
	// Server links are untrusted input. Never let a related link receive a bearer on another origin/route.
	require(url.isHttps && url.host == "kitsu.app" && url.port == 443 && url.encodedPath == path)
	require(url.username.isEmpty() && url.password.isEmpty())
	require(url.queryParameterNames.all { it in setOf("include", "page[limit]", "page[offset]") })
	require(url.queryParameterNames.all { url.queryParameterValues(it).size == 1 })
	url.queryParameter("page[offset]")?.let { require(it.toLongOrNull()?.let { offset -> offset >= 0 } == true) }
	val limit = url.queryParameter("page[limit]")?.toIntOrNull() ?: 20
	require(limit in 1..20)
	return url.newBuilder().setQueryParameter("page[limit]", limit.toString())
		.setQueryParameter("include", if (content == TrackerContent.CHARACTERS) "character" else "person").build()
}

internal fun kitsuPeople(root: JsonObject, target: TrackerTarget, content: TrackerContent, page: TrackerPage): TrackerResult<TrackerPerson> {
	val (edgeType, relationship, personType) = when (content) {
		TrackerContent.CHARACTERS -> Triple("mediaCharacters", "character", "characters")
		TrackerContent.STAFF -> Triple("mediaStaff", "person", "people")
		else -> return TrackerResult.Unsupported
	}
	val included = root.array("included").orEmpty().filterIsInstance<JsonObject>()
		.associateBy { it.text("type") to it.text("id") }
	var badLink = false
	val link = root.obj("links")?.let { links ->
		links.text("next") ?: links.obj("next")?.text("href")
	}
	val next = link?.let {
		try {
			val candidate = TrackerPage((page.number + 1).coerceAtMost(1000), it)
			val url = kitsuDetailsUrl(target, content, candidate)
			val current = kitsuDetailsUrl(target, content, page)
			if (url == current || page.number == 1000) { badLink = true; null } else candidate
		} catch (_: IllegalArgumentException) { badLink = true; null }
	}
	val result = trackerPage(root.array("data"), 20, next, root.hasErrors()) { edge ->
		if (edge.text("type") != edgeType) return@trackerPage null
		val ref = edge.obj("relationships")?.obj(relationship)?.obj("data") ?: return@trackerPage null
		if (ref.text("type") != personType) return@trackerPage null
		val person = included[personType to ref.text("id")] ?: return@trackerPage null
		val attrs = person.obj("attributes") ?: return@trackerPage null
		val name = attrs.text("canonicalName") ?: attrs.text("name") ?: return@trackerPage null
		TrackerPerson(person.text("id"), name, attrs.obj("image")?.text("small"), listOfNotNull(edge.obj("attributes")?.text("role")))
	}.mergePersonRoles()
	return if (badLink) when (result) {
		is TrackerResult.Success -> TrackerResult.Partial(result.items, setOf(TrackerPartialReason.MISSING_RECORDS))
		is TrackerResult.Empty -> TrackerResult.Partial(emptyList(), setOf(TrackerPartialReason.MISSING_RECORDS))
		is TrackerResult.Partial -> result.copy(reasons = result.reasons + TrackerPartialReason.MISSING_RECORDS)
		else -> result
	} else result
}
