package org.koitharu.kotatsu.scrobbling.shikimori.data

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.scrobbling.common.data.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val SHIKIMORI_DETAILS_ORIGIN = "https://shikimori.io"

/** Public catalog only. Do not forward legacy .one OAuth credentials across a redirect. */
internal class ShikimoriDetailsApi {
	private val client = OkHttpClient.Builder()
		.connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
		.callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

	suspend fun people(target: TrackerTarget, content: TrackerContent, page: TrackerPage): TrackerResult<TrackerPerson> {
		require(target.service == ScrobblerService.SHIKIMORI && page.number == 1 && page.url == null)
		val character = content == TrackerContent.CHARACTERS
		val field = if (character) "characterRoles" else "personRoles"
		val node = if (character) "character" else "person"
		val query = "{ mangas(ids: \"${target.id}\", limit: 1) { id $field { rolesEn $node { id name poster { originalUrl } } } } }"
		val body = buildJsonObject { put("query", query) }.toString().toRequestBody("application/json".toMediaType())
		val request = Request.Builder().url("$SHIKIMORI_DETAILS_ORIGIN/api/graphql").header("User-Agent", "Miyorare").post(body).build()
		return shikimoriPeople(json(request).jsonObject, target, content)
	}

	suspend fun recommendations(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerRecommendation> {
		require(target.service == ScrobblerService.SHIKIMORI && page.number == 1 && page.url == null)
		val request = Request.Builder().url("$SHIKIMORI_DETAILS_ORIGIN/api/mangas/${target.id}/similar")
			.header("User-Agent", "Miyorare").get().build()
		return shikimoriSimilar(json(request).jsonArray)
	}

	private suspend fun json(request: Request): JsonElement = client.newCall(request).await().use { response ->
		if (!response.isSuccessful) throw IOException("Shikimori supplemental HTTP ${response.code}")
		Json.parseToJsonElement(checkNotNull(response.body).string())
	}
}

internal fun shikimoriUrl(value: String?): String? {
	if (value == null) return null
	val url = SHIKIMORI_DETAILS_ORIGIN.toHttpUrl().resolve(value) ?: return null
	return url.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
}

internal fun shikimoriPeople(root: JsonObject, target: TrackerTarget, content: TrackerContent): TrackerResult<TrackerPerson> {
	val manga = root.obj("data")?.array("mangas")?.singleOrNull() as? JsonObject ?: return invalidTrackerResponse()
	if (manga.id() != target.id) return invalidTrackerResponse()
	val (field, nodeKey) = when (content) {
		TrackerContent.CHARACTERS -> "characterRoles" to "character"
		TrackerContent.STAFF -> "personRoles" to "person"
		else -> return TrackerResult.Unsupported
	}
	return trackerPage(manga.array(field), 25, providerError = root.hasErrors()) { edge ->
		val person = edge.obj(nodeKey) ?: return@trackerPage null
		val name = person.text("name") ?: return@trackerPage null
		val roles = edge.array("rolesEn").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeIf(String::isNotBlank) }
		TrackerPerson(person.id(), name, shikimoriUrl(person.obj("poster")?.text("originalUrl")), roles)
	}.mergePersonRoles()
}

internal fun shikimoriSimilar(rows: JsonArray): TrackerResult<TrackerRecommendation> = trackerPage(rows, 25) { node ->
	val id = node.id() ?: return@trackerPage null
	val name = node.text("name") ?: return@trackerPage null
	TrackerRecommendation(
		TrackerTarget(ScrobblerService.SHIKIMORI, id, shikimoriUrl(node.text("url"))), name,
		shikimoriUrl(node.obj("image")?.text("preview")), TrackerRecommendationKind.SIMILAR_MANGA,
	)
}
