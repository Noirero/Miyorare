package org.koitharu.kotatsu.readerjourney.theme

import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticPolicy
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRewardAccess

data class ReaderJourneyThemePresentationRequest(
	val loadout: ReaderJourneyCosmeticLoadout,
	val lifetimeXp: Long,
	val explicitCustomAppearance: Boolean,
	val dynamicColorEnabled: Boolean = false,
	val presentationEnabled: Boolean = RankThemePresentationSafety.ENABLED_BY_DEFAULT,
)

/**
 * Converts persisted cosmetic intent + monotonic Reader Journey progress into one presentation source.
 *
 * This never owns progression and never trusts a persisted rank. Lifetime XP remains the source of
 * truth, while invalid or locked cosmetic selections are sanitized before theme resolution.
 */
object ReaderJourneyThemePresentationResolver {

	fun resolve(request: ReaderJourneyThemePresentationRequest): RankThemeSourceResolution {
		val progress = ReaderJourneyRules.progress(request.lifetimeXp)
		val cosmeticAccessRank = ReaderJourneyRewardAccess.cosmeticAccessRank(progress.rank)
		val loadout = ReaderJourneyCosmeticPolicy.sanitizeForRank(
			request.loadout,
			cosmeticAccessRank,
		)
		val explicitRankThemeId = when (loadout.mode) {
			ReaderJourneyCosmeticMode.FULL_SET -> loadout.selectedThemeId
			ReaderJourneyCosmeticMode.CUSTOM ->
				loadout.selectedThemeId ?: RankThemeId.forRank(progress.rank).stableId

			ReaderJourneyCosmeticMode.DEFAULT,
			ReaderJourneyCosmeticMode.AUTO -> null
		}
		return RankThemeSourceResolver.resolve(
			RankThemeSourceRequest(
				explicitCustomOverride = request.explicitCustomAppearance,
				explicitRankThemeId = explicitRankThemeId,
				autoRankEnabled = loadout.mode == ReaderJourneyCosmeticMode.AUTO,
				currentRank = progress.rank,
				dynamicColorEnabled = request.dynamicColorEnabled,
				presentationEnabled = request.presentationEnabled,
			),
		)
	}
}
