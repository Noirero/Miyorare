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

		// Rows are ordered by last-check time, but adaptive cooldowns intentionally differ by title.
		// Scan small windows until enough due rows are found so a block of old/slow titles cooling down
		// for days cannot hide an active title that is already due. Memory stays bounded to one window
		// plus the final worker batch even for very large libraries.
		val selected = ArrayList<MangaTracking>(limit)
		var offset = 0
		while (selected.size < limit && offset < MAX_CANDIDATE_SCAN) {
			val windowSize = minOf(CANDIDATE_WINDOW_SIZE, MAX_CANDIDATE_SCAN - offset)
			val candidates = repository.getTracks(offset = offset, limit = windowSize)
			if (candidates.isEmpty()) break
			selected += smartUpdatePolicy.select(candidates, limit - selected.size)
			offset += candidates.size
			if (candidates.size < windowSize) break
		}
		return selected
	}

	private companion object {
		const val CANDIDATE_WINDOW_SIZE = 128
		const val MAX_CANDIDATE_SCAN = 512
	}
}
