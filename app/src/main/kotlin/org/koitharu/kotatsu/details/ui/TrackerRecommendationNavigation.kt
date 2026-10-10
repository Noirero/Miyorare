package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRecommendation
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerResult

internal data class TrackerRecommendationNavigation(
	val context: DetailsPeopleContext,
	val recommendation: TrackerRecommendation,
	val candidates: List<Manga>,
	val selected: Manga? = null,
	val providerUrl: String? = null,
)

internal fun TrackerResult<TrackerRecommendation>.recommendationItems(): List<TrackerRecommendation> = when (this) {
	is TrackerResult.Success -> items
	is TrackerResult.Partial -> items
	else -> emptyList()
}
