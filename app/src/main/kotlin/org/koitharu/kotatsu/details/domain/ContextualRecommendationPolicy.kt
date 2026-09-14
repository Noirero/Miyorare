package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Feedback 1 recommendation policy.
 *
 * Priority is intentionally strict and cheap to evaluate after a source response arrives:
 * content kind -> SFW/NSFW -> primary genre -> additional genres/themes -> quality.
 * No extra source/network request is made by this policy.
 */
internal object ContextualRecommendationPolicy {

	fun rank(seed: Manga, candidates: List<Manga>): List<Manga> {
		if (candidates.isEmpty()) return emptyList()
		val seedSignals = signals(seed)
		return candidates.withIndex()
			.mapNotNull { indexed ->
				val candidateSignals = signals(indexed.value)
				val score = score(seedSignals, candidateSignals) ?: return@mapNotNull null
				ScoredCandidate(indexed.value, score, indexed.index)
			}
			.sortedWith(
				compareByDescending<ScoredCandidate> { it.score }
					.thenBy { it.originalIndex },
			)
			.map { it.manga }
	}

	/**
	 * Tags are stronger discovery signals than arbitrary words from a title. Genre tags receive the
	 * highest priority, followed by useful theme tags. Type/rating/administrative tags are excluded.
	 */
	fun tagKeywords(seed: Manga): List<RankedRecommendationKeyword> = seed.tags
		.asSequence()
		.mapNotNull { tag ->
			val raw = tag.title.trim()
			val normalized = normalize(raw)
			if (normalized.length < 3 || normalized in META_TAGS || normalized in LOW_SIGNAL_TAGS) {
				return@mapNotNull null
			}
			val genre = canonicalGenre(normalized)
			RankedRecommendationKeyword(
				value = raw,
				score = if (genre != null) GENRE_KEYWORD_SCORE else THEME_KEYWORD_SCORE + normalized.length,
			)
		}
		.distinctBy { normalize(it.value) }
		.sortedByDescending { it.score }
		.take(MAX_TAG_KEYWORDS)
		.toList()

	internal fun score(
		seed: RecommendationSignals,
		candidate: RecommendationSignals,
	): Int? {
		// SFW and NSFW are separate recommendation universes. Never cross this boundary.
		if (seed.isNsfw != candidate.isNsfw) return null

		// If both sides expose a reliable kind, manga/manhwa/manhua/etc. must not cross-mix.
		if (seed.kind != RelatedContentKind.UNKNOWN &&
			candidate.kind != RelatedContentKind.UNKNOWN &&
			seed.kind != candidate.kind
		) return null

		var score = 0
		if (seed.kind != RelatedContentKind.UNKNOWN) {
			score += if (candidate.kind == seed.kind) EXACT_KIND_SCORE else UNKNOWN_KIND_FALLBACK_SCORE
		}

		val primaryGenre = seed.primaryGenre
		if (primaryGenre != null) {
			if (primaryGenre in candidate.genres) {
				score += PRIMARY_GENRE_SCORE
			} else if (candidate.genres.isNotEmpty()) {
				// Keep a small fallback path for sparse catalogues, but ensure an Action match always
				// outranks a Romance-only result when Action is the seed's primary genre.
				score -= PRIMARY_GENRE_MISS_PENALTY
			}
		}

		val sharedGenres = seed.genres.count { it in candidate.genres }
		score += sharedGenres * ADDITIONAL_GENRE_SCORE

		val sharedThemes = seed.semanticTags.count { it in candidate.semanticTags }
		score += sharedThemes * THEME_SCORE

		// Quality is deliberately a final tie-breaker, never strong enough to override context.
		score += candidate.qualityScore.coerceIn(0, QUALITY_SCORE_CAP)
		return score
	}

	internal fun signals(manga: Manga): RecommendationSignals {
		val tags = manga.tags
			.asSequence()
			.map { normalize(it.title) }
			.filter { it.isNotEmpty() }
			.toCollection(LinkedHashSet())
		val genres = tags.mapNotNullTo(LinkedHashSet(), ::canonicalGenre)
		val semanticTags = tags.filterTo(LinkedHashSet()) { tag ->
			tag !in META_TAGS && tag !in LOW_SIGNAL_TAGS
		}
		return RecommendationSignals(
			kind = inferKind(tags),
			isNsfw = manga.isNsfw(),
			primaryGenre = genres.firstOrNull(),
			genres = genres,
			semanticTags = semanticTags,
			qualityScore = qualityScore(manga.rating),
		)
	}

	private fun qualityScore(rating: Float): Int {
		if (!rating.isFinite() || rating <= 0f) return 0
		// Parser ratings vary by source. Clamp instead of assuming a single 5/10/100 scale.
		return (rating * 10f).toInt().coerceAtMost(QUALITY_SCORE_CAP)
	}

	private fun inferKind(tags: Set<String>): RelatedContentKind {
		fun hasAny(markers: Set<String>) = tags.any { tag ->
			tag in markers || markers.any { marker -> tag.startsWith("$marker ") || tag.endsWith(" $marker") }
		}
		return when {
			hasAny(MANHWA_MARKERS) -> RelatedContentKind.MANHWA
			hasAny(MANHUA_MARKERS) -> RelatedContentKind.MANHUA
			hasAny(DOUJINSHI_MARKERS) -> RelatedContentKind.DOUJINSHI
			hasAny(NOVEL_MARKERS) -> RelatedContentKind.NOVEL
			hasAny(COMICS_MARKERS) -> RelatedContentKind.COMICS
			hasAny(MANGA_MARKERS) -> RelatedContentKind.MANGA
			else -> RelatedContentKind.UNKNOWN
		}
	}

	private fun canonicalGenre(tag: String): String? = GENRE_ALIASES[tag]

	private fun normalize(value: String): String = value
		.lowercase()
		.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
		.replace(Regex("\\s+"), " ")
		.trim()

	private data class ScoredCandidate(
		val manga: Manga,
		val score: Int,
		val originalIndex: Int,
	)

	private const val EXACT_KIND_SCORE = 4_000
	private const val UNKNOWN_KIND_FALLBACK_SCORE = 600
	private const val PRIMARY_GENRE_SCORE = 3_000
	private const val PRIMARY_GENRE_MISS_PENALTY = 900
	private const val ADDITIONAL_GENRE_SCORE = 550
	private const val THEME_SCORE = 90
	private const val QUALITY_SCORE_CAP = 80
	private const val GENRE_KEYWORD_SCORE = 20_000
	private const val THEME_KEYWORD_SCORE = 10_000
	private const val MAX_TAG_KEYWORDS = 4

	private val MANHWA_MARKERS = setOf("manhwa", "korean", "korea", "webtoon")
	private val MANHUA_MARKERS = setOf("manhua", "chinese", "china")
	private val MANGA_MARKERS = setOf("manga", "japanese", "japan", "shounen", "shoujo", "seinen", "josei", "kodomo")
	private val COMICS_MARKERS = setOf("comic", "comics", "western comic", "western comics")
	private val DOUJINSHI_MARKERS = setOf("doujin", "doujinshi")
	private val NOVEL_MARKERS = setOf("novel", "light novel", "web novel")

	private val META_TAGS = MANHWA_MARKERS + MANHUA_MARKERS + MANGA_MARKERS + COMICS_MARKERS +
		DOUJINSHI_MARKERS + NOVEL_MARKERS + setOf(
			"safe", "sfw", "nsfw", "adult", "mature", "suggestive", "18", "18+", "16", "16+",
			"ongoing", "completed", "complete", "finished", "hiatus", "cancelled", "canceled",
		)

	private val LOW_SIGNAL_TAGS = setOf(
		"other", "unknown", "none", "uncategorized", "translated", "translation", "full color",
	)

	private val GENRE_ALIASES = mapOf(
		"action" to "action",
		"aksi" to "action",
		"adventure" to "adventure",
		"petualangan" to "adventure",
		"romance" to "romance",
		"romantic" to "romance",
		"comedy" to "comedy",
		"komedi" to "comedy",
		"drama" to "drama",
		"fantasy" to "fantasy",
		"fantasi" to "fantasy",
		"dark fantasy" to "dark fantasy",
		"horror" to "horror",
		"horor" to "horror",
		"mystery" to "mystery",
		"misteri" to "mystery",
		"psychological" to "psychological",
		"thriller" to "thriller",
		"sci fi" to "sci fi",
		"science fiction" to "sci fi",
		"martial arts" to "martial arts",
		"martial art" to "martial arts",
		"school life" to "school life",
		"school" to "school life",
		"slice of life" to "slice of life",
		"supernatural" to "supernatural",
		"historical" to "historical",
		"history" to "historical",
		"sports" to "sports",
		"sport" to "sports",
		"tragedy" to "tragedy",
		"isekai" to "isekai",
		"reincarnation" to "reincarnation",
		"reincarnated" to "reincarnation",
		"cultivation" to "cultivation",
		"cultivator" to "cultivation",
		"wuxia" to "cultivation",
		"xianxia" to "cultivation",
		"dungeon" to "dungeon",
		"game" to "game",
		"gaming" to "game",
		"harem" to "harem",
		"reverse harem" to "reverse harem",
		"ecchi" to "ecchi",
		"smut" to "smut",
		"erotica" to "erotica",
		"boys love" to "boys love",
		"bl" to "boys love",
		"girls love" to "girls love",
		"gl" to "girls love",
	)
}

internal enum class RelatedContentKind {
	MANGA,
	MANHWA,
	MANHUA,
	COMICS,
	DOUJINSHI,
	NOVEL,
	UNKNOWN,
}

internal data class RecommendationSignals(
	val kind: RelatedContentKind,
	val isNsfw: Boolean,
	val primaryGenre: String?,
	val genres: Set<String>,
	val semanticTags: Set<String>,
	val qualityScore: Int = 0,
)

internal data class RankedRecommendationKeyword(
	val value: String,
	val score: Int,
)
