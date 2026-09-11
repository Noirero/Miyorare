package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lightweight, deterministic scorer for cross-source mirror candidates.
 *
 * This deliberately uses metadata that is already available from search/details. It performs no
 * network or database I/O, so Smart Source Fusion does not add work to startup, Reader, Downloads,
 * or any screen that is not actively looking for alternatives.
 */
object SourceFusionScorer {

	const val MIN_SEARCH_CANDIDATE_SCORE = 260
	const val STRONG_SEARCH_CANDIDATE_SCORE = 520

	fun score(reference: Manga, candidate: Manga): Int {
		val referenceTitles = titleKeys(reference)
		val candidateTitles = titleKeys(candidate)
		val titleScore = scoreTitles(referenceTitles, candidateTitles)
		if (titleScore <= 0) return 0

		val authorScore = scoreAuthors(reference.authors, candidate.authors)
		val chapterScore = scoreChapters(reference.chaptersCount(), candidate.chaptersCount())
		return titleScore + authorScore + chapterScore
	}

	private fun scoreTitles(reference: Set<String>, candidate: Set<String>): Int {
		if (reference.isEmpty() || candidate.isEmpty()) return 0
		if (reference.any(candidate::contains)) return 600

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
		return when {
			best >= 0.92 -> (520 * best).roundToInt()
			best >= 0.80 -> (450 * best).roundToInt()
			best >= 0.68 -> (360 * best).roundToInt()
			else -> 0
		}
	}

	private fun scoreAuthors(reference: Collection<String>, candidate: Collection<String>): Int {
		val left = reference.mapNotNullTo(LinkedHashSet()) { author ->
			normalize(author).takeIf { it.isNotEmpty() }
		}
		val right = candidate.mapNotNullTo(LinkedHashSet()) { author ->
			normalize(author).takeIf { it.isNotEmpty() }
		}
		if (left.isEmpty() || right.isEmpty()) return 0
		if (left.any(right::contains)) return 120
		return if (left.any { author -> right.any { other -> author in other || other in author } }) 70 else 0
	}

	private fun scoreChapters(reference: Int, candidate: Int): Int {
		if (reference <= 0 || candidate <= 0) return 0
		val ratio = minOf(reference, candidate).toDouble() / maxOf(reference, candidate).toDouble()
		val coverage = (ratio * 160.0).roundToInt()
		val noRegressionBonus = if (candidate >= reference) 40 else 0
		return coverage + noRegressionBonus
	}

	private fun titleKeys(manga: Manga): Set<String> = buildSet {
		normalize(manga.title).takeIf { it.isNotEmpty() }?.let(::add)
		for (title in manga.altTitles) {
			normalize(title).takeIf { it.isNotEmpty() }?.let(::add)
		}
	}

	private fun normalize(value: String?): String = value
		.orEmpty()
		.lowercase(Locale.ROOT)
		.replace(NON_WORD, " ")
		.trim()
		.replace(REPEATED_SPACE, " ")

	private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
	private val REPEATED_SPACE = Regex("\\s+")
}
