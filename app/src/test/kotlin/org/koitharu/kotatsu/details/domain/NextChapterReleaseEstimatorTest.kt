package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class NextChapterReleaseEstimatorTest {

	private val utc = ZoneId.of("UTC")
	private val start = LocalDate.of(2026, 1, 1)

	private fun events(vararg intervals: Long): List<LocalDate> = intervals.fold(listOf(start)) { dates, days ->
		dates + dates.last().plusDays(days)
	}

	private fun predict(dates: List<LocalDate>, today: LocalDate, zone: ZoneId = utc): NextChapterReleasePrediction? =
		NextChapterReleaseEstimator.predict(
			uploadDates = dates.map { it.atStartOfDay(zone).toInstant().toEpochMilli() },
			nowMillis = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
			zone = zone,
		)

	@Test
	fun `weekly history predicts the next date and remaining days rather than cadence`() {
		val dates = events(7, 7, 7, 7)
		val result = checkNotNull(predict(dates, dates.last().plusDays(4)))
		assertEquals(dates.last().plusDays(7), result.expectedDate)
		assertEquals(3L, result.daysUntilNext)
	}

	@Test
	fun `regular biweekly and monthly histories are supported`() {
		for (interval in listOf(14L, 30L)) {
			val dates = events(interval, interval, interval, interval)
			assertEquals(interval - 3L, predict(dates, dates.last().plusDays(3))?.daysUntilNext)
		}
	}

	@Test
	fun `same day batch releases are one event even with different synthetic times`() {
		val dates = events(7, 7, 7)
		val uploads = dates.flatMap { date ->
			listOf(0, 6, 18).map { hour -> date.atTime(hour, 0).atZone(utc).toInstant().toEpochMilli() }
		}
		val result = NextChapterReleaseEstimator.predict(uploads, dates.last().plusDays(2).atStartOfDay(utc).toInstant().toEpochMilli(), utc)
		assertEquals(5L, result?.daysUntilNext)
	}

	@Test
	fun `isolated late release does not distort a stable weekly cadence`() {
		val dates = events(7, 7, 8, 7, 29, 7, 7)
		assertEquals(6L, predict(dates, dates.last().plusDays(1))?.daysUntilNext)
	}

	@Test
	fun `fewer than four distinct events are insufficient even with many rows`() {
		val dates = events(7, 7)
		assertNull(predict(dates + dates + dates, dates.last()))
		assertNull(predict(emptyList(), start))
	}

	@Test
	fun `severely inconsistent intervals fail the reliability gate`() {
		val dates = events(3, 18, 7, 31, 4)
		assertNull(predict(dates, dates.last()))
	}

	@Test
	fun `three intervals require all three to be consistent`() {
		val dates = events(7, 7, 29)
		assertNull(predict(dates, dates.last()))
	}

	@Test
	fun `stale weekly history is suppressed without inventing a hiatus status`() {
		val dates = events(7, 7, 7, 7)
		assertNull(predict(dates, dates.last().plusDays(90)))
	}

	@Test
	fun `unknown negative and future dates are ignored and cannot manufacture evidence`() {
		val dates = events(7, 7, 7)
		val now = dates.last().plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()
		val valid = dates.map { it.atStartOfDay(utc).toInstant().toEpochMilli() }
		val invalid = listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, now + 86_400_000L)
		assertEquals(6L, NextChapterReleaseEstimator.predict(valid + invalid, now, utc)?.daysUntilNext)
		assertNull(NextChapterReleaseEstimator.predict(invalid, now, utc))
		assertNull(NextChapterReleaseEstimator.predict(valid.take(2) + invalid, now, utc))
		assertNull(NextChapterReleaseEstimator.predict(valid, 0L, utc))
	}

	@Test
	fun `minimum stable evidence produces a result`() {
		val dates = events(7, 7, 8)
		assertEquals(7L, predict(dates, dates.last())?.daysUntilNext)
	}

	@Test
	fun `bounded recent events supersede substantially older cadence`() {
		val old = events(*LongArray(20) { 30L })
		val recent = (1L..11L).map { old.last().plusDays(it * 7L) }
		assertEquals(5L, predict(old + recent, recent.last().plusDays(2))?.daysUntilNext)
	}

	@Test
	fun `predicted day is today and never a negative countdown after it passes`() {
		val dates = events(7, 7, 7)
		assertEquals(0L, predict(dates, dates.last().plusDays(7))?.daysUntilNext)
		assertNull(predict(dates, dates.last().plusDays(8)))
		assertNull(predict(dates, dates.last().plusDays(21)))
		assertNull(predict(dates, dates.last().plusDays(22)))
	}

	@Test
	fun `row ordering does not change release chronology`() {
		val dates = events(7, 7, 7, 7)
		assertEquals(predict(dates, dates.last()), predict(dates.reversed(), dates.last()))
	}

	@Test
	fun `calendar intervals survive spring and autumn daylight saving transitions`() {
		val zone = ZoneId.of("America/New_York")
		for (first in listOf(LocalDate.of(2026, 2, 22), LocalDate.of(2026, 10, 18))) {
			val dates = (0L..4L).map { first.plusDays(it * 7L) }
			assertEquals(3L, predict(dates, dates.last().plusDays(4), zone)?.daysUntilNext)
		}
	}

	@Test
	fun `date only prediction uses the chapter display calendar on either side of UTC`() {
		for (zone in listOf(ZoneId.of("Asia/Jakarta"), ZoneId.of("Pacific/Honolulu"))) {
			val dates = events(7, 7, 7, 7)
			assertEquals(dates.last().plusDays(7), predict(dates, dates.last().plusDays(2), zone)?.expectedDate)
		}
	}

	@Test
	fun `no time of day precision leaks into prediction`() {
		val dates = events(7, 7, 7, 7)
		val uploads = dates.map { it.atTime(23, 30).atZone(utc).toInstant().toEpochMilli() }
		val today = dates.last().plusDays(4)
		for (hour in listOf(0, 12, 23)) {
			assertEquals(3L, NextChapterReleaseEstimator.predict(uploads, today.atTime(hour, 0).atZone(utc).toInstant().toEpochMilli(), utc)?.daysUntilNext)
		}
	}
}
