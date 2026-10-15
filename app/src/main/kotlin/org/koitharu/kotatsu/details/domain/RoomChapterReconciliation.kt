package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.MangaChapter

/** Reconciles only the Room chapter payload; Details/source and Local retain their other state. */
internal suspend fun MutableStateFlow<MangaDetails?>.reconcileRoomChapters(
	chapters: List<MangaChapter>,
	loadingJob: () -> Job,
	isScanlatorsMerged: suspend () -> Boolean,
): MangaDetails? {
	while (true) {
		currentCoroutineContext().ensureActive()
		val observedLoad = loadingJob()
		val current = value
		val currentSourceChapters = current?.sourceManga?.chapters.orEmpty()
		// A committed Room update can fill the source slot while source/local enrichment is still
		// running. Already renderable source chapters must keep the normal load barrier.
		val canPromoteDuringLoad = current != null && !current.isLocal &&
			chapters.isNotEmpty() && currentSourceChapters.isEmpty()
		if (observedLoad.isActive && !canPromoteDuringLoad) {
			observedLoad.join()
			continue
		}
		if (current == null || current.isLocal) return null
		// Cache cleanup must not blank a renderable source list; copy also retains Local overlays.
		if (chapters.isEmpty() && currentSourceChapters.isNotEmpty()) return null

		var updated = current.copy(manga = current.sourceManga.copy(chapters = chapters))
		if (isScanlatorsMerged()) {
			updated = updated.withMergedBranches()
		}
		// The preference lookup suspends. Retry against any newer load/override/Local state, and
		// never let a cancelled mapLatest emission publish after a newer Room emission arrives.
		currentCoroutineContext().ensureActive()
		if (loadingJob() !== observedLoad || value !== current) continue
		if (updated.sourceManga.chapters == current.sourceManga.chapters) return null
		if (!compareAndSet(current, updated)) continue
		return updated
	}
}
