package org.koitharu.kotatsu.readerjourney.domain

import org.koitharu.kotatsu.BuildConfig

/**
 * Optional build-channel access override for dedicated visual QA builds.
 *
 * User-facing Beta and release builds keep normal rank-gated ownership. Automated/developer visual
 * tooling should seed the required rank explicitly instead of bypassing Reader Journey progression.
 * Progression itself is never modified by this helper.
 */
object ReaderJourneyRewardAccess {

	fun cosmeticAccessRank(realRank: ReaderRank): ReaderRank =
		if (BuildConfig.READER_JOURNEY_UNLOCK_ALL_REWARDS) ReaderRank.LEGEND else realRank
}
