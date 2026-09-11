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

		// Adaptive cooldowns mean due order is no longer identical to last-check order. Walk bounded
		// DB windows until the worker batch is full or the eligible table ends. Only one 128-row window
		// plus the final batch is retained in memory, so even a very large library cannot starve a due
		// active title merely because hundreds of older titles ahead of it are still cooling down.
		val selected = ArrayList<MangaTracking>(limit)
		var offset = 0
		while (selected.size < limit) {
			val candidates = repository.getTracks(offset = offset, limit = CANDIDATE_WINDOW_SIZE)
			if (candidates.isEmpty()) break
			selected += smartUpdatePolicy.select(candidates, limit - selected.size)
			offset += candidates.size
			if (candidates.size < CANDIDATE_WINDOW_SIZE) break
		}
		return selected
	}

	private companion object {
		const val CANDIDATE_WINDOW_SIZE = 128
	}
}
