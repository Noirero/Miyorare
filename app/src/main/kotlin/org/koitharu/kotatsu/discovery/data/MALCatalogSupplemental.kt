package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import org.koitharu.kotatsu.discovery.domain.CatalogFailure
import org.koitharu.kotatsu.discovery.domain.CatalogFailureKind
import org.koitharu.kotatsu.discovery.domain.CatalogException
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogCharacterDetails
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogCharacterWork
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogPerson
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogScoreDistribution
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogStatistics
import org.koitharu.kotatsu.scrobbling.common.data.array
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.text
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

private const val JIKAN_API_BASE = "https://api.jikan.moe/v4"
private const val MAL_PUBLIC_FALLBACK_BASE = "https://jikan.lucashdo.com/v1"
private const val MAL_SUPPLEMENTAL_ORIGIN_TIMEOUT_MS = 6_000L

internal data class MALCatalogSupplemental(
	val characters: List<TrackerCatalogPerson>,
	val statistics: TrackerCatalogStatistics?,
	val charactersFailure: CatalogFailure? = null,
	val statisticsFailure: CatalogFailure? = null,
) {
	val partial: Boolean get() = charactersFailure != null || statisticsFailure != null
}

internal suspend fun loadMALCharacters(http: CatalogHttp, key: TrackerCatalogKey): List<TrackerCatalogPerson> {
	require(key.service == ScrobblerService.MAL)
	return loadMALSupplementalCollection(http, key, "characters", ::parseMALCharacters)
}

internal suspend fun loadMALStatistics(http: CatalogHttp, key: TrackerCatalogKey): TrackerCatalogStatistics {
	require(key.service == ScrobblerService.MAL)
	return loadMALSupplementalCollection(http, key, "statistics", ::parseMALStatistics)
}

internal suspend fun loadMALCharacterDetails(
	http: CatalogHttp,
	person: TrackerCatalogPerson,
): TrackerCatalogCharacterDetails {
	val id = person.id?.toLongOrNull()?.takeIf { it > 0L }
		?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	return loadMALSupplementalResource(
		http = http,
		primary = Request.Builder().url("$JIKAN_API_BASE/characters/$id/full").header("Accept", "application/json").get().build(),
		fallback = Request.Builder().url("$MAL_PUBLIC_FALLBACK_BASE/characters/$id/full").header("Accept", "application/json").get().build(),
	) { root -> parseMALCharacterDetails(root, person) }
}

private suspend fun <T> loadMALSupplementalCollection(
	http: CatalogHttp,
	key: TrackerCatalogKey,
	collection: String,
	parser: (JsonObject) -> T,
): T = loadMALSupplementalResource(
	http = http,
	primary = supplementalRequest(JIKAN_API_BASE, key, collection),
	fallback = supplementalRequest(MAL_PUBLIC_FALLBACK_BASE, key, collection),
	parser = parser,
)

private suspend fun requestMALSupplemental(http: CatalogHttp, request: Request): JsonObject = try {
	withTimeout(MAL_SUPPLEMENTAL_ORIGIN_TIMEOUT_MS) {
		http.request(ScrobblerService.MAL, request)
	}
} catch (e: TimeoutCancellationException) {
	throw CatalogException(CatalogFailure(CatalogFailureKind.PROVIDER), e)
}

private suspend fun <T> loadMALSupplementalResource(
	http: CatalogHttp,
	primary: Request,
	fallback: Request,
	parser: (JsonObject) -> T,
): T {
	val primaryFailure = try {
		return parser(requestMALSupplemental(http, primary))
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		e
	}
	try {
		return parser(requestMALSupplemental(http, fallback))
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		e.addSuppressed(primaryFailure)
		throw e
	}
}

private fun supplementalRequest(base: String, key: TrackerCatalogKey, collection: String): Request = Request.Builder()
	.url("$base/manga/${key.remoteId}/$collection")
	.header("Accept", "application/json")
	.get()
	.build()

internal fun parseMALCharacters(root: JsonObject): List<TrackerCatalogPerson> {
	val rows = root.array("data") ?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	return rows.mapNotNull { value ->
		val edge = value as? JsonObject ?: return@mapNotNull null
		val character = edge.obj("character") ?: edge
		val id = character.catalogLong("mal_id", "malId")?.takeIf { it > 0L }?.toString() ?: return@mapNotNull null
		val name = character.text("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
		TrackerCatalogPerson(
			id = id,
			name = name,
			imageUrl = character.catalogImage(),
			roles = listOfNotNull((edge.text("role") ?: character.text("role"))?.trim()?.takeIf(String::isNotEmpty)),
			providerUrl = character.text("url") ?: "https://myanimelist.net/character/$id",
		)
	}.distinctBy { it.id }
}

internal fun parseMALStatistics(root: JsonObject): TrackerCatalogStatistics {
	val data = root.obj("data") ?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	val status = data.obj("status") ?: data
	val scores = data.array("scores").orEmpty().mapNotNull { value ->
		val row = value as? JsonObject ?: return@mapNotNull null
		val score = row.catalogLong("score")?.toInt()?.takeIf { it in 1..10 } ?: return@mapNotNull null
		val votes = row.catalogLong("votes") ?: return@mapNotNull null
		val percentage = row.catalogDouble("percentage")?.takeIf { it in 0.0..100.0 }
		TrackerCatalogScoreDistribution(score, votes, percentage)
	}.distinctBy { it.score }.sortedByDescending { it.score }
	val statistics = TrackerCatalogStatistics(
		reading = status.catalogLong("reading", "inProgress"),
		completed = status.catalogLong("completed"),
		onHold = status.catalogLong("on_hold", "onHold"),
		dropped = status.catalogLong("dropped"),
		planToRead = status.catalogLong("plan_to_read", "planToRead", "planned"),
		total = status.catalogLong("total"),
		scores = scores,
	)
	if (listOf(statistics.reading, statistics.completed, statistics.onHold, statistics.dropped, statistics.planToRead, statistics.total).all { it == null } && scores.isEmpty()) {
		throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	}
	return statistics
}

internal fun parseMALCharacterDetails(
	root: JsonObject,
	seed: TrackerCatalogPerson,
): TrackerCatalogCharacterDetails {
	val data = root.obj("data") ?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	val id = data.catalogLong("mal_id", "malId")?.takeIf { it > 0L }?.toString()
		?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	if (seed.id != null && seed.id != id) throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	val name = data.text("name")?.trim()?.takeIf(String::isNotEmpty)
		?: seed.name.takeIf(String::isNotBlank)
		?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	val person = TrackerCatalogPerson(
		id = id,
		name = name,
		imageUrl = data.catalogImage() ?: seed.imageUrl,
		roles = seed.roles,
		providerUrl = data.text("url") ?: seed.providerUrl ?: "https://myanimelist.net/character/$id",
	)
	val works = buildList {
		addAll(parseMALCharacterWorks(data, "manga", CatalogRelatedMediaKind.MANGA))
		addAll(parseMALCharacterWorks(data, "anime", CatalogRelatedMediaKind.ANIME))
	}.distinctBy { "${it.mediaKind}:${it.remoteId}" }
	return TrackerCatalogCharacterDetails(
		person = person,
		nativeName = data.catalogText("name_kanji", "nameKanji"),
		nicknames = data.catalogStrings("nicknames"),
		about = data.text("about")?.trim()?.takeIf(String::isNotEmpty),
		favorites = data.catalogLong("favorites"),
		voices = parseMALCharacterVoices(data),
		works = works,
	)
}

private fun parseMALCharacterWorks(
	data: JsonObject,
	key: String,
	kind: CatalogRelatedMediaKind,
): List<TrackerCatalogCharacterWork> = data.array(key).orEmpty().mapNotNull { value ->
	val edge = value as? JsonObject ?: return@mapNotNull null
	val node = edge.obj(key) ?: edge
	val id = node.catalogLong("mal_id", "malId")?.takeIf { it > 0L }?.toString() ?: return@mapNotNull null
	val title = node.text("title")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
	val providerPath = if (kind == CatalogRelatedMediaKind.ANIME) "anime" else "manga"
	TrackerCatalogCharacterWork(
		service = ScrobblerService.MAL,
		remoteId = id,
		title = title,
		coverUrl = node.catalogImage(),
		providerUrl = node.text("url") ?: "https://myanimelist.net/$providerPath/$id",
		mediaKind = kind,
		role = edge.text("role") ?: node.text("role"),
	)
}

private fun parseMALCharacterVoices(data: JsonObject): List<TrackerCatalogPerson> = data.array("voices").orEmpty().mapNotNull { value ->
	val edge = value as? JsonObject ?: return@mapNotNull null
	val node = edge.obj("person") ?: edge
	val id = node.catalogLong("mal_id", "malId")?.takeIf { it > 0L }?.toString() ?: return@mapNotNull null
	val name = node.text("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
	val language = edge.text("language") ?: node.text("language")
	TrackerCatalogPerson(
		id = id,
		name = name,
		imageUrl = node.catalogImage(),
		roles = listOfNotNull(language?.trim()?.takeIf(String::isNotEmpty)),
		providerUrl = node.text("url") ?: "https://myanimelist.net/people/$id",
	)
}.distinctBy { it.id }

private fun JsonObject.catalogImage(): String? {
	val images = obj("images")
	return images?.obj("jpg")?.catalogText("large_image_url", "largeImageUrl", "image_url", "imageUrl", "small_image_url", "smallImageUrl")
		?: images?.obj("webp")?.catalogText("large_image_url", "largeImageUrl", "image_url", "imageUrl", "small_image_url", "smallImageUrl")
		?: images?.catalogText("large", "medium", "small")
		?: catalogText("image_url", "imageUrl")
}

private fun JsonObject.catalogText(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
	text(key)?.trim()?.takeIf(String::isNotEmpty)
}

private fun JsonObject.catalogLong(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
	(this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L }
}
