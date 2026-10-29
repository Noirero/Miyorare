package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import org.koitharu.kotatsu.discovery.domain.CatalogFailure
import org.koitharu.kotatsu.discovery.domain.CatalogFailureKind
import org.koitharu.kotatsu.discovery.domain.CatalogException
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

internal data class MALCatalogSupplemental(
	val characters: List<TrackerCatalogPerson>,
	val statistics: TrackerCatalogStatistics?,
	val partial: Boolean,
)

internal suspend fun loadMALCatalogSupplemental(http: CatalogHttp, key: TrackerCatalogKey): MALCatalogSupplemental = coroutineScope {
	require(key.service == ScrobblerService.MAL)
	val characters = async {
		optionalMALSupplemental {
			loadMALSupplementalCollection(http, key, "characters", ::parseMALCharacters)
		}
	}
	val statistics = async {
		optionalMALSupplemental {
			loadMALSupplementalCollection(http, key, "statistics", ::parseMALStatistics)
		}
	}
	val characterResult = characters.await()
	val statisticsResult = statistics.await()
	MALCatalogSupplemental(
		characters = characterResult.getOrDefault(emptyList()),
		statistics = statisticsResult.getOrNull(),
		partial = characterResult.isFailure || statisticsResult.isFailure,
	)
}

private suspend fun <T> loadMALSupplementalCollection(
	http: CatalogHttp,
	key: TrackerCatalogKey,
	collection: String,
	parser: (JsonObject) -> T,
): T {
	val primaryFailure = try {
		return parser(http.request(ScrobblerService.MAL, supplementalRequest(JIKAN_API_BASE, key, collection)))
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		e
	}
	try {
		return parser(http.request(ScrobblerService.MAL, supplementalRequest(MAL_PUBLIC_FALLBACK_BASE, key, collection)))
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

private suspend fun <T> optionalMALSupplemental(block: suspend () -> T): Result<T> = try {
	Result.success(block())
} catch (e: CancellationException) {
	throw e
} catch (e: Exception) {
	Result.failure(e)
}

internal fun parseMALCharacters(root: JsonObject): List<TrackerCatalogPerson> {
	val rows = root.array("data") ?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	return rows.mapNotNull { value ->
		val edge = value as? JsonObject ?: return@mapNotNull null
		val character = edge.obj("character") ?: return@mapNotNull null
		val id = character.catalogLong("mal_id", "malId")?.takeIf { it > 0L }?.toString() ?: return@mapNotNull null
		val name = character.text("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
		val imageUrl = character.obj("images")?.let { images ->
			images.obj("jpg")?.text("image_url")
				?: images.obj("webp")?.text("image_url")
				?: images.text("large")
				?: images.text("medium")
				?: images.text("small")
		}
		TrackerCatalogPerson(
			id = id,
			name = name,
			imageUrl = imageUrl,
			roles = listOfNotNull(edge.text("role")?.trim()?.takeIf(String::isNotEmpty)),
			providerUrl = character.text("url"),
		)
	}.distinctBy { it.id }
}

internal fun parseMALStatistics(root: JsonObject): TrackerCatalogStatistics {
	val data = root.obj("data") ?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	val scores = data.array("scores").orEmpty().mapNotNull { value ->
		val row = value as? JsonObject ?: return@mapNotNull null
		val score = row.catalogLong("score")?.toInt()?.takeIf { it in 1..10 } ?: return@mapNotNull null
		val votes = row.catalogLong("votes") ?: return@mapNotNull null
		val percentage = row.catalogDouble("percentage")?.takeIf { it in 0.0..100.0 }
		TrackerCatalogScoreDistribution(score, votes, percentage)
	}.distinctBy { it.score }.sortedByDescending { it.score }
	val statistics = TrackerCatalogStatistics(
		reading = data.catalogLong("reading"),
		completed = data.catalogLong("completed"),
		onHold = data.catalogLong("on_hold", "onHold"),
		dropped = data.catalogLong("dropped"),
		planToRead = data.catalogLong("plan_to_read", "planToRead"),
		total = data.catalogLong("total"),
		scores = scores,
	)
	if (listOf(statistics.reading, statistics.completed, statistics.onHold, statistics.dropped, statistics.planToRead, statistics.total).all { it == null } && scores.isEmpty()) {
		throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
	}
	return statistics
}

private fun JsonObject.catalogLong(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
	(this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L }
}
