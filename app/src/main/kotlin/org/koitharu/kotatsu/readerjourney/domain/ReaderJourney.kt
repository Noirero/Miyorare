package org.koitharu.kotatsu.readerjourney.domain

/**
 * Reader Journey is deliberately local-first: XP records verified reading completions rather than
 * time spent with the reader open. The level is one shared reader level for Manga and Novel.
 */
data class ReaderJourneyProgress(
	val lifetimeXp: Long,
	val level: Int,
	val xpIntoLevel: Long,
	val xpForNextLevel: Long?,
	val rank: ReaderRank,
) {
	val levelFraction: Float
		get() = xpForNextLevel?.takeIf { it > 0L }?.let { (xpIntoLevel.toFloat() / it).coerceIn(0f, 1f) } ?: 1f
}

enum class ReaderRank(val minLevel: Int) {
	NEWCOMER(1),
	READER(5),
	BOOKWORM(10),
	EXPLORER(20),
	COLLECTOR(30),
	SCHOLAR(40),
	ARCHIVIST(50),
	BIBLIOPHILE(60),
	VETERAN_READER(70),
	MASTER_READER(80),
	GRAND_READER(90),
	LEGEND(100);

	companion object {
		fun forLevel(level: Int): ReaderRank = entries.last { level >= it.minLevel }
	}
}

enum class ReaderJourneyCosmeticSlot {
	FRAME,
	GLOW,
	BACKGROUND,
	PROGRESS_BAR,
}

data class ReaderJourneyCosmeticUnlock(
	val rank: ReaderRank,
	val slot: ReaderJourneyCosmeticSlot,
)

/**
 * Cosmetic ownership is derived from monotonic Lifetime XP rather than stored independently.
 * That keeps unlocks deterministic, backup/sync-safe and impossible to lose through preference
 * resets. Every rank owns one complete cosmetic set; selection/apply UI can be layered on later.
 */
object ReaderJourneyCosmetics {

	fun unlockedAt(rank: ReaderRank): List<ReaderJourneyCosmeticUnlock> =
		ReaderRank.entries
			.filter { it.minLevel <= rank.minLevel }
			.flatMap { unlockedRank ->
				ReaderJourneyCosmeticSlot.entries.map { slot ->
					ReaderJourneyCosmeticUnlock(rank = unlockedRank, slot = slot)
				}
			}

	fun newlyUnlocked(from: ReaderRank, to: ReaderRank): List<ReaderJourneyCosmeticUnlock> {
		if (to.minLevel <= from.minLevel) return emptyList()
		return ReaderRank.entries
			.filter { it.minLevel > from.minLevel && it.minLevel <= to.minLevel }
			.flatMap { unlockedRank ->
				ReaderJourneyCosmeticSlot.entries.map { slot ->
					ReaderJourneyCosmeticUnlock(rank = unlockedRank, slot = slot)
				}
			}
	}
}

data class ReaderJourneyCelebration(
	val xpEarned: Int,
	val fromLevel: Int,
	val toLevel: Int,
	val fromRank: ReaderRank,
	val toRank: ReaderRank,
	val unlockedCosmetics: Int,
) {
	val isLevelUp: Boolean
		get() = toLevel > fromLevel

	val isRankUp: Boolean
		get() = toRank.minLevel > fromRank.minLevel
}

object ReaderJourneyRules {
	const val MAX_LEVEL = 100
	const val MANGA_COMPLETION_XP = 10
	const val REREAD_XP = 1
	const val MAX_REREAD_AWARDS = 3
	const val COMPLETION_PERMILLE = 850
	const val MANGA_MIN_VALID_MS = 8_000L
	const val MANGA_MIN_MS_PER_UNIQUE_PAGE = 250L
	const val NOVEL_MIN_VALID_MS = 12_000L

	fun novelCompletionXp(readingUnits: Int): Int = when {
		readingUnits < 1_500 -> 8
		readingUnits < 4_000 -> 12
		readingUnits < 8_000 -> 15
		else -> 20
	}


	fun requiredMangaPages(totalPages: Int): Int {
		if (totalPages <= 0) return 0
		return ((totalPages.toLong() * COMPLETION_PERMILLE + 999L) / 1000L)
			.coerceAtMost(totalPages.toLong())
			.toInt()
	}

	fun mangaCoveragePermille(uniquePages: Int, totalPages: Int): Int {
		if (totalPages <= 0 || uniquePages <= 0) return 0
		return ((uniquePages.coerceAtMost(totalPages).toLong() * 1000L) / totalPages)
			.toInt()
			.coerceIn(0, 1000)
	}

	fun mangaMinimumValidDurationMs(totalPages: Int): Long {
		val requiredPages = requiredMangaPages(totalPages)
		return maxOf(
			MANGA_MIN_VALID_MS,
			requiredPages.toLong() * MANGA_MIN_MS_PER_UNIQUE_PAGE,
		)
	}

	/**
	 * Moderate non-linear curve. Early levels arrive quickly, while later levels become meaningful
	 * without turning Lv.100 into an RPG grind wall.
	 */
	fun xpRequiredForNextLevel(level: Int): Long {
		if (level >= MAX_LEVEL) return 0L
		val anchors = arrayOf(
			1 to 100L,
			5 to 180L,
			10 to 300L,
			20 to 500L,
			40 to 800L,
			60 to 1_100L,
			80 to 1_400L,
			99 to 2_000L,
		)
		val lower = anchors.last { level >= it.first }
		val upper = anchors.firstOrNull { level <= it.first && it.first > lower.first } ?: lower
		if (upper.first == lower.first) return lower.second
		val fraction = (level - lower.first).toDouble() / (upper.first - lower.first)
		return (lower.second + (upper.second - lower.second) * fraction).toLong()
	}

	fun progress(lifetimeXp: Long): ReaderJourneyProgress {
		val safeXp = lifetimeXp.coerceAtLeast(0L)
		var level = 1
		var consumed = 0L
		while (level < MAX_LEVEL) {
			val required = xpRequiredForNextLevel(level)
			if (safeXp - consumed < required) break
			consumed += required
			level++
		}
		val next = if (level >= MAX_LEVEL) null else xpRequiredForNextLevel(level)
		return ReaderJourneyProgress(
			lifetimeXp = safeXp,
			level = level,
			xpIntoLevel = safeXp - consumed,
			xpForNextLevel = next,
			rank = ReaderRank.forLevel(level),
		)
	}
}
