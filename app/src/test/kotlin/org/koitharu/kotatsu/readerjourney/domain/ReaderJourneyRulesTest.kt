package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderJourneyRulesTest {

	@Test
	fun `reader level starts at one and grows from lifetime xp`() {
		val start = ReaderJourneyRules.progress(0)
		assertEquals(1, start.level)
		assertEquals(ReaderRank.NEWCOMER, start.rank)
		assertEquals(100L, start.xpForNextLevel)

		val levelTwo = ReaderJourneyRules.progress(100)
		assertEquals(2, levelTwo.level)
		assertEquals(0L, levelTwo.xpIntoLevel)
	}

	@Test
	fun `rank bands match the approved reader journey`() {
		assertEquals(ReaderRank.READER, ReaderRank.forLevel(5))
		assertEquals(ReaderRank.COLLECTOR, ReaderRank.forLevel(37))
		assertEquals(ReaderRank.SCHOLAR, ReaderRank.forLevel(40))
		assertEquals(ReaderRank.GRAND_READER, ReaderRank.forLevel(99))
		assertEquals(ReaderRank.LEGEND, ReaderRank.forLevel(100))
	}

	@Test
	fun `level one hundred keeps lifetime xp without another level target`() {
		var threshold = 0L
		for (level in 1 until ReaderJourneyRules.MAX_LEVEL) {
			threshold += ReaderJourneyRules.xpRequiredForNextLevel(level)
		}
		val legend = ReaderJourneyRules.progress(threshold + 250_000L)
		assertEquals(100, legend.level)
		assertEquals(ReaderRank.LEGEND, legend.rank)
		assertNull(legend.xpForNextLevel)
		assertTrue(legend.lifetimeXp > threshold)
	}

	@Test
	fun `novel xp is bounded by reading length`() {
		assertEquals(8, ReaderJourneyRules.novelCompletionXp(1_000))
		assertEquals(12, ReaderJourneyRules.novelCompletionXp(1_500))
		assertEquals(15, ReaderJourneyRules.novelCompletionXp(4_000))
		assertEquals(20, ReaderJourneyRules.novelCompletionXp(8_000))
		assertEquals(20, ReaderJourneyRules.novelCompletionXp(100_000))
	}

	@Test
	fun `level curve becomes gradually more demanding`() {
		assertEquals(100L, ReaderJourneyRules.xpRequiredForNextLevel(1))
		assertEquals(180L, ReaderJourneyRules.xpRequiredForNextLevel(5))
		assertEquals(300L, ReaderJourneyRules.xpRequiredForNextLevel(10))
		assertEquals(500L, ReaderJourneyRules.xpRequiredForNextLevel(20))
		assertEquals(800L, ReaderJourneyRules.xpRequiredForNextLevel(40))
		assertEquals(1_100L, ReaderJourneyRules.xpRequiredForNextLevel(60))
		assertEquals(1_400L, ReaderJourneyRules.xpRequiredForNextLevel(80))
		assertEquals(2_000L, ReaderJourneyRules.xpRequiredForNextLevel(99))
	}
	@Test
	fun `manga completion requires eighty five percent unique pages`() {
		assertEquals(1, ReaderJourneyRules.requiredMangaPages(1))
		assertEquals(2, ReaderJourneyRules.requiredMangaPages(2))
		assertEquals(17, ReaderJourneyRules.requiredMangaPages(20))
		assertEquals(85, ReaderJourneyRules.requiredMangaPages(100))
		assertEquals(0, ReaderJourneyRules.requiredMangaPages(0))
	}

	@Test
	fun `duplicate page visits do not increase manga coverage`() {
		assertEquals(840, ReaderJourneyRules.mangaCoveragePermille(uniquePages = 84, totalPages = 100))
		assertEquals(850, ReaderJourneyRules.mangaCoveragePermille(uniquePages = 85, totalPages = 100))
		assertEquals(1000, ReaderJourneyRules.mangaCoveragePermille(uniquePages = 150, totalPages = 100))
	}

	@Test
	fun `anti skip duration scales with required unique page count`() {
		assertEquals(
			ReaderJourneyRules.MANGA_MIN_VALID_MS,
			ReaderJourneyRules.mangaMinimumValidDurationMs(1),
		)
		assertEquals(
			21_250L,
			ReaderJourneyRules.mangaMinimumValidDurationMs(100),
		)
	}

}
