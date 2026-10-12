package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Strict policy for the Similar Genres lane.
 *
 * Candidate discovery may be broad, but eligibility is not: SFW/NSFW boundaries and known content
 * kinds must match, and both sides must expose at least one canonical genre in common. Ranking then
 * prefers the strongest genre overlap, with shared themes and quality used only as tie-breakers.
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
	 * Discovery keywords prefer canonical genres over themes. Eligibility is still decided by
	 * [score], so a broad source search cannot leak unrelated titles into Similar Genres.
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
		if (seed.isNsfw != candidate.isNsfw) return null

		if (seed.kind != RelatedContentKind.UNKNOWN &&
			candidate.kind != RelatedContentKind.UNKNOWN &&
			seed.kind != candidate.kind
		) return null

		// The lane is explicitly "Similar Genres": no canonical genre evidence means no result.
		if (seed.genres.isEmpty() || candidate.genres.isEmpty()) return null
		val sharedGenres = seed.genres.count { it in candidate.genres }
		if (sharedGenres == 0) return null

		// Similarity is based on the seed's complete genre set. Do not infer a "primary" genre from
		// parser tag order: sources are not required to expose tags in semantic priority order.
		val genreSimilarity = sharedGenres * GENRE_SIMILARITY_SCALE / seed.genres.size
		val sharedThemes = seed.semanticTags.count { it in candidate.semanticTags }
		val kindBonus = if (seed.kind != RelatedContentKind.UNKNOWN && candidate.kind == seed.kind) {
			EXACT_KIND_TIE_BREAKER
		} else {
			0
		}
		return genreSimilarity +
			(sharedGenres * SHARED_GENRE_TIE_BREAKER) +
			kindBonus +
			(sharedThemes * THEME_SCORE) +
			candidate.qualityScore.coerceIn(0, QUALITY_SCORE_CAP)
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
			genres = genres,
			semanticTags = semanticTags,
			qualityScore = qualityScore(manga.rating),
		)
	}

	private fun qualityScore(rating: Float): Int {
		if (!rating.isFinite() || rating <= 0f) return 0
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

	private fun normalize(value: String) = value
		.lowercase()
		.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
		.replace(Regex("\\s+"), " ")
		.trim()

	private data class ScoredCandidate(val manga: Manga, val score: Int, val originalIndex: Int)

	private const val GENRE_SIMILARITY_SCALE = 10_000
	private const val SHARED_GENRE_TIE_BREAKER = 250
	private const val EXACT_KIND_TIE_BREAKER = 100
	private const val THEME_SCORE = 10
	private const val QUALITY_SCORE_CAP = 9
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
		"action" to "action", "aksi" to "action",
		"adventure" to "adventure", "petualangan" to "adventure",
		"romance" to "romance", "romantic" to "romance",
		"comedy" to "comedy", "komedi" to "comedy",
		"drama" to "drama",
		"fantasy" to "fantasy", "fantasi" to "fantasy", "dark fantasy" to "dark fantasy",
		"horror" to "horror", "horor" to "horror",
		"mystery" to "mystery", "misteri" to "mystery",
		"psychological" to "psychological", "thriller" to "thriller",
		"sci fi" to "sci fi", "science fiction" to "sci fi",
		"martial arts" to "martial arts", "martial art" to "martial arts",
		"school life" to "school life", "school" to "school life",
		"slice of life" to "slice of life", "supernatural" to "supernatural",
		"historical" to "historical", "history" to "historical",
		"sports" to "sports", "sport" to "sports", "tragedy" to "tragedy",
		"isekai" to "isekai",
		"reincarnation" to "reincarnation", "reincarnated" to "reincarnation",
		"cultivation" to "cultivation", "cultivator" to "cultivation",
		"wuxia" to "cultivation", "xianxia" to "cultivation",
		"dungeon" to "dungeon", "game" to "game", "gaming" to "game",
		"harem" to "harem", "reverse harem" to "reverse harem",
		"ecchi" to "ecchi", "smut" to "smut", "erotica" to "erotica",
		"boys love" to "boys love", "bl" to "boys love",
		"girls love" to "girls love", "gl" to "girls love",
	)
}

internal enum class RelatedContentKind {
	MANGA, MANHWA, MANHUA, COMICS, DOUJINSHI, NOVEL, UNKNOWN,
}

internal data class RecommendationSignals(
	val kind: RelatedContentKind,
	val isNsfw: Boolean,
	val genres: Set<String>,
	val semanticTags: Set<String>,
	val qualityScore: Int = 0,
)

internal data class RankedRecommendationKeyword(val value: String, val score: Int)
