package org.koitharu.kotatsu.stats.domain

import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticPolicy
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId

/**
 * Privacy-safe Reader Profile share payload.
 *
 * This is intentionally a whitelist model: only aggregate journey identity that the Phase 9
 * contract explicitly allows can enter the image renderer. It contains no title, cover, source,
 * genre, tag, history, display name, Reader Title, showcase, or mature-content identity.
 */
data class ReaderProfileShareModel(
	val rank: ReaderRank,
	val level: Int,
	val lifetimeXp: Long,
	val theme: RankThemeId?,
) {

	companion object {
		fun from(
			lifetimeXp: Long,
			loadout: ReaderJourneyCosmeticLoadout,
		): ReaderProfileShareModel {
			val progress = ReaderJourneyRules.progress(lifetimeXp)
			val safeLoadout = ReaderJourneyCosmeticPolicy.sanitizeForRank(loadout, progress.rank)
			val theme = when (safeLoadout.mode) {
				ReaderJourneyCosmeticMode.DEFAULT -> null
				ReaderJourneyCosmeticMode.AUTO -> RankThemeId.forRank(progress.rank)
				ReaderJourneyCosmeticMode.FULL_SET,
				ReaderJourneyCosmeticMode.CUSTOM -> RankThemeId.fromStableId(safeLoadout.selectedThemeId)
			}
			return ReaderProfileShareModel(
				rank = progress.rank,
				level = progress.level,
				lifetimeXp = progress.lifetimeXp,
				theme = theme,
			)
		}
	}
}
