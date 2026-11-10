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
			key = dayA.key, kind = HistoryAccordionItem.Kind.MORE, moreCount = 50,
		)

		assertTrue(dayA.areItemsTheSame(expandedA))
		assertFalse(dayA.areItemsTheSame(dayB))
		assertFalse(dayA.areItemsTheSame(moreA))
		assertTrue(moreA.areItemsTheSame(moreA.copy(moreCount = 2)))
	}

	@Test
	fun moreShowsAtMostFiftyItemsPerExpandedDateGroup() {
		assertEquals(0, nextHistoryAccordionMoreCount(0))
		assertEquals(1, nextHistoryAccordionMoreCount(1))
		assertEquals(16, nextHistoryAccordionMoreCount(16))
		assertEquals(49, nextHistoryAccordionMoreCount(49))
		assertEquals(50, nextHistoryAccordionMoreCount(50))
		assertEquals(50, nextHistoryAccordionMoreCount(51))
		assertEquals(50, nextHistoryAccordionMoreCount(99))
		assertEquals(50, nextHistoryAccordionMoreCount(120))
		assertEquals(50, HISTORY_GROUP_PAGE_SIZE)
		assertEquals(0, nextHistoryAccordionMoreCount(-3))
	}

	@Test
	fun groupMoreAdvancesInFiftyTitleStepsWithoutAffectingRecentPageSize() {
		assertEquals(50, HISTORY_GROUP_PAGE_SIZE)
		assertEquals(100, nextHistoryAccordionPageLimit(null))
		assertEquals(100, nextHistoryAccordionPageLimit(50))
		assertEquals(150, nextHistoryAccordionPageLimit(100))
		assertEquals(16, nextHistoryAccordionMoreCount(16))
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
