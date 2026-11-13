package org.koitharu.kotatsu.history.ui

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Local-calendar buckets; boundaries follow midnight even across DST transitions. */
internal data class HistoryDateOverview(
	val recentCount: Int,
	val days: Map<LocalDate, Int>,
	val months: Map<YearMonth, Int>,
) {
	val isEmpty: Boolean get() = recentCount == 0 && days.isEmpty() && months.isEmpty()
}

internal data class HistoryTimeWindow(val start: Long, val endExclusive: Long)

internal fun historyDateOverview(timestamps: List<Long>, today: LocalDate, zone: ZoneId): HistoryDateOverview {
	var recent = 0
	val days = mutableMapOf<LocalDate, Int>()
	val months = mutableMapOf<YearMonth, Int>()
	for (timestamp in timestamps) {
		val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
		val age = ChronoUnit.DAYS.between(date, today)
		when {
			age <= 7L -> recent++ // Includes future-dated rows; keep the existing Unknown label.
			age <= 30L -> days[date] = (days[date] ?: 0) + 1
			else -> {
				val month = YearMonth.from(date)
				months[month] = (months[month] ?: 0) + 1
			}
		}
	}
	return HistoryDateOverview(recent, days, months)
}

internal fun historyGroupWindow(key: String, today: LocalDate, zone: ZoneId): HistoryTimeWindow? {
	val cutoff = today.minusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()
	return when {
		key.startsWith("day:") -> {
			val date = runCatching { LocalDate.parse(key.removePrefix("day:")) }.getOrNull() ?: return null
			val age = ChronoUnit.DAYS.between(date, today)
			if (age !in 8L..30L) return null
			HistoryTimeWindow(
				date.atStartOfDay(zone).toInstant().toEpochMilli(),
				date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
			)
		}
		key.startsWith("month:") -> {
			val month = runCatching { YearMonth.parse(key.removePrefix("month:")) }.getOrNull() ?: return null
			val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
			val end = minOf(month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), cutoff)
			if (start >= end) return null
			HistoryTimeWindow(start, end)
		}
		else -> null
	}
}

/** Keep bucket order consistent with Last Read direction. */
internal enum class HistoryDateSection { RECENT, DAYS_8_30, OLDER }

internal fun historySectionOrder(ascending: Boolean): List<HistoryDateSection> =
	if (ascending) listOf(HistoryDateSection.OLDER, HistoryDateSection.DAYS_8_30, HistoryDateSection.RECENT)
	else listOf(HistoryDateSection.RECENT, HistoryDateSection.DAYS_8_30, HistoryDateSection.OLDER)
