package org.koitharu.kotatsu.history.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class HistoryDateGroupingTest {
	private val today = LocalDate.of(2026, 11, 10)
	private val zone = ZoneId.of("Asia/Jakarta")

	private fun timestamp(daysAgo: Long) =
		today.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli()

	@Test
	fun recentAndCollapsibleBoundariesUseCalendarDays() {
		val groups = historyDateOverview(
			listOf(timestamp(0), timestamp(1), timestamp(2), timestamp(3), timestamp(7),
				timestamp(8), timestamp(8), timestamp(30), timestamp(31), timestamp(70)),
			today, zone,
		)
		assertEquals(5, groups.recentCount)
		assertEquals(2, groups.days[today.minusDays(8)])
		assertEquals(1, groups.days[today.minusDays(30)])
		assertEquals(1, groups.months[YearMonth.from(today.minusDays(31))])
		assertEquals(1, groups.months[YearMonth.from(today.minusDays(70))])
		assertFalse(groups.isEmpty)
	}

	@Test
	fun dayWindowIncludesOnlyTheSelectedDay() {
		val date = today.minusDays(8)
		val window = checkNotNull(historyGroupWindow("day:$date", today, zone))
		assertEquals(timestamp(8), window.start)
		assertEquals(timestamp(7), window.endExclusive)
		assertNull(historyGroupWindow("day:${today.minusDays(7)}", today, zone))
		assertNull(historyGroupWindow("day:${today.minusDays(31)}", today, zone))
	}

	@Test
	fun olderMonthIsClampedAtTheThirtyDayBoundary() {
		val month = YearMonth.of(2026, 10)
		val window = checkNotNull(historyGroupWindow("month:$month", today, zone))
		assertEquals(month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), window.start)
		assertEquals(timestamp(30), window.endExclusive)
		assertNull(historyGroupWindow("month:2026-11", today, zone))
		assertTrue(historyDateOverview(emptyList(), today, zone).isEmpty)
	}

	@Test
	fun daylightSavingDayWindowIsNotAssumedToBeTwentyFourHours() {
		val dstZone = ZoneId.of("America/New_York")
		val date = LocalDate.of(2026, 3, 8)
		val window = checkNotNull(historyGroupWindow("day:$date", date.plusDays(8), dstZone))
		assertEquals(23L * 60 * 60 * 1000, window.endExclusive - window.start)
	}

	@Test
	fun moreHeadersFromDifferentDaysHaveDistinctRecyclerViewIdentity() {
		val first = org.koitharu.kotatsu.list.ui.model.ListHeader("More", itemKey = "history-more:day:2026-11-02")
		val second = org.koitharu.kotatsu.list.ui.model.ListHeader("More", itemKey = "history-more:day:2026-11-01")
		val same = org.koitharu.kotatsu.list.ui.model.ListHeader("More", itemKey = "history-more:day:2026-11-02")
		assertFalse(first.areItemsTheSame(second))
		assertTrue(first.areItemsTheSame(same))
	}

	@Test fun lastReadDirectionsDisplayAgeBucketsCorrectly() {
		assertEquals(listOf(HistoryDateSection.RECENT, HistoryDateSection.DAYS_8_30, HistoryDateSection.OLDER),
			historySectionOrder(false))
		assertEquals(listOf(HistoryDateSection.OLDER, HistoryDateSection.DAYS_8_30, HistoryDateSection.RECENT),
			historySectionOrder(true))
		assertEquals(org.koitharu.kotatsu.list.domain.ListSortOrder.Type.LAST_READ,
			org.koitharu.kotatsu.list.domain.ListSortOrder.LONG_AGO_READ.type)
		assertTrue(org.koitharu.kotatsu.list.domain.ListSortOrder.LONG_AGO_READ.isAscending)
	}
}
