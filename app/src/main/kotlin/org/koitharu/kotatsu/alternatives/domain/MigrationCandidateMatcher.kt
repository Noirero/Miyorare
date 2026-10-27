package org.koitharu.kotatsu.alternatives.domain

import dagger.Reusable
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import java.text.Normalizer
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class MigrationMatchState {
	READY,
	NEEDS_REVIEW,
	NO_REPLACEMENT,
}

data class MigrationCandidateObservation(
	val manga: Manga,
	val matchedQueries: Set<String> = emptySet(),
)

data class MigrationCandidateEvidence(
	val score: Int,
	val exactTitleMatch: Boolean,
	val titleSimilarity: Float,
	val matchedSeedQuery: Boolean,
	val authorMatch: Boolean,
	val authorConflict: Boolean,
	val chapterMatches: Int,
	val chapterCoverage: Float,
	val strongChapterMatch: Boolean,
	val chapterConflict: Boolean,
	val autoEligible: Boolean,
)

data class MigrationMatchCandidate(
	val manga: Manga,
	val matchedQueries: Set<String>,
	val evidence: MigrationCandidateEvidence,
)

data class MigrationMatchAssessment(
	val state: MigrationMatchState,
	val best: MigrationMatchCandidate?,
	val candidates: List<MigrationMatchCandidate>,
)

private val LEADING_MIGRATION_DECORATOR = Regex(
	"""^\s*(?:\[[^\]\r\n]{1,40}]|\([^\)\r\n]{1,40}\)|\{[^}\r\n]{1,40}\}|【[^】\r\n]{1,40}】|（[^）\r\n]{1,40}）)\s*[-–—:|·]*\s*""",
)

internal fun migrationTitleVariants(value: String): List<String> {
	val original = value.trim()
	if (original.isEmpty()) return emptyList()
	var cleaned = original
	var stripped = 0
	while (stripped < 3) {
		val match = LEADING_MIGRATION_DECORATOR.find(cleaned) ?: break
		val next = cleaned.removeRange(match.range).trim()
		if (next.isEmpty() || next == cleaned) break
		cleaned = next
		stripped++
	}
	return if (cleaned == original) listOf(original) else listOf(original, cleaned)
}

internal fun normalizeMigrationTitle(value: String): String {
	val decomposed = Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
	return buildString(decomposed.length) {
		for (char in decomposed) {
			if (char.isLetterOrDigit()) append(char)
		}
	}
}

/**
 * Ranks candidates returned by installed-source search so review UI can stay transparent.
 * Automatic migration remains stricter: it still needs corroborating identity evidence and a clear winner.
 * Synthetic candidates without search provenance still need minimum identity credibility.
 */
@Reusable
class MigrationCandidateMatcher @Inject constructor() {

	fun assess(
		seed: Manga,
		observations: Collection<MigrationCandidateObservation>,
	): MigrationMatchAssessment {
		val ranked = rank(seed, observations)
		val best = ranked.firstOrNull()
		if (best == null) {
			return MigrationMatchAssessment(MigrationMatchState.NO_REPLACEMENT, null, emptyList())
		}
		val runnerUp = ranked.getOrNull(1)
		val ambiguous = runnerUp != null && best.evidence.score - runnerUp.evidence.score < READY_SCORE_MARGIN
		val state = if (best.evidence.autoEligible && !ambiguous) {
			MigrationMatchState.READY
		} else {
			MigrationMatchState.NEEDS_REVIEW
		}
		return MigrationMatchAssessment(state, best, ranked.take(MAX_REVIEW_CANDIDATES))
	}

	fun rank(
		seed: Manga,
		observations: Collection<MigrationCandidateObservation>,
	): List<MigrationMatchCandidate> {
		return observations
			.groupBy { CandidateKey(it.manga.source.name, it.manga.id) }
			.mapNotNull { (_, group) ->
				val representative = group
					.map(MigrationCandidateObservation::manga)
					.sortedWith(
						compareByDescending<Manga> { it.chapters.orEmpty().size }
							.thenByDescending { it.authors.size }
							.thenByDescending { it.altTitles.size }
							.thenBy { normalizeMigrationTitle(it.title) }
							.thenBy { it.publicUrl },
					)
					.first()
				val matchedQueries = group.flatMapTo(sortedSetOf()) { it.matchedQueries }
				val evidence = score(seed, representative, matchedQueries)
				if (matchedQueries.isEmpty() && !isCredible(evidence)) return@mapNotNull null
				MigrationMatchCandidate(representative, matchedQueries, evidence)
			}
			.sortedWith(
				compareByDescending<MigrationMatchCandidate> { it.evidence.score }
					.thenByDescending { it.evidence.exactTitleMatch }
					.thenByDescending { it.evidence.authorMatch }
					.thenByDescending { it.evidence.strongChapterMatch }
					.thenBy { it.manga.source.name }
					.thenBy { it.manga.id }
					.thenBy { normalizeMigrationTitle(it.manga.title) },
			)
	}

	private fun score(
		seed: Manga,
		candidate: Manga,
		matchedQueries: Set<String>,
	): MigrationCandidateEvidence {
		val seedTitles = seed.normalizedTitles()
		val candidateTitles = candidate.normalizedTitles()
		val exactTitleMatch = seedTitles.any(candidateTitles::contains)
		val titleSimilarity = bestTitleSimilarity(seedTitles, candidateTitles)
		val matchedSeedQuery = matchedQueries
			.asSequence()
			.map(::normalizeMigrationTitle)
			.filter(String::isNotEmpty)
			.any(seedTitles::contains)

		var score = when {
			exactTitleMatch -> 50
			titleSimilarity >= 0.95f -> 45
			titleSimilarity >= 0.88f -> 35
			matchedSeedQuery -> 30
			titleSimilarity >= 0.78f -> 20
			else -> 0
		}

		val seedAuthors = seed.authors.mapTo(HashSet(), ::normalizeMigrationTitle).filterTo(HashSet(), String::isNotEmpty)
		val candidateAuthors = candidate.authors.mapTo(HashSet(), ::normalizeMigrationTitle).filterTo(HashSet(), String::isNotEmpty)
		val authorMatch = seedAuthors.isNotEmpty() && candidateAuthors.isNotEmpty() && seedAuthors.any { first ->
			candidateAuthors.any { second -> namesMatch(first, second) }
		}
		val authorConflict = seedAuthors.isNotEmpty() && candidateAuthors.isNotEmpty() && !authorMatch
		when {
			authorMatch -> score += 25
			authorConflict -> score -= 30
		}

		val chapterEvidence = chapterEvidence(seed, candidate)
		score += when {
			chapterEvidence.conflict -> -20
			chapterEvidence.strong -> 20
			chapterEvidence.matches >= 2 && chapterEvidence.coverage >= 0.4f -> 10
			else -> 0
		}
		if (chapterEvidence.countsClose) score += 5

		val autoEligible = score >= READY_MIN_SCORE &&
			!authorConflict &&
			!chapterEvidence.conflict &&
			(authorMatch || chapterEvidence.strong) &&
			(exactTitleMatch || titleSimilarity >= 0.88f || matchedSeedQuery)

		return MigrationCandidateEvidence(
			score = score.coerceIn(0, 100),
			exactTitleMatch = exactTitleMatch,
			titleSimilarity = titleSimilarity,
			matchedSeedQuery = matchedSeedQuery,
			authorMatch = authorMatch,
			authorConflict = authorConflict,
			chapterMatches = chapterEvidence.matches,
			chapterCoverage = chapterEvidence.coverage,
			strongChapterMatch = chapterEvidence.strong,
			chapterConflict = chapterEvidence.conflict,
			autoEligible = autoEligible,
		)
	}

	private fun isCredible(evidence: MigrationCandidateEvidence): Boolean =
		evidence.exactTitleMatch || evidence.titleSimilarity >= 0.78f || evidence.matchedSeedQuery

	private fun chapterEvidence(seed: Manga, candidate: Manga): ChapterEvidence {
		val seedKeys = seed.chapters.orEmpty()
			.asSequence()
			.filter { it.number > 0f }
			.map { it.volume to it.number }
			.toSet()
		val candidateKeys = candidate.chapters.orEmpty()
			.asSequence()
			.filter { it.number > 0f }
			.map { it.volume to it.number }
			.toSet()
		val smaller = min(seedKeys.size, candidateKeys.size)
		val matches = if (smaller == 0) 0 else seedKeys.intersect(candidateKeys).size
		val coverage = if (smaller == 0) 0f else matches.toFloat() / smaller.toFloat()
		val strong = matches >= MIN_STRONG_CHAPTER_MATCHES && coverage >= STRONG_CHAPTER_COVERAGE
		val conflict = smaller >= MIN_STRONG_CHAPTER_MATCHES && matches == 0

		val seedCount = seed.chapters.orEmpty().size
		val candidateCount = candidate.chapters.orEmpty().size
		val tolerance = max(2, max(seedCount, candidateCount) / 10)
		val countsClose = seedCount > 0 && candidateCount > 0 && abs(seedCount - candidateCount) <= tolerance
		return ChapterEvidence(matches, coverage, strong, conflict, countsClose)
	}

	private fun bestTitleSimilarity(first: Set<String>, second: Set<String>): Float {
		var best = 0f
		for (a in first) {
			for (b in second) {
				if (a == b) return 1f
				if (a.length < MIN_FUZZY_TITLE_LENGTH || b.length < MIN_FUZZY_TITLE_LENGTH) continue
				val longest = max(a.length, b.length)
				if (longest == 0) continue
				val similarity = 1f - a.levenshteinDistance(b).toFloat() / longest.toFloat()
				if (similarity > best) best = similarity
			}
		}
		return best
	}

	private fun Manga.normalizedTitles(): Set<String> = buildSet {
		for (variant in migrationTitleVariants(title)) {
			normalizeMigrationTitle(variant).takeIf(String::isNotEmpty)?.let(::add)
		}
		for (alias in altTitles) {
			for (variant in migrationTitleVariants(alias)) {
				normalizeMigrationTitle(variant).takeIf(String::isNotEmpty)?.let(::add)
			}
		}
	}

	private fun namesMatch(first: String, second: String): Boolean {
		if (first == second) return true
		if (min(first.length, second.length) < MIN_AUTHOR_CONTAINMENT_LENGTH) return false
		return first.contains(second) || second.contains(first)
	}

	private data class CandidateKey(val source: String, val id: Long)

	private data class ChapterEvidence(
		val matches: Int,
		val coverage: Float,
		val strong: Boolean,
		val conflict: Boolean,
		val countsClose: Boolean,
	)

	private companion object {
		const val READY_MIN_SCORE = 70
		const val READY_SCORE_MARGIN = 12
		const val MAX_REVIEW_CANDIDATES = 20
		const val MIN_FUZZY_TITLE_LENGTH = 4
		const val MIN_AUTHOR_CONTAINMENT_LENGTH = 4
		const val MIN_STRONG_CHAPTER_MATCHES = 3
		const val STRONG_CHAPTER_COVERAGE = 0.6f
	}
}
