package org.koitharu.kotatsu.sources.compat

import org.koitharu.kotatsu.parsers.model.Manga
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DownloadReconnectPlan {
	data object NoSafeMatch : DownloadReconnectPlan

	data class Automatic(
		val manga: Manga,
		val evidence: DownloadedContentMatch,
	) : DownloadReconnectPlan

	/** More than one candidate has the same strongest evidence, so user confirmation is required. */
	data class Ambiguous(
		val evidence: DownloadedContentMatch,
		val candidates: List<Manga>,
	) : DownloadReconnectPlan
}

/**
 * Chooses an existing downloaded copy only when identity evidence is unique and deterministic.
 * Title similarity is deliberately excluded from automatic decisions.
 */
@Singleton
class DownloadReconnectPlanner @Inject constructor(
	private val matcher: DownloadedContentMatcher,
) {

	suspend fun plan(remote: Manga, downloadedCandidates: Iterable<Manga>): DownloadReconnectPlan {
		var strongest = DownloadedContentMatch.NONE
		val strongestCandidates = ArrayList<Manga>()
		for (candidate in downloadedCandidates) {
			val evidence = matcher.match(remote, candidate)
			val comparison = rank(evidence).compareTo(rank(strongest))
			when {
				comparison > 0 -> {
					strongest = evidence
					strongestCandidates.clear()
					if (evidence != DownloadedContentMatch.NONE) strongestCandidates += candidate
				}
				comparison == 0 && evidence != DownloadedContentMatch.NONE -> strongestCandidates += candidate
			}
		}
		return when {
			strongestCandidates.isEmpty() -> DownloadReconnectPlan.NoSafeMatch
			strongestCandidates.size == 1 -> DownloadReconnectPlan.Automatic(strongestCandidates.single(), strongest)
			else -> DownloadReconnectPlan.Ambiguous(strongest, strongestCandidates)
		}
	}

	/**
	 * Weak title matches are candidates for a manual reconnect screen only. Calling this function
	 * never changes provider identity, database rows, or download files.
	 */
	fun manualTitleCandidates(remote: Manga, downloadedCandidates: Iterable<Manga>): List<Manga> {
		val title = normalizeTitle(remote.title)
		if (title.isEmpty()) return emptyList()
		return downloadedCandidates.filter { normalizeTitle(it.title) == title }
	}

	private fun rank(match: DownloadedContentMatch): Int = when (match) {
		DownloadedContentMatch.NONE -> 0
		DownloadedContentMatch.SOURCE_ALIAS_AND_CONTENT_URL -> 1
		DownloadedContentMatch.PUBLIC_URL -> 2
		DownloadedContentMatch.EXACT_ID -> 3
	}

	private fun normalizeTitle(value: String): String = value
		.trim()
		.lowercase(Locale.ROOT)
		.replace(Regex("\\s+"), " ")
}
