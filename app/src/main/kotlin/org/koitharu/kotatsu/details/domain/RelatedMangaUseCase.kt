package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

data class RelatedMangaGroup(
	val keyword: String?,
	val manga: List<Manga>,
)

@Singleton
class RelatedMangaUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	private val searchCacheMutex = Mutex()
	private val keywordSearchCache = object : LinkedHashMap<SearchCacheKey, SearchCacheEntry>(
		KEYWORD_CACHE_CAPACITY,
		0.75f,
		true,
	) {
		override fun removeEldestEntry(
			eldest: MutableMap.MutableEntry<SearchCacheKey, SearchCacheEntry>?,
		): Boolean = size > KEYWORD_CACHE_CAPACITY
	}

	suspend operator fun invoke(seed: Manga) = runCatchingCancellable {
		loadPrimary(seed)
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrNull()

	suspend fun getGroups(seed: Manga): List<RelatedMangaGroup> {
		val result = ArrayList<RelatedMangaGroup>()
		collectGroups(seed) { group -> result += group }
		return result
	}

	/**
	 * Emits expanded Related groups progressively. Details still uses [invoke], so simply opening a
	 * manga never fans out several searches. The expanded screen starts at most two source requests
	 * at once, then publishes each useful group as soon as its ranked turn is ready.
	 */
	suspend fun collectGroups(
		seed: Manga,
		emit: suspend (RelatedMangaGroup) -> Unit,
	) = coroutineScope {
		val repository = mangaRepositoryFactory.create(seed.source)
		val primaryDeferred = async {
			runCatchingCancellable { repository.getRelated(seed) }.getOrDefault(emptyList())
		}

		if (seed.source == LocalMangaSource) {
			val primary = primaryDeferred.await()
				.filterNot { it.id == seed.id }
				.distinctBy { it.id }
				.take(MAX_ITEMS_PER_GROUP)
			if (primary.isNotEmpty()) {
				emit(RelatedMangaGroup(keyword = null, manga = primary))
			}
			return@coroutineScope
		}

		val keywords = buildRelatedKeywords(seed)
		val limiter = Semaphore(MAX_PARALLEL_SEARCHES)
		val keywordDeferred = keywords.map { keyword ->
			keyword to async {
				limiter.withPermit { searchKeyword(repository, keyword) }
			}
		}

		val seen = HashSet<Long>()
		val previousFingerprints = ArrayList<Set<Long>>(keywords.size + 1)

		val primary = primaryDeferred.await()
			.asSequence()
			.filterNot { it.id == seed.id }
			.distinctBy { it.id }
			.take(MAX_ITEMS_PER_GROUP)
			.toList()
		if (primary.isNotEmpty()) {
			primary.forEach { seen += it.id }
			previousFingerprints += primary.toFingerprint()
			emit(RelatedMangaGroup(keyword = null, manga = primary))
		}

		for ((keyword, deferred) in keywordDeferred) {
			val raw = deferred.await()
				.asSequence()
				.filterNot { it.id == seed.id }
				.distinctBy { it.id }
				.take(MAX_RAW_RESULTS_PER_KEYWORD)
				.toList()
			if (raw.isEmpty()) continue

			val fingerprint = raw.toFingerprint()
			val looksQueryInsensitive = previousFingerprints.any {
				fingerprint.overlapRatio(it) >= QUERY_INSENSITIVE_OVERLAP
			}
			previousFingerprints += fingerprint

			val literalMatches = raw.filter { it.matchesKeyword(keyword) }
			val candidates = when {
				!looksQueryInsensitive -> raw
				literalMatches.isNotEmpty() -> literalMatches
				else -> emptyList()
			}
			if (candidates.isEmpty()) continue

			val uniqueItems = candidates
				.asSequence()
				.filter { it.id !in seen }
				.take(MAX_ITEMS_PER_GROUP)
				.toList()

			val items = ArrayList<Manga>(MAX_ITEMS_PER_GROUP)
			items += uniqueItems
			if (items.size < MIN_ITEMS_BEFORE_OVERLAP && items.size < candidates.size) {
				val alreadyIncluded = items.mapTo(HashSet<Long>()) { it.id }
				candidates.asSequence()
					.filter { it.id !in alreadyIncluded }
					.take(minOf(MIN_ITEMS_BEFORE_OVERLAP - items.size, MAX_ITEMS_PER_GROUP - items.size))
					.forEach { manga ->
						items += manga
						alreadyIncluded += manga.id
					}
			}

			if (items.isNotEmpty()) {
				items.forEach { seen += it.id }
				emit(RelatedMangaGroup(keyword = keyword, manga = items))
			}
		}
	}

	private suspend fun loadPrimary(seed: Manga): List<Manga> {
		val repository = mangaRepositoryFactory.create(seed.source)
		val related = repository.getRelated(seed)
		if (related.isNotEmpty() || seed.source == LocalMangaSource) {
			return related
		}

		// Keep the Details preview cheap: only one bounded fallback query is allowed here.
		val keyword = buildRelatedKeywords(seed).firstOrNull() ?: return emptyList()
		return searchKeyword(repository, keyword)
			.asSequence()
			.filterNot { it.id == seed.id }
			.filter { it.matchesKeyword(keyword) }
			.distinctBy { it.id }
			.take(MAX_ITEMS_PER_GROUP)
			.toList()
	}

	private suspend fun searchKeyword(repository: MangaRepository, keyword: String): List<Manga> {
		val key = SearchCacheKey(repository.source.name, keyword.lowercase())
		val now = System.currentTimeMillis()
		val cached = searchCacheMutex.withLock {
			val entry = keywordSearchCache[key]
			if (entry != null && now - entry.cachedAt < KEYWORD_CACHE_TTL_MS) {
				entry.manga
			} else {
				if (entry != null) keywordSearchCache.remove(key)
				null
			}
		}
		if (cached != null) return cached

		val order = SortOrder.RELEVANCE.takeIf { it in repository.sortOrders } ?: repository.defaultSortOrder
		val result = runCatchingCancellable {
			repository.getList(
				offset = 0,
				order = order,
				filter = MangaListFilter(query = keyword),
			)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(emptyList())

		searchCacheMutex.withLock {
			keywordSearchCache[key] = SearchCacheEntry(System.currentTimeMillis(), result)
		}
		return result
	}

	private fun buildRelatedKeywords(seed: Manga): List<String> {
		val unique = LinkedHashMap<String, RankedKeyword>()
		var position = 0
		sequenceOf(seed.title)
			.plus(seed.altTitles.asSequence())
			.forEach { title ->
				RELATED_TERM_REGEX.findAll(title).forEach { match ->
					val term = match.value.trim()
					val normalized = term.lowercase()
					if (term.length > 2 &&
						term.any { it.isLetter() } &&
						normalized !in RELATED_STOP_WORDS
					) {
						unique.putIfAbsent(
							normalized,
							RankedKeyword(
								value = term,
								score = keywordScore(term),
								position = position,
							),
						)
						position++
					}
				}
			}

		val limit = when {
			unique.size <= BASE_RELATED_KEYWORDS -> BASE_RELATED_KEYWORDS
			else -> minOf(unique.size, MAX_RELATED_KEYWORDS)
		}
		return unique.values
			.sortedWith(
				compareByDescending<RankedKeyword> { it.score }
					.thenBy { it.position },
			)
			.take(limit)
			.map { it.value }
	}

	private fun keywordScore(keyword: String): Int =
		(if ('-' in keyword) HYPHENATED_KEYWORD_BONUS else 0) +
			(if ('\'' in keyword || '’' in keyword) APOSTROPHE_KEYWORD_BONUS else 0) +
			keyword.count { it.isLetterOrDigit() }

	private fun Manga.matchesKeyword(keyword: String): Boolean {
		val needle = normalizeForMatch(keyword)
		if (needle.isEmpty()) return false
		return sequenceOf(title)
			.plus(altTitles.asSequence())
			.any { normalizeForMatch(it).contains(needle) }
	}

	private fun List<Manga>.toFingerprint(): Set<Long> = asSequence()
		.take(FINGERPRINT_SIZE)
		.mapTo(LinkedHashSet()) { it.id }

	private fun Set<Long>.overlapRatio(other: Set<Long>): Double {
		val base = minOf(size, other.size)
		if (base < MIN_FINGERPRINT_SIZE) return 0.0
		return count { it in other }.toDouble() / base
	}

	private fun normalizeForMatch(value: String): String = value
		.lowercase()
		.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
		.replace(Regex("\\s+"), " ")
		.trim()

	private data class RankedKeyword(
		val value: String,
		val score: Int,
		val position: Int,
	)

	private data class SearchCacheKey(
		val sourceName: String,
		val keyword: String,
	)

	private data class SearchCacheEntry(
		val cachedAt: Long,
		val manga: List<Manga>,
	)

	private companion object {
		const val BASE_RELATED_KEYWORDS = 4
		const val MAX_RELATED_KEYWORDS = 6
		const val MAX_PARALLEL_SEARCHES = 2
		const val MAX_ITEMS_PER_GROUP = 12
		const val MAX_RAW_RESULTS_PER_KEYWORD = 24
		const val MIN_ITEMS_BEFORE_OVERLAP = 4
		const val FINGERPRINT_SIZE = 8
		const val MIN_FINGERPRINT_SIZE = 4
		const val QUERY_INSENSITIVE_OVERLAP = 0.80
		const val KEYWORD_CACHE_CAPACITY = 36
		const val KEYWORD_CACHE_TTL_MS = 10 * 60 * 1000L
		const val HYPHENATED_KEYWORD_BONUS = 100
		const val APOSTROPHE_KEYWORD_BONUS = 40

		val RELATED_TERM_REGEX = Regex("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)+|[\\p{L}\\p{N}]+")
		val RELATED_STOP_WORDS = setOf(
			"a", "an", "and", "are", "as", "at", "after", "before", "by", "for", "from",
			"he", "her", "his", "i", "in", "into", "is", "it", "its", "me", "my", "of",
			"on", "our", "she", "that", "the", "their", "them", "they", "this", "to", "we",
			"we're", "with", "you", "your", "manga", "manhwa", "manhua", "comic", "comics",
			"chapter", "chapters", "volume", "vol",
		)
	}
}
