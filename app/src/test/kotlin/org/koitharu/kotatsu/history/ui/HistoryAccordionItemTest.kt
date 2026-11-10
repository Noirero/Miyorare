package org.koitharu.kotatsu.history.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistoryAccordionItemTest {

	@Test
	fun independentDayHeadersKeepStableDistinctRecyclerViewIdentities() {
		val dayA = HistoryAccordionItem(
			key = "day:2026-11-02", kind = HistoryAccordionItem.Kind.DAY,
			date = LocalDate.of(2026, 11, 2), titleCount = 18,
		)
		val dayB = dayA.copy(key = "day:2026-11-01", date = LocalDate.of(2026, 11, 1))
		val expandedA = dayA.copy(expanded = true)
		val moreA = HistoryAccordionItem(
			key = dayA.key, kind = HistoryAccordionItem.Kind.MORE, moreCount = 16,
		)

		assertTrue(dayA.areItemsTheSame(expandedA))
		assertFalse(dayA.areItemsTheSame(dayB))
		assertFalse(dayA.areItemsTheSame(moreA))
		assertTrue(moreA.areItemsTheSame(moreA.copy(moreCount = 2)))
	}

	@Test
	fun moreShowsAtMostTheExistingSixteenItemPageSize() {
		assertEquals(0, nextHistoryAccordionMoreCount(0))
		assertEquals(1, nextHistoryAccordionMoreCount(1))
		assertEquals(15, nextHistoryAccordionMoreCount(15))
		assertEquals(16, nextHistoryAccordionMoreCount(16))
		assertEquals(16, nextHistoryAccordionMoreCount(17))
		assertEquals(16, nextHistoryAccordionMoreCount(99))
		assertEquals(0, nextHistoryAccordionMoreCount(-3))
	}

	@Test
	fun parentSummaryCountsOnlyDatesWithMatchingHistory() {
		val zone = java.time.ZoneId.of("Asia/Jakarta")
		val today = LocalDate.of(2026, 11, 10)
		fun at(daysAgo: Long) = today.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli()
		val overview = historyDateOverview(listOf(at(8), at(8), at(15), at(31), at(31), at(68)), today, zone)

		assertEquals(3, overview.days.values.sum())
		assertEquals(2, overview.days.size)
		assertEquals(3, overview.months.values.sum())
		assertEquals(2, overview.months.size)
	}
}
