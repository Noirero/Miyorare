package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRecommendation
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerTarget

/** An empty candidate list means title-seeded search; multiple candidates require a choice. */
internal data class RecommendationResolution<T>(val recommendation: TrackerRecommendation, val candidates: List<T>)

/** Only a persisted provider association can resolve a target. This path has no write operation. */
internal suspend fun <T> resolveTrackerRecommendation(
	recommendation: TrackerRecommendation,
	findAssociated: suspend (TrackerTarget) -> List<T>,
	identity: (T) -> Long,
	isCurrent: suspend () -> Boolean,
): RecommendationResolution<T>? {
	currentCoroutineContext().ensureActive()
	if (!isCurrent()) return null
	val candidates = findAssociated(recommendation.target).distinctBy(identity)
	currentCoroutineContext().ensureActive()
	if (!isCurrent()) return null
	return RecommendationResolution(recommendation, candidates)
}
