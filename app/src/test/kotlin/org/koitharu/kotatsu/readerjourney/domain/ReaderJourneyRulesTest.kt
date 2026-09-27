package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyProfileEntity
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyXpEventEntity

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
	fun `rank cosmetics unlock deterministically without rng or separate ownership state`() {
		val readerUnlocks = ReaderJourneyCosmetics.newlyUnlocked(
			ReaderRank.NEWCOMER,
			ReaderRank.READER,
		)
		assertEquals(ReaderJourneyCosmeticSlot.entries.size, readerUnlocks.size)
		assertEquals(
			ReaderJourneyCosmeticSlot.entries.toSet(),
			readerUnlocks.map { it.slot }.toSet(),
		)
		assertTrue(readerUnlocks.all { it.rank == ReaderRank.READER })

		val archivistOwned = ReaderJourneyCosmetics.unlockedAt(ReaderRank.ARCHIVIST)
		assertEquals(
			(ReaderRank.ARCHIVIST.ordinal + 1) * ReaderJourneyCosmeticSlot.entries.size,
			archivistOwned.size,
		)
	}

	@Test
	fun `cosmetic loadout can only resolve to an unlocked rank`() {
		assertEquals(
			listOf(
				ReaderRank.NEWCOMER,
				ReaderRank.READER,
				ReaderRank.BOOKWORM,
			),
			ReaderJourneyCosmetics.unlockedRanks(ReaderRank.BOOKWORM),
		)
		assertEquals(
			ReaderRank.READER,
			ReaderJourneyCosmetics.effectiveRank(ReaderRank.READER, ReaderRank.BOOKWORM),
		)
		assertEquals(
			ReaderRank.BOOKWORM,
			ReaderJourneyCosmetics.effectiveRank(ReaderRank.LEGEND, ReaderRank.BOOKWORM),
		)
		assertEquals(
			ReaderRank.BOOKWORM,
			ReaderJourneyCosmetics.effectiveRank(null, ReaderRank.BOOKWORM),
		)
	}

	@Test
	fun `cosmetic loadout keeps four independent slots`() {
		val loadout = ReaderJourneyCosmeticLoadout()
			.withSelection(ReaderJourneyCosmeticSlot.FRAME, ReaderRank.READER)
			.withSelection(ReaderJourneyCosmeticSlot.GLOW, ReaderRank.BOOKWORM)
			.withSelection(ReaderJourneyCosmeticSlot.BACKGROUND, ReaderRank.EXPLORER)
			.withSelection(ReaderJourneyCosmeticSlot.PROGRESS_BAR, ReaderRank.COLLECTOR)

		assertEquals(ReaderRank.READER, loadout.frame)
		assertEquals(ReaderRank.BOOKWORM, loadout.glow)
		assertEquals(ReaderRank.EXPLORER, loadout.background)
		assertEquals(ReaderRank.COLLECTOR, loadout.progressBar)
	}

	@Test
	fun `journey celebration distinguishes level and rank transitions`() {
		val levelOnly = ReaderJourneyCelebration(
			xpEarned = 10,
			fromLevel = 1,
			toLevel = 2,
			fromRank = ReaderRank.NEWCOMER,
			toRank = ReaderRank.NEWCOMER,
			unlockedCosmetics = 0,
		)
		assertTrue(levelOnly.isLevelUp)
		assertTrue(!levelOnly.isRankUp)

		val rankUp = levelOnly.copy(
			fromLevel = 4,
			toLevel = 5,
			toRank = ReaderRank.READER,
			unlockedCosmetics = ReaderJourneyCosmeticSlot.entries.size,
		)
		assertTrue(rankUp.isLevelUp)
		assertTrue(rankUp.isRankUp)
		assertEquals(4, rankUp.unlockedCosmetics)
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

	@Test
	fun `achievement milestones are deterministic and generic`() {
		val metrics = ReaderAchievementMetrics(
			completedChapters = 100L,
			novelChapters = 1L,
			uniqueTitles = 10L,
			longestStreak = 7L,
		)
		val unlocked = ReaderAchievementRules.newlySatisfied(metrics, emptySet()).toSet()

		assertTrue(ReaderAchievementId.FIRST_CHAPTER in unlocked)
		assertTrue(ReaderAchievementId.CHAPTERS_100 in unlocked)
		assertTrue(ReaderAchievementId.FIRST_NOVEL in unlocked)
		assertTrue(ReaderAchievementId.TITLES_10 in unlocked)
		assertTrue(ReaderAchievementId.STREAK_7 in unlocked)
		assertTrue(ReaderAchievementId.CHAPTERS_1000 !in unlocked)
	}

	@Test
	fun `already unlocked achievement is not emitted twice`() {
		val metrics = ReaderAchievementMetrics(completedChapters = 1L)
		val unlocked = ReaderAchievementRules.newlySatisfied(
			metrics,
			setOf(ReaderAchievementId.FIRST_CHAPTER),
		)

		assertTrue(unlocked.isEmpty())
	}


	@Test
	fun `approved rank one hundred target remains 96101 xp`() {
		val total = (1 until ReaderJourneyRules.MAX_LEVEL)
			.sumOf(ReaderJourneyRules::xpRequiredForNextLevel)
		assertEquals(96_101L, total)
		assertEquals(100, ReaderJourneyRules.progress(total).level)
	}

	@Test
	fun `quarter level milestones are emitted only when crossed`() {
		assertEquals(
			listOf(25),
			ReaderJourneyRules.progressMilestonesCrossed(
				ReaderJourneyRules.progress(20L),
				ReaderJourneyRules.progress(30L),
			),
		)
		assertEquals(
			listOf(50, 75),
			ReaderJourneyRules.progressMilestonesCrossed(
				ReaderJourneyRules.progress(40L),
				ReaderJourneyRules.progress(80L),
			),
		)
		assertEquals(
			listOf(100),
			ReaderJourneyRules.progressMilestonesCrossed(
				ReaderJourneyRules.progress(90L),
				ReaderJourneyRules.progress(100L),
			),
		)
		assertEquals(
			emptyList<Int>(),
			ReaderJourneyRules.progressMilestonesCrossed(
				ReaderJourneyRules.progress(30L),
				ReaderJourneyRules.progress(30L),
			),
		)
	}

	@Test
	fun `adaptive reroll does not reintroduce opposite format for strongly single format readers`() {
		val all = ReaderJourneyWeeklyTaskId.entries.toList()
		val mangaCandidates = selectAdaptiveRerollCandidates(
			ReaderJourneyProfileEntity(mangaChapters = 40L, novelChapters = 1L),
			all,
		)
		val novelCandidates = selectAdaptiveRerollCandidates(
			ReaderJourneyProfileEntity(mangaChapters = 1L, novelChapters = 40L),
			all,
		)

		assertTrue(mangaCandidates.none { it.metric == ReaderJourneyWeeklyMetric.NOVEL_CHAPTERS })
		assertTrue(novelCandidates.none { it.metric == ReaderJourneyWeeklyMetric.MANGA_CHAPTERS })
	}


	@Test
	fun `awarded weekly slot resolves to ledger task after cross device reroll divergence`() {
		val configured = ReaderJourneyWeeklyTaskId.READ_2_NOVELS
		val awarded = ReaderJourneyXpEventEntity(
			eventKey = "weekly:2026-09-21:slot:4",
			source = ReaderJourneyXpSource.WEEKLY_TASK.name,
			xp = 35,
			occurredAt = 1L,
			context = ReaderJourneyWeeklyTaskId.READ_5_CHAPTERS.name,
		)

		assertEquals(
			ReaderJourneyWeeklyTaskId.READ_5_CHAPTERS,
			resolveWeeklyTaskId(configured, awarded),
		)
		assertEquals(configured, resolveWeeklyTaskId(configured, null))

		val reroll = ReaderJourneyXpEventEntity(
			eventKey = "weekly-reroll:2026-09-21:slot:4",
			source = "WEEKLY_REROLL",
			xp = 0,
			occurredAt = 1L,
			context = ReaderJourneyWeeklyTaskId.READ_3_DAYS.name,
			profileDelta = false,
		)
		assertEquals(
			ReaderJourneyWeeklyTaskId.READ_3_DAYS,
			resolveWeeklyTaskId(configured, awardedEvent = null, rerollEvent = reroll),
		)
	}

	@Test
	fun `soft daily cap diminishes reading without hard stopping it`() {
		assertEquals(
			10,
			ReaderJourneyRules.applySoftDailyReadingReturn(
				baseXp = 10,
				readingXpToday = ReaderJourneyRules.SOFT_DAILY_READING_XP - 1L,
			),
		)
		assertEquals(
			6,
			ReaderJourneyRules.applySoftDailyReadingReturn(
				baseXp = 10,
				readingXpToday = ReaderJourneyRules.SOFT_DAILY_READING_XP.toLong(),
			),
		)
		assertTrue(
			ReaderJourneyRules.applySoftDailyReadingReturn(
				baseXp = 1,
				readingXpToday = 10_000L,
			) > 0,
		)
	}

	@Test
	fun `rested and welcome bonuses are deterministic`() {
		assertEquals(3, ReaderJourneyRules.percentageBonus(10, 25))
		assertEquals(5, ReaderJourneyRules.percentageBonus(20, 25))
		assertEquals(0, ReaderJourneyRules.percentageBonus(0, 25))
	}

	@Test
	fun `achievement xp follows approved one time milestone values`() {
		assertEquals(25, ReaderAchievementId.FIRST_CHAPTER.xpReward)
		assertEquals(250, ReaderAchievementId.CHAPTERS_100.xpReward)
		assertEquals(1_000, ReaderAchievementId.CHAPTERS_1000.xpReward)
		assertEquals(100, ReaderAchievementId.TITLES_10.xpReward)
		assertEquals(300, ReaderAchievementId.TITLES_50.xpReward)
		assertTrue(ReaderAchievementId.FIRST_NOVEL.xpReward > 0)
	}

	@Test
	fun `reading personality uses aggregate verified journey data only`() {
		assertEquals(
			ReadingPersonality.DISCOVERING,
			ReadingPersonalityRules.resolve(0, 0, 0, 0),
		)
		assertEquals(
			ReadingPersonality.MANGA_READER,
			ReadingPersonalityRules.resolve(20, 2, 4, 3),
		)
		assertEquals(
			ReadingPersonality.NOVEL_READER,
			ReadingPersonalityRules.resolve(2, 20, 4, 3),
		)
		assertEquals(
			ReadingPersonality.STEADY_READER,
			ReadingPersonalityRules.resolve(10, 10, 5, 30),
		)
	}


}
