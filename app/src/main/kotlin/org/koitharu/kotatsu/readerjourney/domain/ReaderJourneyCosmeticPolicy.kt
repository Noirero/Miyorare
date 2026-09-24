package org.koitharu.kotatsu.readerjourney.domain

import org.koitharu.kotatsu.readerjourney.theme.RankThemeId

/**
 * One ownership/selection policy for every cosmetic entry point.
 *
 * Ownership remains derived from Reader Rank. This object only sanitizes presentation selections;
 * it never stores unlock booleans or mutates Reader Journey progression.
 */
object ReaderJourneyCosmeticPolicy {

	fun sanitizeForRank(
		loadout: ReaderJourneyCosmeticLoadout,
		currentRank: ReaderRank,
	): ReaderJourneyCosmeticLoadout {
		val selectedTheme = RankThemeId.fromStableId(loadout.selectedThemeId)
			?.takeIf { it.rank.minLevel <= currentRank.minLevel }

		return loadout.copy(
			mode = if (
				loadout.mode == ReaderJourneyCosmeticMode.FULL_SET &&
				selectedTheme == null
			) ReaderJourneyCosmeticMode.AUTO else loadout.mode,
			selectedThemeId = selectedTheme?.stableId,
			frame = loadout.frame?.takeIf { it.minLevel <= currentRank.minLevel },
			glow = loadout.glow?.takeIf { it.minLevel <= currentRank.minLevel },
			background = loadout.background?.takeIf { it.minLevel <= currentRank.minLevel },
			progressBar = loadout.progressBar?.takeIf { it.minLevel <= currentRank.minLevel },
			favoriteThemeIds = loadout.favoriteThemeIds.filterTo(LinkedHashSet()) { stableId ->
				RankThemeId.fromStableId(stableId)?.rank?.minLevel?.let { it <= currentRank.minLevel } == true
			},
		)
	}

	fun unlockedThemes(currentRank: ReaderRank): List<RankThemeId> =
		RankThemeId.entries.filter { it.rank.minLevel <= currentRank.minLevel }

	fun owns(theme: RankThemeId, currentRank: ReaderRank): Boolean =
		theme.rank.minLevel <= currentRank.minLevel
}
