package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

	/**
	 * Builds the expanded "More like these" view only when the user explicitly opens it.
	 * The Details screen keeps using [invoke], so opening Details never fans out several searches.
	 */
	suspend fun getGroups(seed: Manga): List<RelatedMangaGroup> = coroutineScope {
		val repository = mangaRepositoryFactory.create(seed.source)
		// Expanded Related already launches bounded keyword searches below. Ask the source only for its
		// native related feed here; calling loadPrimary() would perform the first fallback keyword search
		// a second time before the shared cache has a chance to fill.
		val primaryDeferred = async {
			runCatchingCancellable { repository.getRelated(seed) }.getOrDefault(emptyList())
		}

		if (seed.source == LocalMangaSource) {
			val primary = primaryDeferred.await()
				.filterNot { it.id == seed.id }
				.distinctBy { it.id }
				.take(MAX_ITEMS_PER_GROUP)
			return@coroutineScope if (primary.isEmpty()) {
				emptyList()
			} else {
				listOf(RelatedMangaGroup(keyword = null, manga = primary))
			}
		}

		val keywords = buildRelatedKeywords(seed)
		val limiter = Semaphore(MAX_PARALLEL_SEARCHES)
		val keywordDeferred = keywords.map { keyword ->
			async {
				limiter.withPermit {
					keyword to searchKeyword(repository, keyword)
				}
			}
		}

		val groups = ArrayList<RelatedMangaGroup>(keywords.size + 1)
		val seen = HashSet<Long>()

		val primary = primaryDeferred.await()
			.asSequence()
			.filterNot { it.id == seed.id }
			.distinctBy { it.id }
			.take(MAX_ITEMS_PER_GROUP)
			.toList()
		if (primary.isNotEmpty()) {
			primary.forEach { seen += it.id }
			groups += RelatedMangaGroup(keyword = null, manga = primary)
		}

		for ((keyword, raw) in keywordDeferred.awaitAll()) {
			val items = raw
				.asSequence()
				.filterNot { it.id == seed.id }
				.filter { it.matchesKeyword(keyword) }
				.distinctBy { it.id }
				.filter { it.id !in seen }
				.take(MAX_ITEMS_PER_GROUP)
				.toList()
			if (items.isNotEmpty()) {
				items.forEach { seen += it.id }
				groups += RelatedMangaGroup(keyword = keyword, manga = items)
			}
		}
		groups
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
		val unique = LinkedHashMap<String, String>()
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
						unique.putIfAbsent(normalized, term)
					}
				}
			}
		return unique.values
			.sortedWith(
				compareByDescending<String> { keywordScore(it) }
					.thenByDescending { it.length },
			)
			.take(MAX_RELATED_KEYWORDS)
	}

	private fun keywordScore(keyword: String): Int =
		(if ('-' in keyword) HYPHENATED_KEYWORD_BONUS else 0) +
			keyword.count { it.isLetterOrDigit() }

	private fun Manga.matchesKeyword(keyword: String): Boolean {
		val needle = normalizeForMatch(keyword)
		if (needle.isEmpty()) return false
		return sequenceOf(title)
			.plus(altTitles.asSequence())
			.any { normalizeForMatch(it).contains(needle) }
	}

	private fun normalizeForMatch(value: String): String = value
		.lowercase()
		.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
		.replace(Regex("\\s+"), " ")
		.trim()

	private data class SearchCacheKey(
		val sourceName: String,
		val keyword: String,
	)

	private data class SearchCacheEntry(
		val cachedAt: Long,
		val manga: List<Manga>,
	)

	private companion object {
		const val MAX_RELATED_KEYWORDS = 4
		const val MAX_PARALLEL_SEARCHES = 2
		const val MAX_ITEMS_PER_GROUP = 12
		const val KEYWORD_CACHE_CAPACITY = 24
		const val KEYWORD_CACHE_TTL_MS = 10 * 60 * 1000L
		const val HYPHENATED_KEYWORD_BONUS = 100

		val RELATED_TERM_REGEX = Regex("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)+|[\\p{L}\\p{N}]+")
		val RELATED_STOP_WORDS = setOf(
			"a", "an", "and", "are", "as", "at", "after", "before", "by", "for", "from",
			"he", "her", "his", "i", "in", "into", "is", "it", "its", "me", "my", "of",
			"on", "our", "she", "that", "the", "their", "them", "they", "this", "to", "we",
			"we're", "with", "you", "your", "manga", "manhwa", "manhua", "comic", "comics",
			"chapter", "chapters", "volume", "vol",
		)
	}
}
