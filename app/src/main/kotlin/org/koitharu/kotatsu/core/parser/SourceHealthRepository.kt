package org.koitharu.kotatsu.core.parser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passive source-health memory used by scheduled library updates.
 *
 * It learns only from checks Miyorare already performs. No polling, startup scan, alarm, or extra
 * request is added. Old observations are discarded before a new sample is folded in so one stale
 * slow request cannot keep penalising a source for another week.
 */
@Singleton
class SourceHealthRepository @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	enum class State {
		UNKNOWN,
		HEALTHY,
		SLOW,
		UNSTABLE,
	}

	data class Snapshot(
		val state: State,
		val averageLatencyMs: Long,
		val consecutiveFailures: Int,
		val successCount: Int,
		val failureCount: Int,
		val lastObservedAt: Long,
	)

	fun snapshotForScheduling(source: MangaSource, now: Long = System.currentTimeMillis()): Snapshot {
		val key = prefix(source)
		val lastObserved = prefs.getLong(key + LAST_OBSERVED, 0L)
		if (!isFresh(lastObserved, now)) {
			return Snapshot(State.UNKNOWN, 0L, 0, 0, 0, lastObserved)
		}
		val latency = prefs.getLong(key + LATENCY, 0L).coerceAtLeast(0L)
		val failures = prefs.getInt(key + FAILURE_STREAK, 0).coerceAtLeast(0)
		val successCount = prefs.getInt(key + SUCCESSES, 0).coerceAtLeast(0)
		val failureCount = prefs.getInt(key + FAILURES, 0).coerceAtLeast(0)
		val observations = successCount + failureCount
		val state = when {
			failures >= 2 -> State.UNSTABLE
			observations >= MIN_SLOW_OBSERVATIONS && latency >= SLOW_LATENCY_MS -> State.SLOW
			successCount > 0 -> State.HEALTHY
			else -> State.UNKNOWN
		}
		return Snapshot(state, latency, failures, successCount, failureCount, lastObserved)
	}

	@Synchronized
	fun recordSuccess(source: MangaSource, latencyMs: Long) {
		val now = System.currentTimeMillis()
		val key = prefix(source)
		val fresh = isFresh(prefs.getLong(key + LAST_OBSERVED, 0L), now)
		val previousLatency = if (fresh) prefs.getLong(key + LATENCY, 0L) else 0L
		val previousSuccesses = if (fresh) prefs.getInt(key + SUCCESSES, 0) else 0
		val previousFailures = if (fresh) prefs.getInt(key + FAILURES, 0) else 0
		val latency = latencyMs.coerceAtLeast(0L)
		val average = if (previousLatency <= 0L) latency else ((previousLatency * 3L) + latency) / 4L
		prefs.edit()
			.putLong(key + LATENCY, average)
			.putInt(key + FAILURE_STREAK, 0)
			.putInt(key + SUCCESSES, (previousSuccesses + 1).coerceAtMost(MAX_COUNTER))
			.putInt(key + FAILURES, previousFailures.coerceAtMost(MAX_COUNTER))
			.putLong(key + LAST_OBSERVED, now)
			.apply()
	}

	@Synchronized
	fun recordFailure(source: MangaSource, latencyMs: Long) {
		val now = System.currentTimeMillis()
		val key = prefix(source)
		val fresh = isFresh(prefs.getLong(key + LAST_OBSERVED, 0L), now)
		val previousLatency = if (fresh) prefs.getLong(key + LATENCY, 0L) else 0L
		val previousFailureStreak = if (fresh) prefs.getInt(key + FAILURE_STREAK, 0) else 0
		val previousSuccesses = if (fresh) prefs.getInt(key + SUCCESSES, 0) else 0
		val previousFailures = if (fresh) prefs.getInt(key + FAILURES, 0) else 0
		val latency = latencyMs.coerceAtLeast(0L)
		val average = when {
			latency == 0L -> previousLatency
			previousLatency <= 0L -> latency
			else -> ((previousLatency * 3L) + latency) / 4L
		}
		prefs.edit()
			.putLong(key + LATENCY, average)
			.putInt(key + FAILURE_STREAK, (previousFailureStreak + 1).coerceAtMost(MAX_FAILURE_STREAK))
			.putInt(key + SUCCESSES, previousSuccesses.coerceAtMost(MAX_COUNTER))
			.putInt(key + FAILURES, (previousFailures + 1).coerceAtMost(MAX_COUNTER))
			.putLong(key + LAST_OBSERVED, now)
			.apply()
	}

	private fun isFresh(lastObserved: Long, now: Long): Boolean =
		lastObserved > 0L && now >= lastObserved && now - lastObserved <= SCHEDULER_STALE_AFTER_MS

	private fun prefix(source: MangaSource): String = source.name + ':'

	private companion object {
		const val PREFS_NAME = "source_health"
		const val LATENCY = "latency"
		const val FAILURE_STREAK = "failure_streak"
		const val SUCCESSES = "successes"
		const val FAILURES = "failures"
		const val LAST_OBSERVED = "last_observed"
		const val MAX_COUNTER = 1_000
		const val MAX_FAILURE_STREAK = 10
		const val MIN_SLOW_OBSERVATIONS = 2
		const val SLOW_LATENCY_MS = 3_500L
		const val SCHEDULER_STALE_AFTER_MS = 7L * 24L * 60L * 60L * 1_000L
	}
}
