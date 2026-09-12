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

internal sealed interface DownloadReconnectSelection {
	data object NoSafeMatch : DownloadReconnectSelection

	data class Automatic(
		val index: Int,
		val evidence: DownloadedContentMatch,
	) : DownloadReconnectSelection

	data class Ambiguous(
		val evidence: DownloadedContentMatch,
		val indexes: List<Int>,
	) : DownloadReconnectSelection
}

/**
 * Chooses an existing downloaded copy only when identity evidence is unique and deterministic.
 * Title similarity is deliberately excluded from automatic decisions.
 */
@Singleton
class DownloadReconnectPlanner @Inject constructor(
	private val matcher: DownloadedContentMatcher,
) {

	/** Evaluate one candidate without retaining or mutating it. Useful for bounded fallback scans. */
	suspend fun evidence(remote: Manga, downloaded: Manga): DownloadedContentMatch = matcher.match(remote, downloaded)

	suspend fun plan(remote: Manga, downloadedCandidates: Iterable<Manga>): DownloadReconnectPlan {
		val candidates = downloadedCandidates.toList()
		val matches = ArrayList<DownloadedContentMatch>(candidates.size)
		for (candidate in candidates) {
			matches += evidence(remote, candidate)
		}
		return when (val selection = select(matches)) {
			DownloadReconnectSelection.NoSafeMatch -> DownloadReconnectPlan.NoSafeMatch
			is DownloadReconnectSelection.Automatic -> DownloadReconnectPlan.Automatic(
				manga = candidates[selection.index],
				evidence = selection.evidence,
			)
			is DownloadReconnectSelection.Ambiguous -> DownloadReconnectPlan.Ambiguous(
				evidence = selection.evidence,
				candidates = selection.indexes.map(candidates::get),
			)
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

	private fun normalizeTitle(value: String): String = value
		.trim()
		.lowercase(Locale.ROOT)
		.replace(Regex("\\s+"), " ")

	companion object {
		internal fun select(matches: List<DownloadedContentMatch>): DownloadReconnectSelection {
			var strongest = DownloadedContentMatch.NONE
			val indexes = ArrayList<Int>()
			for ((index, evidence) in matches.withIndex()) {
				val comparison = rank(evidence).compareTo(rank(strongest))
				when {
					comparison > 0 -> {
						strongest = evidence
						indexes.clear()
						if (evidence != DownloadedContentMatch.NONE) indexes += index
					}
					comparison == 0 && evidence != DownloadedContentMatch.NONE -> indexes += index
				}
			}
			return when {
				indexes.isEmpty() -> DownloadReconnectSelection.NoSafeMatch
				indexes.size == 1 -> DownloadReconnectSelection.Automatic(indexes.single(), strongest)
				else -> DownloadReconnectSelection.Ambiguous(strongest, indexes)
			}
		}

		private fun rank(match: DownloadedContentMatch): Int = when (match) {
			DownloadedContentMatch.NONE -> 0
			DownloadedContentMatch.SOURCE_ALIAS_AND_CONTENT_URL -> 1
			DownloadedContentMatch.PUBLIC_URL -> 2
			DownloadedContentMatch.EXACT_ID -> 3
		}
	}
}
