package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.MultiMutex
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

	/**
	 * One limiter for the whole Related feature, not one limiter per screen/request. Cache hits never
	 * take a permit. This keeps multiple Details/Related collectors from multiplying source traffic.
	 */
	private val networkLimiter = Semaphore(MAX_PARALLEL_NETWORK_OPERATIONS)
	private val keywordRequestMutex = MultiMutex<SearchCacheKey>()
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
	 * Emits useful groups as soon as each request completes. [includePrimary] is false for inline
	 * Details because that screen already has its lightweight preview; this avoids asking the source
	 * for Related twice. Every source operation shares [networkLimiter], and failure of one keyword
	 * never cancels successful sibling groups.
	 */
	suspend fun collectGroups(
		seed: Manga,
		includePrimary: Boolean = true,
		excludedIds: Set<Long> = emptySet(),
		emit: suspend (RelatedMangaGroup) -> Unit,
	) = coroutineScope {
		val repository = mangaRepositoryFactory.create(seed.source)

		if (seed.source == LocalMangaSource) {
			if (!includePrimary) return@coroutineScope
			val primary = getRelatedSafely(repository, seed)
				.asSequence()
				.filterNot { it.id == seed.id || it.id in excludedIds }
				.distinctBy { it.id }
				.take(MAX_ITEMS_PER_GROUP)
				.toList()
			if (primary.isNotEmpty()) {
				emit(RelatedMangaGroup(keyword = null, manga = primary))
			}
			return@coroutineScope
		}

		val keywords = buildRelatedKeywords(seed)
		val taskCount = keywords.size + if (includePrimary) 1 else 0
		if (taskCount == 0) return@coroutineScope

		val completed = Channel<CompletedRelatedTask>(capacity = taskCount)
		if (includePrimary) {
			launch {
				completed.send(CompletedRelatedTask.Primary(getRelatedSafely(repository, seed)))
			}
		}
		keywords.forEach { keyword ->
			launch {
				completed.send(
					CompletedRelatedTask.Keyword(
						keyword = keyword,
						manga = searchKeyword(repository, keyword),
					),
				)
			}
		}

		val seen = HashSet<Long>(excludedIds)
		val keywordFingerprints = ArrayList<ResultFingerprint>(keywords.size)
		var queryInsensitiveEvidence = 0

		repeat(taskCount) {
			when (val task = completed.receive()) {
				is CompletedRelatedTask.Primary -> {
					val primary = task.manga
						.asSequence()
						.filterNot { it.id == seed.id || it.id in excludedIds }
						.distinctBy { it.id }
						.take(MAX_ITEMS_PER_GROUP)
						.toList()
					if (primary.isNotEmpty()) {
						primary.forEach { seen += it.id }
						emit(RelatedMangaGroup(keyword = null, manga = primary))
					}
				}

				is CompletedRelatedTask.Keyword -> {
					val raw = task.manga
						.asSequence()
						.filterNot { it.id == seed.id }
						.distinctBy { it.id }
						.take(MAX_RAW_RESULTS_PER_KEYWORD)
						.toList()
					if (raw.isEmpty()) return@repeat

					val fingerprint = raw.toFingerprint()
					val literalMatches = raw.filter { it.matchesKeyword(task.keyword) }
					val literalRatio = literalMatches.size.toDouble() / raw.size
					val resemblesPreviousQuery = keywordFingerprints.any { previous ->
						fingerprint.isNearDuplicateOf(previous)
					}
					if (resemblesPreviousQuery && literalRatio < MIN_LITERAL_MATCH_RATIO) {
						queryInsensitiveEvidence++
					}
					keywordFingerprints += fingerprint

					// Do not punish one legitimate pair of similar keyword searches. Switch to literal guarding
					// only after repeated near-identical ordering plus weak literal relevance provides evidence
					// that the source is ignoring its query.
					val useStrictGuard = queryInsensitiveEvidence >= QUERY_INSENSITIVE_EVIDENCE_REQUIRED &&
						literalRatio < MIN_LITERAL_MATCH_RATIO
					val candidates = if (useStrictGuard) literalMatches else raw
					if (candidates.isEmpty()) return@repeat

					val uniqueItems = candidates
						.asSequence()
						.filter { it.id !in seen && it.id !in excludedIds }
						.take(MAX_ITEMS_PER_GROUP)
						.toList()

					val items = ArrayList<Manga>(MAX_ITEMS_PER_GROUP)
					items += uniqueItems
					if (items.size < MIN_ITEMS_BEFORE_OVERLAP && items.size < candidates.size) {
						val alreadyIncluded = items.mapTo(HashSet<Long>()) { it.id }
						candidates.asSequence()
							.filter { it.id !in alreadyIncluded && it.id !in excludedIds }
							.take(minOf(MIN_ITEMS_BEFORE_OVERLAP - items.size, MAX_ITEMS_PER_GROUP - items.size))
							.forEach { manga ->
								items += manga
								alreadyIncluded += manga.id
							}
					}

					if (items.isNotEmpty()) {
						items.forEach { seen += it.id }
						emit(RelatedMangaGroup(keyword = task.keyword, manga = items))
					}
				}
			}
		}
	}

	private suspend fun loadPrimary(seed: Manga): List<Manga> {
		val repository = mangaRepositoryFactory.create(seed.source)
		val related = getRelatedSafely(repository, seed)
		if (related.isNotEmpty() || seed.source == LocalMangaSource) {
			return related
		}

		// Keep the initial Details preview cheap: only one bounded fallback query is allowed here.
		val keyword = buildRelatedKeywords(seed).firstOrNull() ?: return emptyList()
		return searchKeyword(repository, keyword)
			.asSequence()
			.filterNot { it.id == seed.id }
			.filter { it.matchesKeyword(keyword) }
			.distinctBy { it.id }
			.take(MAX_ITEMS_PER_GROUP)
			.toList()
	}

	/**
	 * CachingMangaRepository intentionally runs its underlying Related fetch in processLifecycleScope.
	 * Once that fetch has started, cancelling the screen only cancels the await, not the source work.
	 * Keep the permit until that process-scoped work resolves so a cancelled screen cannot create an
	 * uncounted native request beside two newer keyword requests.
	 */
	private suspend fun getRelatedSafely(repository: MangaRepository, seed: Manga): List<Manga> =
		networkLimiter.withPermit {
			withContext(NonCancellable) {
				runCatchingCancellable { repository.getRelated(seed) }
					.onFailure { it.printStackTraceDebug() }
					.getOrNull()
					.orEmpty()
			}
		}

	private suspend fun searchKeyword(repository: MangaRepository, keyword: String): List<Manga> {
		val key = SearchCacheKey(repository.source.name, keyword.lowercase())
		keywordRequestMutex.lock(key)
		return try {
			// Re-check after acquiring the per-key lock. Another screen may have filled the cache while
			// this caller was waiting, so identical preview/expanded searches collapse into one request.
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
			val result = networkLimiter.withPermit {
				withTimeoutOrNull(RELATED_REQUEST_TIMEOUT_MS) {
					runCatchingCancellable {
						repository.getList(
							offset = 0,
							order = order,
							filter = MangaListFilter(query = keyword),
						)
					}.onFailure {
						it.printStackTraceDebug()
					}.getOrNull()
				}
			}

			// Cache legitimate empty search results, but never turn a timeout/failure into a 10-minute
			// negative cache entry. A temporary source problem should be retryable immediately.
			if (result != null) {
				searchCacheMutex.withLock {
					keywordSearchCache[key] = SearchCacheEntry(System.currentTimeMillis(), result)
				}
			}
			result.orEmpty()
		} finally {
			keywordRequestMutex.unlock(key)
		}
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

	private fun List<Manga>.toFingerprint(): ResultFingerprint = ResultFingerprint(
		ids = asSequence().take(FINGERPRINT_SIZE).map { it.id }.toList(),
	)

	private fun ResultFingerprint.isNearDuplicateOf(other: ResultFingerprint): Boolean {
		val base = minOf(ids.size, other.ids.size)
		if (base < MIN_FINGERPRINT_SIZE) return false
		val otherSet = other.ids.toHashSet()
		val overlap = ids.count { it in otherSet }.toDouble() / base
		val positional = (0 until base).count { ids[it] == other.ids[it] }.toDouble() / base
		return overlap >= QUERY_INSENSITIVE_OVERLAP && positional >= QUERY_INSENSITIVE_POSITIONAL_MATCH
	}

	private fun normalizeForMatch(value: String): String = value
		.lowercase()
		.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
		.replace(Regex("\\s+"), " ")
		.trim()

	private sealed interface CompletedRelatedTask {
		data class Primary(val manga: List<Manga>) : CompletedRelatedTask
		data class Keyword(
			val keyword: String,
			val manga: List<Manga>,
		) : CompletedRelatedTask
	}

	private data class RankedKeyword(
		val value: String,
		val score: Int,
		val position: Int,
	)

	private data class ResultFingerprint(
		val ids: List<Long>,
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
		const val MAX_PARALLEL_NETWORK_OPERATIONS = 2
		const val MAX_ITEMS_PER_GROUP = 12
		const val MAX_RAW_RESULTS_PER_KEYWORD = 24
		const val MIN_ITEMS_BEFORE_OVERLAP = 4
		const val FINGERPRINT_SIZE = 8
		const val MIN_FINGERPRINT_SIZE = 4
		const val QUERY_INSENSITIVE_OVERLAP = 0.90
		const val QUERY_INSENSITIVE_POSITIONAL_MATCH = 0.60
		const val QUERY_INSENSITIVE_EVIDENCE_REQUIRED = 2
		const val MIN_LITERAL_MATCH_RATIO = 0.25
		const val KEYWORD_CACHE_CAPACITY = 36
		const val KEYWORD_CACHE_TTL_MS = 10 * 60 * 1000L
		const val RELATED_REQUEST_TIMEOUT_MS = 10_000L
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
