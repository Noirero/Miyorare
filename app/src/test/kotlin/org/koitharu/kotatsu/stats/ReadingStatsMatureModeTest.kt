package org.koitharu.kotatsu.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsInsight
import org.koitharu.kotatsu.stats.domain.StatsMatureMode
import org.koitharu.kotatsu.stats.domain.StatsRecord

class ReadingStatsMatureModeTest {

	@Test
	fun switchingToPrivateHidesAnUnfilteredSnapshotUntilTheReplacementArrives() {
		val record = StatsRecord(manga = null, duration = 20, chapters = 1)
		val included = ReadingStats(
			matureMode = StatsMatureMode.INCLUDE,
			records = listOf(record),
			revisited = listOf(record),
			topGenres = listOf(StatsInsight("fixture genre", 1)),
			titleCount = 1,
			totalDuration = record.duration,
		)

		val pending = included.forMatureMode(StatsMatureMode.PRIVATE)
		assertEquals(StatsMatureMode.PRIVATE, pending.matureMode)
		assertTrue(pending.isEmpty)
		assertTrue(pending.records.isEmpty())
		assertTrue(pending.revisited.isEmpty())
		assertTrue(pending.topGenres.isEmpty())

		val privateSnapshot = ReadingStats(
			matureMode = StatsMatureMode.PRIVATE,
			records = listOf(record.copy(isPrivate = true)),
			totalDuration = record.duration,
		)
		assertSame(privateSnapshot, privateSnapshot.forMatureMode(StatsMatureMode.PRIVATE))
	}
}
