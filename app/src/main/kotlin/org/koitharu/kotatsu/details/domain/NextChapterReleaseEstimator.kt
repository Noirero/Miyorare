package org.koitharu.kotatsu.details.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class NextChapterReleasePrediction(
	val expectedDate: LocalDate,
	val daysUntilNext: Long,
)

/**
 * Estimates availability at the current source, not an official publication schedule.
 * uploadDate is milliseconds, but native/Mihon/Tsuki parsers do not carry date precision or
 * source timezone metadata. Some dates are parsed midnights or relative dates with synthetic
 * times. Use the same local calendar as chapter-date presentation, never elapsed 24-hour units.
 */
object NextChapterReleaseEstimator {

	private const val MAX_EVENTS = 12
	private const val MIN_EVENTS = 4

	fun predict(uploadDates: Iterable<Long>, nowMillis: Long, zone: ZoneId): NextChapterReleasePrediction? {
		if (nowMillis <= 0L) return null
		val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
		val events = uploadDates.asSequence()
			.filter { it > 0L && it <= nowMillis }
			.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
			.distinct()
			.sorted()
			.toList()
			.takeLast(MAX_EVENTS)
		if (events.size < MIN_EVENTS) return null

		val intervals = events.zipWithNext { previous, next -> ChronoUnit.DAYS.between(previous, next) }
		if (intervals.any { it <= 0L }) return null
		val sorted = intervals.sorted()
		// Round a half-day median upward: the input does not justify sub-day precision.
		val cadence = if (sorted.size % 2 == 0) {
			(sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2] + 1L) / 2L
		} else {
			sorted[sorted.size / 2]
		}
		val tolerance = (cadence / 4L).coerceAtLeast(1L)
		val consistent = intervals.count { kotlin.math.abs(it - cadence) <= tolerance }
		if (consistent * 4 < intervals.size * 3) return null

		val latest = events.last()
		val elapsed = ChronoUnit.DAYS.between(latest, today)
		if (elapsed > cadence * 3L) return null
		val expected = latest.plusDays(cadence)
		val remaining = ChronoUnit.DAYS.between(today, expected)
		// A missed estimate is not a new countdown. Fail closed even before the hiatus-like limit.
		if (remaining < 0L) return null
		return NextChapterReleasePrediction(expected, remaining)
	}
}
