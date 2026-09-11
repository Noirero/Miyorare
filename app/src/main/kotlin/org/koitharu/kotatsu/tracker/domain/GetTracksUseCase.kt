package org.koitharu.kotatsu.tracker.domain

import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import javax.inject.Inject

class GetTracksUseCase @Inject constructor(
	private val repository: TrackingRepository,
	private val smartUpdatePolicy: SmartUpdatePolicy,
) {

	suspend operator fun invoke(limit: Int): List<MangaTracking> {
		repository.updateTracks()
		// A user-triggered/full run must remain deterministic: "check now" still checks everything.
		if (limit == Int.MAX_VALUE) {
			return repository.getTracks(offset = 0, limit = Int.MAX_VALUE)
		}
		if (limit <= 0) return emptyList()

		// Pull a bounded oldest-first runway. The policy may skip rows that were checked recently;
		// taking more than the worker batch keeps an active/new title behind a few cooling-down rows
		// from being starved, without materialising a large library.
		val candidateLimit = (limit.toLong() * CANDIDATE_WINDOW_FACTOR)
			.coerceAtLeast(limit.toLong())
			.coerceAtMost(MAX_CANDIDATE_WINDOW.toLong())
			.toInt()
		val candidates = repository.getTracks(offset = 0, limit = candidateLimit)
		return smartUpdatePolicy.select(candidates, limit)
	}

	private companion object {
		const val CANDIDATE_WINDOW_FACTOR = 4L
		const val MAX_CANDIDATE_WINDOW = 256
	}
}
