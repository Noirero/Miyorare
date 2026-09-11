package org.koitharu.kotatsu.details.domain

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
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
import org.koitharu.kotatsu.BuildConfig
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

	private val networkLimiter = Semaphore(MAX_PARALLEL_NETWORK_OPERATIONS)
	private val nativeRequestLocks = StripedKeyedMutex<NativeRequestKey>(REQUEST_LOCK_STRIPES)
	private val keywordRequestLocks = StripedKeyedMutex<SearchCacheKey>(REQUEST_LOCK_STRIPES)
	private val previewLoadLocks = StripedKeyedMutex<PreviewCacheKey>(REQUEST_LOCK_STRIPES)
	private val sourceSerialLocks = StripedKeyedMutex<String>(SOURCE_LOCK_STRIPES)
	private val searchCacheMutex = Mutex()
	private val sourceProfileMutex = Mutex()
	private val previewCacheMutex = Mutex()

	private val keywordSearchCache = object : LinkedHashMap<SearchCacheKey, SearchCacheEntry>(
		KEYWORD_CACHE_CAPACITY,
		0.75f,
		true,
	) {
		override fun removeEldestEntry(
			eldest: MutableMap.MutableEntry<SearchCacheKey, SearchCacheEntry>?,
		): Boolean = size > KEYWORD_CACHE_CAPACITY
	}

	private val previewCache = object : LinkedHashMap<PreviewCacheKey, PreviewEntry>(
		PREVIEW_CACHE_CAPACITY,
		0.75f,
		true,
	) {
		override fun removeEldestEntry(
			eldest: MutableMap.MutableEntry<PreviewCacheKey, PreviewEntry>?,
		): Boolean = size > PREVIEW_CACHE_CAPACITY
	}

	private val sourceProfiles = object : LinkedHashMap<String, SourceProfile>(
		SOURCE_PROFILE_CAPACITY,
		0.75f,
		true,
	) {
		override fun removeEldestEntry(
			eldest: MutableMap.MutableEntry<String, SourceProfile>?,
		): Boolean = size > SOURCE_PROFILE_CAPACITY
	}

	suspend operator fun invoke(seed: Manga) = runCatchingCancellable {
		getOrLoadPreview(seed).manga
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrNull()

	suspend fun getGroups(seed: Manga): List<RelatedMangaGroup> {
		val result = ArrayList<RelatedMangaGroup>()
		collectGroups(seed) { group -> result += group }
		return result
	}

	suspend fun collectGroups(
		seed: Manga,
		includePrimary: Boolean = true,
		excludedIds: Set<Long> = emptySet(),
		emit: suspend (RelatedMangaGroup) -> Unit,
	) = coroutineScope {
		if (seed.source == LocalMangaSource) return@coroutineScope
		val repository = mangaRepositoryFactory.create(seed.source)
		val keywords = buildRelatedKeywords(seed)
		val taskCount = keywords.size + if (includePrimary) 1 else 0
		if (taskCount == 0) return@coroutineScope

		val seedKey = seed.canonicalKey()
		// Inline discovery must know exactly what the preview contains before it starts emitting.
		// If the preview is still loading, this shares/coalesces that load rather than racing it.
		val previewIdentities = if (includePrimary) emptySet() else getOrLoadPreview(seed).identities
		val hardExcludedKeys = buildSet {
			add(seedKey)
			addAll(previewIdentities)
		}
		val completed = Channel<CompletedRelatedTask>(capacity = taskCount)
		if (includePrimary) {
			launch {
				completed.send(CompletedRelatedTask.Primary(getRelatedSafely(repository, seed)))
			}
		}
		keywords.forEach { keyword ->
			launch {
				val outcome = searchKeywordOutcome(repository, keyword)
				completed.send(
					CompletedRelatedTask.Keyword(
						keyword = keyword,
						manga = outcome.manga,
						failed = outcome.failed,
					),
				)
			}
		}

		val seen = HashSet<CanonicalMangaKey>(hardExcludedKeys)
		val keywordFingerprints = ArrayList<ResultFingerprint>(keywords.size)
		var queryInsensitiveEvidence = 0
		var failedKeywordSearches = 0
		var groupsEmitted = 0
		var canonicalDuplicatesDropped = 0
		var strictGuardDrops = 0

		repeat(taskCount) {
			when (val task = completed.receive()) {
				is CompletedRelatedTask.Primary -> {
					val filtered = task.manga
						.asSequence()
						.filterNot {
							it.id == seed.id || it.id in excludedIds || it.canonicalKey() in hardExcludedKeys
						}
						.toList()
					val distinct = filtered.distinctBy { it.canonicalKey() }
					canonicalDuplicatesDropped += filtered.size - distinct.size
					val primary = distinct.take(MAX_ITEMS_PER_GROUP)
					if (primary.isNotEmpty()) {
						primary.forEach { seen += it.canonicalKey() }
						groupsEmitted++
						emit(RelatedMangaGroup(keyword = null, manga = primary))
					}
				}

				is CompletedRelatedTask.Keyword -> {
					if (task.failed) {
						failedKeywordSearches++
						return@repeat
					}

					val filtered = task.manga
						.asSequence()
						.filterNot { it.id == seed.id || it.canonicalKey() == seedKey }
						.take(MAX_RAW_RESULTS_PER_KEYWORD)
						.toList()
					val raw = filtered.distinctBy { it.canonicalKey() }
					canonicalDuplicatesDropped += filtered.size - raw.size
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

					val useStrictGuard = queryInsensitiveEvidence >= QUERY_INSENSITIVE_EVIDENCE_REQUIRED &&
						literalRatio < MIN_LITERAL_MATCH_RATIO
					val candidates = if (useStrictGuard) literalMatches else raw
					if (useStrictGuard) strictGuardDrops += raw.size - literalMatches.size
					if (candidates.isEmpty()) return@repeat

					val uniqueItems = candidates
						.asSequence()
						.filter { it.id !in excludedIds && it.canonicalKey() !in seen }
						.take(MAX_ITEMS_PER_GROUP)
						.toList()

					val items = ArrayList<Manga>(MAX_ITEMS_PER_GROUP)
					items += uniqueItems
					if (items.size < MIN_ITEMS_BEFORE_OVERLAP && items.size < candidates.size) {
						val alreadyIncluded = items.mapTo(HashSet<CanonicalMangaKey>()) { it.canonicalKey() }
						candidates.asSequence()
							.filter {
								it.id !in excludedIds &&
								it.canonicalKey() !in hardExcludedKeys &&
								it.canonicalKey() !in alreadyIncluded
							}
							.take(minOf(MIN_ITEMS_BEFORE_OVERLAP - items.size, MAX_ITEMS_PER_GROUP - items.size))
							.forEach { manga ->
								items += manga
								alreadyIncluded += manga.canonicalKey()
							}
					}

					if (items.isNotEmpty()) {
						items.forEach { seen += it.canonicalKey() }
						groupsEmitted++
						emit(RelatedMangaGroup(keyword = task.keyword, manga = items))
					}
				}
			}
		}

		logDebugSnapshot(
			sourceName = repository.source.name,
			keywords = keywords,
			groupsEmitted = groupsEmitted,
			canonicalDuplicatesDropped = canonicalDuplicatesDropped,
			strictGuardDrops = strictGuardDrops,
			failedKeywordSearches = failedKeywordSearches,
		)

		if (failedKeywordSearches > 0) {
			throw RelatedDiscoveryException(failedKeywordSearches)
		}
	}

	private suspend fun getOrLoadPreview(seed: Manga): PreviewEntry {
		if (seed.source == LocalMangaSource) return PreviewEntry(System.currentTimeMillis(), emptyList(), emptySet())
		val key = PreviewCacheKey(seed.source.name, seed.id)
		return previewLoadLocks.withLock(key) {
			getCachedPreview(key)?.let { return@withLock it }
			val manga = loadPrimary(seed)
			val entry = PreviewEntry(
				cachedAt = System.currentTimeMillis(),
				manga = manga,
				identities = manga.asSequence().mapTo(LinkedHashSet()) { it.canonicalKey() },
			)
			// Do not persist an empty preview. A legitimate empty search is already cached by the
			// repository/keyword layer, while a timeout or temporary failure must remain immediately retryable.
			if (manga.isNotEmpty()) {
				previewCacheMutex.withLock {
					previewCache[key] = entry
				}
			}
			entry
		}
	}

	private suspend fun getCachedPreview(key: PreviewCacheKey): PreviewEntry? {
		val now = System.currentTimeMillis()
		return previewCacheMutex.withLock {
			val entry = previewCache[key]
			if (entry != null && now - entry.cachedAt < PREVIEW_CACHE_TTL_MS) {
				entry
			} else {
				if (entry != null) previewCache.remove(key)
				null
			}
		}
	}

	private suspend fun loadPrimary(seed: Manga): List<Manga> {
		if (seed.source == LocalMangaSource) return emptyList()
		val repository = mangaRepositoryFactory.create(seed.source)
		val seedKey = seed.canonicalKey()
		val related = getRelatedSafely(repository, seed)
			.asSequence()
			.filterNot { it.id == seed.id || it.canonicalKey() == seedKey }
			.distinctBy { it.canonicalKey() }
			.take(MAX_ITEMS_PER_GROUP)
			.toList()
		if (related.isNotEmpty()) return related

		val keyword = buildRelatedKeywords(seed).firstOrNull() ?: return emptyList()
		return searchKeyword(repository, keyword)
			.asSequence()
			.filterNot { it.id == seed.id || it.canonicalKey() == seedKey }
			.filter { it.matchesKeyword(keyword) }
			.distinctBy { it.canonicalKey() }
			.take(MAX_ITEMS_PER_GROUP)
			.toList()
	}

	private suspend fun getRelatedSafely(repository: MangaRepository, seed: Manga): List<Manga> {
		val key = NativeRequestKey(repository.source.name, seed.url)
		return nativeRequestLocks.withLock(key) {
			val outcome = executeAdaptiveNetwork(repository.source.name) {
				withContext(NonCancellable) {
					val result = runCatchingCancellable { repository.getRelated(seed) }
						.onFailure { it.printStackTraceDebug() }
						.getOrNull()
					NetworkOutcome(
						value = result,
						failed = result == null,
						timedOut = false,
					)
				}
			}
			outcome.value.orEmpty()
		}
	}

	private suspend fun searchKeyword(repository: MangaRepository, keyword: String): List<Manga> =
		searchKeywordOutcome(repository, keyword).manga

	private suspend fun searchKeywordOutcome(
		repository: MangaRepository,
		keyword: String,
	): KeywordSearchOutcome {
		val sourceName = repository.source.name
		val key = SearchCacheKey(sourceName, keyword.lowercase())
		return keywordRequestLocks.withLock(key) {
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
			if (cached != null) {
				recordCache(sourceName, hit = true)
				return@withLock KeywordSearchOutcome(cached, failed = false)
			}
			recordCache(sourceName, hit = false)

			val order = SortOrder.RELEVANCE.takeIf { it in repository.sortOrders } ?: repository.defaultSortOrder
			val outcome = executeAdaptiveNetwork(sourceName) {
				val completed = withTimeoutOrNull(RELATED_REQUEST_TIMEOUT_MS) {
					val result = runCatchingCancellable {
						repository.getList(
							offset = 0,
							order = order,
							filter = MangaListFilter(query = keyword),
						)
					}.onFailure {
						it.printStackTraceDebug()
					}
					NetworkOutcome(
						value = result.getOrNull(),
						failed = result.isFailure,
						timedOut = false,
					)
				}
				completed ?: NetworkOutcome(value = null, failed = true, timedOut = true)
			}

			val result = outcome.value
			if (result != null) {
				searchCacheMutex.withLock {
					keywordSearchCache[key] = SearchCacheEntry(System.currentTimeMillis(), result)
				}
				KeywordSearchOutcome(result, failed = false)
			} else {
				KeywordSearchOutcome(emptyList(), failed = true)
			}
		}
	}

	private suspend fun <T> executeAdaptiveNetwork(
		sourceName: String,
		block: suspend () -> NetworkOutcome<T>,
	): NetworkOutcome<T> {
		val serialize = sourceProfileMutex.withLock {
			sourceProfiles[sourceName]?.shouldSerialize(System.currentTimeMillis()) == true
		}
		val execute: suspend () -> NetworkOutcome<T> = {
			networkLimiter.withPermit {
				val startedAt = SystemClock.elapsedRealtime()
				try {
					val outcome = block()
					recordNetworkResult(
						sourceName = sourceName,
						durationMs = SystemClock.elapsedRealtime() - startedAt,
						failed = outcome.failed,
						timedOut = outcome.timedOut,
					)
					outcome
				} catch (e: CancellationException) {
					throw e
				}
			}
		}
		return if (serialize) {
			sourceSerialLocks.withLock(sourceName) { execute() }
		} else {
			execute()
		}
	}

	private suspend fun recordNetworkResult(
		sourceName: String,
		durationMs: Long,
		failed: Boolean,
		timedOut: Boolean,
	) {
		val now = System.currentTimeMillis()
		sourceProfileMutex.withLock {
			val profile = sourceProfiles.getOrPut(sourceName) { SourceProfile() }
			profile.samples++
			profile.networkRequests++
			profile.averageLatencyMs = if (profile.samples == 1) {
				durationMs.toDouble()
			} else {
				profile.averageLatencyMs * LATENCY_EMA_OLD_WEIGHT + durationMs * LATENCY_EMA_NEW_WEIGHT
			}
			if (failed) {
				profile.failures++
				profile.consecutiveFailures++
			} else {
				profile.consecutiveFailures = 0
			}
			if (timedOut) profile.timeouts++
			if (timedOut || profile.consecutiveFailures >= FAILURES_BEFORE_SERIALIZE) {
				profile.serializeUntil = maxOf(profile.serializeUntil, now + SLOW_SOURCE_SERIALIZE_MS)
			}
		}
	}

	private suspend fun recordCache(sourceName: String, hit: Boolean) {
		sourceProfileMutex.withLock {
			val profile = sourceProfiles.getOrPut(sourceName) { SourceProfile() }
			if (hit) profile.cacheHits++ else profile.cacheMisses++
		}
	}

	private suspend fun logDebugSnapshot(
		sourceName: String,
		keywords: List<String>,
		groupsEmitted: Int,
		canonicalDuplicatesDropped: Int,
		strictGuardDrops: Int,
		failedKeywordSearches: Int,
	) {
		if (!BuildConfig.DEBUG) return
		val now = System.currentTimeMillis()
		val snapshot = sourceProfileMutex.withLock {
			sourceProfiles[sourceName]?.let { profile ->
				SourceProfileSnapshot(
					averageLatencyMs = profile.averageLatencyMs.toLong(),
					networkRequests = profile.networkRequests,
					cacheHits = profile.cacheHits,
					cacheMisses = profile.cacheMisses,
					failures = profile.failures,
					timeouts = profile.timeouts,
					serialized = profile.shouldSerialize(now),
				)
			}
		}
		Log.d(
			TAG,
			"source=$sourceName keywords=${keywords.joinToString("|")} groups=$groupsEmitted " +
				"canonicalDrops=$canonicalDuplicatesDropped strictDrops=$strictGuardDrops " +
				"failedKeywords=$failedKeywordSearches profile=$snapshot",
		)
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
					if (term.length > 2 && term.any { it.isLetter() } && normalized !in RELATED_STOP_WORDS) {
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

		val limit = if (unique.size <= BASE_RELATED_KEYWORDS) {
			BASE_RELATED_KEYWORDS
		} else {
			minOf(unique.size, MAX_RELATED_KEYWORDS)
		}
		return unique.values
			.sortedWith(compareByDescending<RankedKeyword> { it.score }.thenBy { it.position })
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

	private fun Manga.canonicalKey(): CanonicalMangaKey {
		val localUrl = url.trim().trimEnd('/')
		val webUrl = publicUrl.trim().trimEnd('/')
		val identity = when {
			localUrl.isNotEmpty() -> "url:$localUrl"
			webUrl.isNotEmpty() -> "public:$webUrl"
			else -> "title:${normalizeForMatch(title)}"
		}
		return CanonicalMangaKey(source.name, identity)
	}

	private fun List<Manga>.toFingerprint(): ResultFingerprint = ResultFingerprint(
		keys = asSequence()
			.take(FINGERPRINT_SIZE)
			.map { manga -> manga.canonicalKey().let { "${it.sourceName}|${it.identity}" } }
			.toList(),
	)

	private fun ResultFingerprint.isNearDuplicateOf(other: ResultFingerprint): Boolean {
		val base = minOf(keys.size, other.keys.size)
		if (base < MIN_FINGERPRINT_SIZE) return false
		val otherSet = other.keys.toHashSet()
		val overlap = keys.count { it in otherSet }.toDouble() / base
		val positional = (0 until base).count { keys[it] == other.keys[it] }.toDouble() / base
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
			val failed: Boolean,
		) : CompletedRelatedTask
	}

	private data class KeywordSearchOutcome(
		val manga: List<Manga>,
		val failed: Boolean,
	)

	private data class NetworkOutcome<T>(
		val value: T?,
		val failed: Boolean,
		val timedOut: Boolean,
	)

	private class RelatedDiscoveryException(failedRequests: Int) : IllegalStateException(
		"$failedRequests related search request(s) failed",
	)

	private data class RankedKeyword(
		val value: String,
		val score: Int,
		val position: Int,
	)

	private data class ResultFingerprint(
		val keys: List<String>,
	)

	private data class CanonicalMangaKey(
		val sourceName: String,
		val identity: String,
	)

	private data class NativeRequestKey(
		val sourceName: String,
		val seedUrl: String,
	)

	private data class PreviewCacheKey(
		val sourceName: String,
		val seedId: Long,
	)

	private data class SearchCacheKey(
		val sourceName: String,
		val keyword: String,
	)

	private data class SearchCacheEntry(
		val cachedAt: Long,
		val manga: List<Manga>,
	)

	private data class PreviewEntry(
		val cachedAt: Long,
		val manga: List<Manga>,
		val identities: Set<CanonicalMangaKey>,
	)

	private data class SourceProfile(
		var samples: Int = 0,
		var networkRequests: Int = 0,
		var averageLatencyMs: Double = 0.0,
		var consecutiveFailures: Int = 0,
		var failures: Int = 0,
		var timeouts: Int = 0,
		var cacheHits: Int = 0,
		var cacheMisses: Int = 0,
		var serializeUntil: Long = 0L,
	) {
		fun shouldSerialize(now: Long): Boolean =
			serializeUntil > now ||
				consecutiveFailures >= FAILURES_BEFORE_SERIALIZE ||
				(samples >= SLOW_SOURCE_MIN_SAMPLES && averageLatencyMs >= SLOW_SOURCE_LATENCY_MS)
	}

	private data class SourceProfileSnapshot(
		val averageLatencyMs: Long,
		val networkRequests: Int,
		val cacheHits: Int,
		val cacheMisses: Int,
		val failures: Int,
		val timeouts: Int,
		val serialized: Boolean,
	)

	private class StripedKeyedMutex<K : Any>(stripeCount: Int) {
		private val stripes = Array(stripeCount.coerceAtLeast(1)) { Mutex() }

		suspend fun <T> withLock(key: K, block: suspend () -> T): T {
			val mutex = stripes[(key.hashCode() and Int.MAX_VALUE) % stripes.size]
			mutex.lock()
			return try {
				block()
			} finally {
				mutex.unlock()
			}
		}
	}

	private companion object {
		const val TAG = "RelatedManga"
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
		const val PREVIEW_CACHE_CAPACITY = 24
		const val PREVIEW_CACHE_TTL_MS = 10 * 60 * 1000L
		const val RELATED_REQUEST_TIMEOUT_MS = 10_000L
		const val REQUEST_LOCK_STRIPES = 32
		const val SOURCE_LOCK_STRIPES = 16
		const val SOURCE_PROFILE_CAPACITY = 24
		const val SLOW_SOURCE_MIN_SAMPLES = 3
		const val SLOW_SOURCE_LATENCY_MS = 4_500.0
		const val SLOW_SOURCE_SERIALIZE_MS = 5 * 60 * 1000L
		const val FAILURES_BEFORE_SERIALIZE = 2
		const val LATENCY_EMA_OLD_WEIGHT = 0.70
		const val LATENCY_EMA_NEW_WEIGHT = 0.30
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
