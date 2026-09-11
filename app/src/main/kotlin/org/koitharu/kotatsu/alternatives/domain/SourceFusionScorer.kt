package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.core.model.UniversalMangaIdentity
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.parsers.model.Manga
import kotlin.math.roundToInt

/**
 * Lightweight mirror ranking layered on top of [UniversalMangaIdentity].
 *
 * Identity is a hard gate: chapter coverage may rank plausible mirrors, but it can never rescue a
 * candidate that failed cross-source identity. This keeps a high chapter count from turning an
 * unrelated same-title work into a migration target.
 */
object SourceFusionScorer {

	const val MIN_SEARCH_CANDIDATE_SCORE = UniversalMangaIdentity.LIKELY_MATCH_SCORE
	const val STRONG_SEARCH_CANDIDATE_SCORE = UniversalMangaIdentity.STRONG_MATCH_SCORE

	fun score(reference: Manga, candidate: Manga): Int {
		val identity = UniversalMangaIdentity.evaluate(reference, candidate)
		if (identity.score < UniversalMangaIdentity.LIKELY_MATCH_SCORE) return 0
		return identity.score + scoreChapters(reference.chaptersCount(), candidate.chaptersCount())
	}

	private fun scoreChapters(reference: Int, candidate: Int): Int {
		if (reference <= 0 || candidate <= 0) return 0
		val ratio = minOf(reference, candidate).toDouble() / maxOf(reference, candidate).toDouble()
		val coverage = (ratio * 160.0).roundToInt()
		val noRegressionBonus = if (candidate >= reference) 40 else 0
		return coverage + noRegressionBonus
	}
}
