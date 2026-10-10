package org.koitharu.kotatsu.scrobbling.common.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

private val unchangedSession: StateFlow<Long> = MutableStateFlow(0L)

/** Optional read-only capability on existing provider repositories; no tracking writes. */
interface TrackerDetailsProvider {
	val detailsService: ScrobblerService
	val detailsCapabilities: Set<TrackerContent>
	val isAuthorized: Boolean
	/** Screen consumers observe account/logout/token replacement without reading credentials. */
	val detailsSessionGeneration: StateFlow<Long> get() = unchangedSession
	suspend fun loadCharacters(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> = TrackerResult.Unsupported
	suspend fun loadStaff(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> = TrackerResult.Unsupported
	suspend fun loadRecommendations(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerRecommendation> = TrackerResult.Unsupported
}
