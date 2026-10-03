package org.koitharu.kotatsu.stats.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsRepositoryJourneyStreakTest {

	private val zone = ZoneId.of("UTC")

	@Test
	fun `verified completion streak ignores gaps and duplicate completions on same day`() {
		val day = LocalDate.of(2026, 9, 1)
		val timestamps = listOf(
			day.atStartOfDay(zone).toInstant().toEpochMilli(),
			day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
			day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
			day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(),
			day.plusDays(5).atStartOfDay(zone).toInstant().toEpochMilli(),
		)

		assertEquals(3, calculateLongestVerifiedReadingStreak(timestamps, zone))
	}

	@Test
	fun `no verified completions means no streak achievement progress`() {
		assertEquals(0, calculateLongestVerifiedReadingStreak(emptyList(), zone))
	}
}
