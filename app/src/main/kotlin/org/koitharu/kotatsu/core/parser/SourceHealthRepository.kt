package org.koitharu.kotatsu.core.parser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passive source-health memory.
 *
 * Health is learned only from source work the user already requested; there is no polling, worker,
 * alarm, or startup scan. Interactive source ranking decays quickly, while the scheduled updater can
 * use a longer observation window so a 3-7 day cooldown does not forget the reason for that cooldown.
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

	/** Short-lived view used by interactive source ordering/Source Fusion. */
	fun snapshot(source: MangaSource, now: Long = System.currentTimeMillis()): Snapshot =
		snapshot(source, now, INTERACTIVE_STALE_AFTER_MS)

	/** Longer-lived view used only by Smart Update scheduling. */
	fun snapshotForScheduling(source: MangaSource, now: Long = System.currentTimeMillis()): Snapshot =
		snapshot(source, now, SCHEDULER_STALE_AFTER_MS)

	private fun snapshot(source: MangaSource, now: Long, staleAfterMs: Long): Snapshot {
		val key = prefix(source)
		val lastObserved = prefs.getLong(key + LAST_OBSERVED, 0L)
		if (lastObserved == 0L || now - lastObserved > staleAfterMs) {
			return Snapshot(State.UNKNOWN, 0L, 0, 0, 0, lastObserved)
		}
		val latency = prefs.getLong(key + LATENCY, 0L).coerceAtLeast(0L)
		val failures = prefs.getInt(key + FAILURE_STREAK, 0).coerceAtLeast(0)
		val successCount = prefs.getInt(key + SUCCESSES, 0).coerceAtLeast(0)
		val failureCount = prefs.getInt(key + FAILURES, 0).coerceAtLeast(0)
		val state = when {
			failures >= 2 -> State.UNSTABLE
			latency >= SLOW_LATENCY_MS -> State.SLOW
			successCount > 0 -> State.HEALTHY
			else -> State.UNKNOWN
		}
		return Snapshot(state, latency, failures, successCount, failureCount, lastObserved)
	}

	/** Lower is better and safe to use as one component of interactive source ordering. */
	fun rankingPenalty(source: MangaSource): Int {
		val health = snapshot(source)
		return when (health.state) {
			State.HEALTHY -> if (health.averageLatencyMs >= MEDIUM_LATENCY_MS) 20 else 0
			State.UNKNOWN -> 25
			State.SLOW -> 80
			State.UNSTABLE -> 180 + (health.consecutiveFailures.coerceAtMost(5) * 30)
		}
	}

	@Synchronized
	fun recordSuccess(source: MangaSource, latencyMs: Long) {
		val key = prefix(source)
		val previousLatency = prefs.getLong(key + LATENCY, 0L)
		val latency = latencyMs.coerceAtLeast(0L)
		val average = if (previousLatency <= 0L) latency else ((previousLatency * 3L) + latency) / 4L
		prefs.edit()
			.putLong(key + LATENCY, average)
			.putInt(key + FAILURE_STREAK, 0)
			.putInt(key + SUCCESSES, (prefs.getInt(key + SUCCESSES, 0) + 1).coerceAtMost(MAX_COUNTER))
			.putLong(key + LAST_OBSERVED, System.currentTimeMillis())
			.apply()
	}

	@Synchronized
	fun recordFailure(source: MangaSource, latencyMs: Long) {
		val key = prefix(source)
		val previousLatency = prefs.getLong(key + LATENCY, 0L)
		val latency = latencyMs.coerceAtLeast(0L)
		val average = when {
			latency == 0L -> previousLatency
			previousLatency <= 0L -> latency
			else -> ((previousLatency * 3L) + latency) / 4L
		}
		prefs.edit()
			.putLong(key + LATENCY, average)
			.putInt(key + FAILURE_STREAK, (prefs.getInt(key + FAILURE_STREAK, 0) + 1).coerceAtMost(MAX_FAILURE_STREAK))
			.putInt(key + FAILURES, (prefs.getInt(key + FAILURES, 0) + 1).coerceAtMost(MAX_COUNTER))
			.putLong(key + LAST_OBSERVED, System.currentTimeMillis())
			.apply()
	}

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
		const val MEDIUM_LATENCY_MS = 1_500L
		const val SLOW_LATENCY_MS = 3_500L
		const val INTERACTIVE_STALE_AFTER_MS = 24L * 60L * 60L * 1_000L
		const val SCHEDULER_STALE_AFTER_MS = 7L * 24L * 60L * 60L * 1_000L
	}
}
