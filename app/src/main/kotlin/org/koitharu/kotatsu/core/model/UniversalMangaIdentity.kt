package org.koitharu.kotatsu.core.model

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Cross-source identity evidence for the same work.
 *
 * Numeric manga ids are source-local and therefore intentionally never participate in identity.
 * The matcher uses only metadata already present on the manga objects, so it performs no network,
 * database, or background work and is safe to reuse from duplicate detection and source fusion.
 */
object UniversalMangaIdentity {

	const val LIKELY_MATCH_SCORE = 500
	const val STRONG_MATCH_SCORE = 650

	data class Evidence(
		val score: Int,
		val titleScore: Int,
		val authorScore: Int,
		val exactTitle: Boolean,
		val authorOverlap: Boolean,
	)

	fun evaluate(reference: Manga, candidate: Manga): Evidence {
		val referenceTitles = titleKeys(reference)
		val candidateTitles = titleKeys(candidate)
		val title = scoreTitles(referenceTitles, candidateTitles)
		if (title.score == 0) {
			return Evidence(0, 0, 0, false, false)
		}

		val referenceAuthors = authorKeys(reference)
		val candidateAuthors = authorKeys(candidate)
		val authorOverlap = referenceAuthors.isNotEmpty() && candidateAuthors.isNotEmpty() &&
			referenceAuthors.any(candidateAuthors::contains)
		val authorScore = when {
			authorOverlap -> 140
			referenceAuthors.isEmpty() || candidateAuthors.isEmpty() -> 0
			else -> -90
		}
		return Evidence(
			score = (title.score + authorScore).coerceAtLeast(0),
			titleScore = title.score,
			authorScore = authorScore,
			exactTitle = title.exact,
			authorOverlap = authorOverlap,
		)
	}

	fun isLikelySame(reference: Manga, candidate: Manga, minimumScore: Int = LIKELY_MATCH_SCORE): Boolean =
		evaluate(reference, candidate).score >= minimumScore

	fun titleKeys(manga: Manga): Set<String> = buildSet(manga.altTitles.size + 1) {
		normalize(manga.title).takeIf { it.length >= MIN_TITLE_LENGTH }?.let(::add)
		for (title in manga.altTitles) {
			normalize(title).takeIf { it.length >= MIN_TITLE_LENGTH }?.let(::add)
		}
	}

	fun authorKeys(manga: Manga): Set<String> = manga.authors.mapNotNullTo(LinkedHashSet()) { author ->
		normalize(author).takeIf { it.length >= MIN_AUTHOR_LENGTH }
	}

	private fun scoreTitles(reference: Set<String>, candidate: Set<String>): TitleEvidence {
		if (reference.isEmpty() || candidate.isEmpty()) return TitleEvidence(0, false)
		if (reference.any(candidate::contains)) return TitleEvidence(620, true)

		var best = 0.0
		for (left in reference) {
			for (right in candidate) {
				val maxLength = maxOf(left.length, right.length)
				if (maxLength == 0) continue
				val distance = left.levenshteinDistance(right)
				val similarity = 1.0 - distance.toDouble() / maxLength.toDouble()
				if (similarity > best) best = similarity
			}
		}
		val score = when {
			best >= 0.94 -> (590 * best).roundToInt()
			best >= 0.86 -> (550 * best).roundToInt()
			best >= 0.76 -> (500 * best).roundToInt()
			else -> 0
		}
		return TitleEvidence(score, false)
	}

	private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
		.lowercase(Locale.ROOT)
		.replace(NON_WORD, " ")
		.trim()
		.replace(REPEATED_SPACE, " ")

	private data class TitleEvidence(val score: Int, val exact: Boolean)

	private const val MIN_TITLE_LENGTH = 3
	private const val MIN_AUTHOR_LENGTH = 2
	private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
	private val REPEATED_SPACE = Regex("\\s+")
}
