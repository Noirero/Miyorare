package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.core.model.UniversalMangaIdentity
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.parsers.model.Manga
import kotlin.math.roundToInt

/**
 * Lightweight mirror ranking layered on top of [UniversalMangaIdentity].
 *
 * Identity decides whether two records plausibly represent the same work; chapter coverage only
 * ranks those plausible mirrors. This keeps identity consistent across features while avoiding any
 * network/database work in the scorer itself.
 */
object SourceFusionScorer {

	const val MIN_SEARCH_CANDIDATE_SCORE = 260
	const val STRONG_SEARCH_CANDIDATE_SCORE = 520

	fun score(reference: Manga, candidate: Manga): Int {
		val identity = UniversalMangaIdentity.evaluate(reference, candidate)
		if (identity.titleScore <= 0) return 0
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
