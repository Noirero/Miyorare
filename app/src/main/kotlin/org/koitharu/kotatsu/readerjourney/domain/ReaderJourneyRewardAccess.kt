package org.koitharu.kotatsu.readerjourney.domain

import org.koitharu.kotatsu.BuildConfig

/**
 * Build-channel access override for Reader Journey cosmetic rewards.
 *
 * Official Beta APKs use the Preview build type and intentionally expose every cosmetic reward for
 * visual QA. Stable/Main release builds keep the normal rank-gated ownership rules.
 *
 * Progression itself is never modified: XP, level and rank remain real. This only widens which
 * cosmetic rank is available to presentation/customization code.
 */
object ReaderJourneyRewardAccess {

	fun cosmeticAccessRank(realRank: ReaderRank): ReaderRank =
		if (BuildConfig.READER_JOURNEY_UNLOCK_ALL_REWARDS) ReaderRank.LEGEND else realRank
}
