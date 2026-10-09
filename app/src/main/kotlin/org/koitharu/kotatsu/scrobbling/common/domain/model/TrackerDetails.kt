package org.koitharu.kotatsu.scrobbling.common.domain.model

/** Provider catalog identity, never a parser/local manga id or a tracker rate id. */
data class TrackerTarget(val service: ScrobblerService, val id: String, val url: String? = null) {
	/** URL is provenance metadata; association/cache identity is always provider plus catalog id. */
	val identity: Pair<ScrobblerService, String> get() = service to id
	init {
		require(id.toLongOrNull()?.let { it > 0 && it.toString() == id } == true)
	}
}

enum class TrackerContent { CHARACTERS, STAFF, RECOMMENDATIONS }

/** One bounded provider page. Kitsu supplies an opaque related-collection link. */
data class TrackerPage(val number: Int = 1, val url: String? = null) {
	init { require(number in 1..1000) }
}

data class TrackerPerson(
	val id: String?,
	val name: String,
	val image: String?,
	val roles: List<String>,
)

enum class TrackerRecommendationKind { RECOMMENDATION, SIMILAR_MANGA }

data class TrackerRecommendation(
	val target: TrackerTarget,
	val title: String,
	val image: String?,
	val kind: TrackerRecommendationKind,
)

enum class TrackerPartialReason { PROVIDER_ERROR, MISSING_RECORDS, TRUNCATED }

sealed interface TrackerResult<out T> {
	data object NotRequested : TrackerResult<Nothing>
	data object Unsupported : TrackerResult<Nothing>
	data class Empty(val next: TrackerPage? = null) : TrackerResult<Nothing>
	data class Success<T>(val items: List<T>, val next: TrackerPage? = null) : TrackerResult<T>
	data class Partial<T>(
		val items: List<T>,
		val reasons: Set<TrackerPartialReason>,
		val next: TrackerPage? = null,
	) : TrackerResult<T>
	/** Keep the cause for diagnosis, without logging provider bodies or credentials. */
	data class Error(val cause: Exception) : TrackerResult<Nothing>
}

enum class TrackerDetailsReadState { SUPPRESSED, NO_MAPPING, AUTH_REQUIRED, UNSUPPORTED, LOADED }

data class AssociatedTrackerDetails(
	val service: ScrobblerService,
	val target: TrackerTarget?,
	val state: TrackerDetailsReadState,
	val characters: TrackerResult<TrackerPerson> = TrackerResult.NotRequested,
	val staff: TrackerResult<TrackerPerson> = TrackerResult.NotRequested,
	val recommendations: TrackerResult<TrackerRecommendation> = TrackerResult.NotRequested,
)

/** Callers must explicitly permit optional reads after checking their current privacy/source state. */
data class TrackerDetailsReadPolicy(
	val enabled: Boolean = false,
	val incognito: Boolean = true,
	val privateOnly: Boolean = true,
	val onDevice: Boolean = true,
) {
	val allowsNetwork: Boolean get() = enabled && !incognito && !privateOnly && !onDevice
}
