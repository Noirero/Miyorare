package org.koitharu.kotatsu.scrobbling.common.domain

import org.koitharu.kotatsu.scrobbling.common.domain.model.*

/** Optional read-only capability on existing provider repositories; no tracking writes. */
interface TrackerDetailsProvider {
	val detailsService: ScrobblerService
	val detailsCapabilities: Set<TrackerContent>
	val isAuthorized: Boolean
	suspend fun loadCharacters(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> = TrackerResult.Unsupported
	suspend fun loadStaff(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> = TrackerResult.Unsupported
	suspend fun loadRecommendations(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerRecommendation> = TrackerResult.Unsupported
}
