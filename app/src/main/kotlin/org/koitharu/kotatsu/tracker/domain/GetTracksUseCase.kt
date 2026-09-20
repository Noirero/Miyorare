package org.koitharu.kotatsu.tracker.domain

import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import javax.inject.Inject

class GetTracksUseCase @Inject constructor(
	private val repository: TrackingRepository,
	private val smartUpdatePolicy: SmartUpdatePolicy,
) {

	suspend operator fun invoke(limit: Int): List<MangaTracking> {
		repository.updateTracks()
		if (limit == Int.MAX_VALUE) {
			return repository.getTracks(offset = 0, limit = Int.MAX_VALUE)
		}
		if (limit <= 0) return emptyList()

		// Adaptive cooldowns mean the next due row is not guaranteed to be in the first DB page.
		// Walk bounded windows until the worker batch is full or the eligible table ends. Only one
		// source window and the selected batch are retained, preventing starvation without loading
		// an arbitrarily large library into memory.
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
