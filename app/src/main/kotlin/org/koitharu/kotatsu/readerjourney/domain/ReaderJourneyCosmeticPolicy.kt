package org.koitharu.kotatsu.readerjourney.domain

import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.readerjourney.theme.ReferenceRankThemeVisualSpec

data class ReaderJourneyThemeCollectionEntry(
	val theme: RankThemeId,
	val unlocked: Boolean,
	val unlockLevel: Int,
	val visualSpec: ReferenceRankThemeVisualSpec,
)

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
		val unlockedSpecs = unlockedVisualSpecs(currentRank)
		val unlockedThemeIds = unlockedSpecs.mapTo(HashSet()) { it.themeId.stableId }
		val unlockedBadgeIds = unlockedSpecs.mapTo(HashSet()) { it.badgeId }
		val unlockedWallpaperIds = unlockedSpecs.mapTo(HashSet()) { it.wallpaperId }
		val unlockedCardIds = unlockedSpecs.mapTo(HashSet()) { it.cardId }
		val unlockedProgressIds = unlockedSpecs.mapTo(HashSet()) { it.progressId }

		val selectedTheme = RankThemeId.fromStableId(loadout.selectedThemeId)
			?.takeIf { it.stableId in unlockedThemeIds }
		fun sanitizeThemeSource(raw: String?): String? =
			RankThemeId.fromStableId(raw)?.stableId?.takeIf { it in unlockedThemeIds }

		val sanitized = loadout.copy(
			mode = if (
				loadout.mode == ReaderJourneyCosmeticMode.FULL_SET &&
				selectedTheme == null
			) ReaderJourneyCosmeticMode.AUTO else loadout.mode,
			selectedThemeId = selectedTheme?.stableId,
			navigationThemeId = sanitizeThemeSource(loadout.navigationThemeId),
			accentThemeId = sanitizeThemeSource(loadout.accentThemeId),
			glowThemeId = sanitizeThemeSource(loadout.glowThemeId),
			selectedBadgeId = loadout.selectedBadgeId?.takeIf { it in unlockedBadgeIds },
			selectedWallpaperId = loadout.selectedWallpaperId?.takeIf { it in unlockedWallpaperIds },
			selectedReaderCardId = loadout.selectedReaderCardId?.takeIf { it in unlockedCardIds },
			selectedProgressStyleId = loadout.selectedProgressStyleId?.takeIf { it in unlockedProgressIds },
			frame = loadout.frame?.takeIf { it.minLevel <= currentRank.minLevel },
			glow = loadout.glow?.takeIf { it.minLevel <= currentRank.minLevel },
			background = loadout.background?.takeIf { it.minLevel <= currentRank.minLevel },
			progressBar = loadout.progressBar?.takeIf { it.minLevel <= currentRank.minLevel },
			favoriteThemeIds = loadout.favoriteThemeIds.filterTo(LinkedHashSet()) { stableId ->
				stableId in unlockedThemeIds
			},
		)

		return when (sanitized.mode) {
			ReaderJourneyCosmeticMode.DEFAULT -> clearEquippedSelections(sanitized)
			ReaderJourneyCosmeticMode.AUTO -> clearEquippedSelections(sanitized)
			ReaderJourneyCosmeticMode.FULL_SET -> selectedTheme
				?.let { equipFullSetInternal(sanitized, it) }
				?: clearEquippedSelections(sanitized.copy(mode = ReaderJourneyCosmeticMode.AUTO))
			ReaderJourneyCosmeticMode.CUSTOM -> sanitized
		}
	}

	fun collection(currentRank: ReaderRank): List<ReaderJourneyThemeCollectionEntry> =
		RankThemeVisualRegistry.all.map { spec ->
			ReaderJourneyThemeCollectionEntry(
				theme = spec.themeId,
				unlocked = owns(spec.themeId, currentRank),
				unlockLevel = spec.themeId.rank.minLevel,
				visualSpec = spec,
			)
		}

	fun nextLockedTheme(currentRank: ReaderRank): ReaderJourneyThemeCollectionEntry? =
		collection(currentRank).firstOrNull { !it.unlocked }

	fun equipDefault(
		loadout: ReaderJourneyCosmeticLoadout,
		currentRank: ReaderRank,
	): ReaderJourneyCosmeticLoadout =
		sanitizeForRank(
			clearEquippedSelections(loadout.copy(mode = ReaderJourneyCosmeticMode.DEFAULT)),
			currentRank,
		)

	fun equipAuto(
		loadout: ReaderJourneyCosmeticLoadout,
		currentRank: ReaderRank,
	): ReaderJourneyCosmeticLoadout =
		sanitizeForRank(
			clearEquippedSelections(loadout.copy(mode = ReaderJourneyCosmeticMode.AUTO)),
			currentRank,
		)

	fun equipFullSet(
		loadout: ReaderJourneyCosmeticLoadout,
		theme: RankThemeId,
		currentRank: ReaderRank,
	): ReaderJourneyCosmeticLoadout {
		if (!owns(theme, currentRank)) {
			return sanitizeForRank(loadout, currentRank)
		}
		return sanitizeForRank(
			equipFullSetInternal(loadout.copy(mode = ReaderJourneyCosmeticMode.FULL_SET), theme),
			currentRank,
		)
	}

	fun equipCustom(
		loadout: ReaderJourneyCosmeticLoadout,
		currentRank: ReaderRank,
	): ReaderJourneyCosmeticLoadout =
		sanitizeForRank(loadout.copy(mode = ReaderJourneyCosmeticMode.CUSTOM), currentRank)

	fun toggleFavorite(
		loadout: ReaderJourneyCosmeticLoadout,
		theme: RankThemeId,
		currentRank: ReaderRank,
	): ReaderJourneyCosmeticLoadout {
		if (!owns(theme, currentRank)) return sanitizeForRank(loadout, currentRank)
		val favorites = LinkedHashSet(loadout.favoriteThemeIds)
		if (!favorites.add(theme.stableId)) {
			favorites.remove(theme.stableId)
		}
		return sanitizeForRank(loadout.copy(favoriteThemeIds = favorites), currentRank)
	}

	fun unlockedThemes(currentRank: ReaderRank): List<RankThemeId> =
		RankThemeId.entries.filter { it.rank.minLevel <= currentRank.minLevel }

	fun owns(theme: RankThemeId, currentRank: ReaderRank): Boolean =
		theme.rank.minLevel <= currentRank.minLevel

	private fun unlockedVisualSpecs(currentRank: ReaderRank): List<ReferenceRankThemeVisualSpec> =
		RankThemeVisualRegistry.all.filter { owns(it.themeId, currentRank) }

	private fun equipFullSetInternal(
		loadout: ReaderJourneyCosmeticLoadout,
		theme: RankThemeId,
	): ReaderJourneyCosmeticLoadout {
		val spec = checkNotNull(RankThemeVisualRegistry.resolve(theme)) {
			"Missing visual spec for ${theme.stableId}"
		}
		return loadout.copy(
			mode = ReaderJourneyCosmeticMode.FULL_SET,
			selectedThemeId = theme.stableId,
			navigationThemeId = null,
			accentThemeId = null,
			glowThemeId = null,
			selectedBadgeId = spec.badgeId,
			selectedWallpaperId = spec.wallpaperId,
			selectedReaderCardId = spec.cardId,
			selectedProgressStyleId = spec.progressId,
			frame = theme.rank,
			glow = theme.rank,
			background = theme.rank,
			progressBar = theme.rank,
		)
	}

	private fun clearEquippedSelections(
		loadout: ReaderJourneyCosmeticLoadout,
	): ReaderJourneyCosmeticLoadout = loadout.copy(
		selectedThemeId = null,
		navigationThemeId = null,
		accentThemeId = null,
		glowThemeId = null,
		selectedBadgeId = null,
		selectedWallpaperId = null,
		selectedReaderCardId = null,
		selectedProgressStyleId = null,
		frame = null,
		glow = null,
		background = null,
		progressBar = null,
	)
}
