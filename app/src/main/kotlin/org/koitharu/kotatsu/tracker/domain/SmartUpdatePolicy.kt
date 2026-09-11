package org.koitharu.kotatsu.tracker.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.parser.SourceHealthRepository
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import java.time.Instant
import javax.inject.Inject
import kotlin.math.max

/**
 * Passive adaptive policy for scheduled library updates.
 *
 * It never schedules extra work by itself. The existing TrackWorker asks this policy which rows are
 * actually due. Manual/full checks intentionally bypass it.
 */
@Reusable
class SmartUpdatePolicy @Inject constructor(
	private val sourceHealthRepository: SourceHealthRepository,
) {

	fun select(
		candidates: List<MangaTracking>,
		limit: Int,
		now: Instant = Instant.now(),
	): List<MangaTracking> {
		if (candidates.isEmpty() || limit <= 0) return emptyList()
		val nowMs = now.toEpochMilli()
		return candidates.asSequence()
			.map { tracking ->
				val interval = intervalMs(tracking, nowMs)
				val lastCheck = tracking.lastCheck?.toEpochMilli() ?: 0L
				val dueAt = if (lastCheck <= 0L) 0L else lastCheck + interval
				DueCandidate(
					tracking = tracking,
					dueAt = dueAt,
					overdueMs = if (dueAt == 0L) Long.MAX_VALUE else nowMs - dueAt,
				)
			}
			.filter { it.dueAt == 0L || nowMs >= it.dueAt }
			.sortedWith(
				compareByDescending<DueCandidate> { it.overdueMs }
					.thenBy { it.tracking.lastCheck?.toEpochMilli() ?: 0L },
			)
			.take(limit)
			.map { it.tracking }
			.toList()
	}

	private fun intervalMs(tracking: MangaTracking, nowMs: Long): Long {
		val lastChapterDate = tracking.lastChapterDate?.toEpochMilli()
		val chapterAge = lastChapterDate?.let { (nowMs - it).coerceAtLeast(0L) }
		var base = when {
			tracking.lastCheck == null -> 0L
			tracking.newChapters > 0 -> DAY_MS
			// Missing upload dates are common on otherwise healthy/current sources. Treat "unknown" as
			// medium activity rather than falsely classifying the manga as abandoned for three days.
			chapterAge == null -> 12L * HOUR_MS
			chapterAge <= 14L * DAY_MS -> 6L * HOUR_MS
			chapterAge <= 60L * DAY_MS -> 12L * HOUR_MS
			chapterAge <= 180L * DAY_MS -> DAY_MS
			else -> 3L * DAY_MS
		}
		if (base == 0L) return 0L

		val health = sourceHealthRepository.snapshotForScheduling(tracking.manga.source)
		base = when (health.state) {
			SourceHealthRepository.State.HEALTHY -> base
			SourceHealthRepository.State.UNKNOWN -> base
			SourceHealthRepository.State.SLOW -> base + base / 2L
			SourceHealthRepository.State.UNSTABLE -> base * 2L
		}
		return max(MIN_INTERVAL_MS, base).coerceAtMost(MAX_INTERVAL_MS)
	}

	private data class DueCandidate(
		val tracking: MangaTracking,
		val dueAt: Long,
		val overdueMs: Long,
	)

	private companion object {
		const val HOUR_MS = 60L * 60L * 1_000L
		const val DAY_MS = 24L * HOUR_MS
		const val MIN_INTERVAL_MS = 2L * HOUR_MS
		const val MAX_INTERVAL_MS = 7L * DAY_MS
	}
}
