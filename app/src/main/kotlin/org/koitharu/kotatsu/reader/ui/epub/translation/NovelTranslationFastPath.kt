package org.koitharu.kotatsu.reader.ui.epub.translation

import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mutable operation-local visibility hint. Reader updates this as the user moves so queued work can
 * be reprioritized without restarting or duplicating already completed translation work.
 */
class NovelTranslationPriorityController(initialVisibleRange: IntRange? = null) {
	@Volatile
	var visibleRange: IntRange? = initialVisibleRange
		private set

	fun update(range: IntRange?) {
		visibleRange = range
	}
}

internal object NovelTranslationPrioritizer {
	fun score(segment: NovelTranslationSegment, visibleRange: IntRange?): Long {
		val visible = visibleRange ?: return segment.start.toLong()
		val visibleStart = visible.first.coerceAtLeast(0)
		val visibleEndExclusive = (visible.last + 1).coerceAtLeast(visibleStart)
		if (segment.start < visibleEndExclusive && segment.end > visibleStart) return 0L
		return if (segment.start >= visibleEndExclusive) {
			// Ahead of the viewport is preferred over equally distant already-read text.
			1L + (segment.start - visibleEndExclusive).toLong() * 2L
		} else {
			2L + (visibleStart - segment.end).coerceAtLeast(0).toLong() * 2L
		}
	}
}

/** Queue whose next item is chosen using the latest visible range, enabling live reprioritization. */
internal class NovelTranslationPriorityQueue<T>(
	items: Collection<T>,
	private val segmentOf: (T) -> NovelTranslationSegment,
	private val priorityController: NovelTranslationPriorityController?,
) {
	private val mutex = Mutex()
	private val pending = items.toMutableList()

	suspend fun takeBatch(maxItems: Int, maxChars: Int, charCount: (T) -> Int): List<T> = mutex.withLock {
		if (pending.isEmpty()) return@withLock emptyList()
		val visible = priorityController?.visibleRange
		pending.sortWith(
			compareBy<T> { NovelTranslationPrioritizer.score(segmentOf(it), visible) }
				.thenBy { segmentOf(it).start },
		)
		val result = ArrayList<T>(maxItems.coerceAtLeast(1))
		var chars = 0
		val itemLimit = maxItems.coerceAtLeast(1)
		val charLimit = maxChars.coerceAtLeast(1)
		val iterator = pending.iterator()
		while (iterator.hasNext() && result.size < itemLimit) {
			val item = iterator.next()
			val nextChars = charCount(item).coerceAtLeast(0)
			if (result.isNotEmpty() && chars + nextChars > charLimit) continue
			result += item
			chars += nextChars
			iterator.remove()
			if (chars >= charLimit) break
		}
		if (result.isEmpty()) {
			// A single oversized item still has to make progress; provider single-request limits are
			// enforced by the segmenter before items enter this queue.
			result += pending.removeAt(0)
		}
		result
	}
}

data class NovelTranslationSchedulingPolicy(
	val maxConcurrency: Int,
	val minRequestGapMillis: Long,
	val pressureLevel: Int,
)

/**
 * Small deterministic provider-pressure controller. It becomes conservative after transient
 * failures/rate limits and gradually returns to the provider's configured fast path after healthy
 * responses. Circuit-breaking remains a separate, stronger safety layer.
 */
class NovelTranslationProviderHealthController {
	private data class State(var pressureLevel: Int = 0, var successStreak: Int = 0)
	private val states = ConcurrentHashMap<String, State>()

	fun policy(capabilities: NovelTranslationProviderCapabilities): NovelTranslationSchedulingPolicy {
		val state = states[capabilities.providerId]
		val level = state?.pressureLevel ?: 0
		val baseConcurrency = capabilities.maxConcurrency.coerceAtLeast(1)
		val baseGap = capabilities.minRequestGapMillis.coerceAtLeast(0L)
		return when (level) {
			0 -> NovelTranslationSchedulingPolicy(baseConcurrency, baseGap, 0)
			1 -> NovelTranslationSchedulingPolicy(
				maxConcurrency = ((baseConcurrency + 1) / 2).coerceAtLeast(1),
				minRequestGapMillis = maxOf(baseGap * 2L, 200L),
				pressureLevel = 1,
			)
			else -> NovelTranslationSchedulingPolicy(
				maxConcurrency = 1,
				minRequestGapMillis = maxOf(baseGap * 4L, 750L),
				pressureLevel = 2,
			)
		}
	}

	fun onSuccess(providerId: String): Boolean {
		val state = states[providerId] ?: return false
		synchronized(state) {
			if (state.pressureLevel <= 0) return false
			state.successStreak++
			if (state.successStreak < HEALTHY_RESPONSES_TO_RECOVER) return false
			state.successStreak = 0
			state.pressureLevel--
			if (state.pressureLevel == 0) states.remove(providerId, state)
			return true
		}
	}

	fun onFailure(providerId: String, failure: NovelTranslationFailure): Boolean {
		val targetLevel = when (failure.category) {
			NovelTranslationFailureCategory.RATE_LIMITED -> 2
			NovelTranslationFailureCategory.NETWORK,
			NovelTranslationFailureCategory.TIMEOUT,
			NovelTranslationFailureCategory.PROVIDER_UNAVAILABLE -> 1
			else -> return false
		}
		val state = states.getOrPut(providerId) { State() }
		synchronized(state) {
			val before = state.pressureLevel
			state.successStreak = 0
			state.pressureLevel = if (targetLevel == 2) 2 else (state.pressureLevel + 1).coerceAtMost(2)
			return state.pressureLevel != before
		}
	}

	internal fun pressureLevel(providerId: String): Int = states[providerId]?.pressureLevel ?: 0

	private companion object {
		private const val HEALTHY_RESPONSES_TO_RECOVER = 3
	}
}

/** Small process-local LRU in front of the durable semantic translation cache. */
class NovelTranslationMemoryCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
	private data class Entry(val value: String, val backingStamp: String?)

	private val entries = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
			size > maxEntries.coerceAtLeast(1)
	}

	@Volatile
	private var backingCache: NovelTranslationCacheCoherence? = null

	internal fun bindBackingCache(cache: NovelTranslationResultCache) {
		backingCache = cache as? NovelTranslationCacheCoherence
	}

	fun read(key: NovelTranslationSemanticKey): String? = synchronized(entries) {
		val entry = entries[key.digest] ?: return@synchronized null
		if (!isCurrent(key, entry)) {
			entries.remove(key.digest)
			return@synchronized null
		}
		entry.value
	}

	fun readMany(keys: Collection<NovelTranslationSemanticKey>): Map<NovelTranslationSemanticKey, String> =
		synchronized(entries) {
			buildMap {
				keys.forEach { key ->
					val entry = this@NovelTranslationMemoryCache.entries[key.digest] ?: return@forEach
					if (isCurrent(key, entry)) {
						put(key, entry.value)
					} else {
						this@NovelTranslationMemoryCache.entries.remove(key.digest)
					}
				}
			}
		}

	fun write(key: NovelTranslationSemanticKey, value: String) = synchronized(entries) {
		entries[key.digest] = Entry(value, backingCache?.backingStamp(key))
	}

	fun writeMany(values: Map<NovelTranslationSemanticKey, String>) = synchronized(entries) {
		values.forEach { (key, value) -> entries[key.digest] = Entry(value, backingCache?.backingStamp(key)) }
	}

	internal fun clear() = synchronized(entries) { entries.clear() }

	private fun isCurrent(key: NovelTranslationSemanticKey, entry: Entry): Boolean {
		val cache = backingCache ?: return true
		return cache.backingStamp(key) == entry.backingStamp
	}

	private companion object {
		private const val DEFAULT_MAX_ENTRIES = 512
	}
}

/**
 * Translation cache is derived data, so persistence is deliberately removed from the network hot
 * path. Results become immediately available through L1 and are durably flushed shortly after in a
 * bounded/coalesced background write.
 */
class NovelTranslationWriteBehindCache(
	private val persistent: NovelTranslationResultCache,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
	private val flushDelayMillis: Long = DEFAULT_FLUSH_DELAY_MILLIS,
) {
	private val lock = Any()
	private val flushMutex = Mutex()
	private val pending = LinkedHashMap<NovelTranslationSemanticKey, String>()
	private var flushJob: Job? = null
	private var consecutiveFailures = 0

	fun enqueue(key: NovelTranslationSemanticKey, value: String) {
		synchronized(lock) {
			pending[key] = value
			if (flushJob?.isActive != true) {
				// New translation activity is a safe opportunity to retry entries retained after the
				// previous bounded retry budget was exhausted.
				if (consecutiveFailures >= MAX_AUTOMATIC_FLUSH_RETRIES) consecutiveFailures = 0
				scheduleFlushLocked(flushDelayMillis)
			}
		}
	}

	internal suspend fun flushNow() = flushMutex.withLock {
		val snapshot = synchronized(lock) {
			if (pending.isEmpty()) {
				flushJob = null
				return@withLock
			}
			LinkedHashMap(pending).also {
				pending.clear()
				flushJob = null
			}
		}
		try {
			persistent.writeMany(snapshot)
			synchronized(lock) { consecutiveFailures = 0 }
		} catch (error: CancellationException) {
			requeue(snapshot, retryAutomatically = false)
			throw error
		} catch (_: Exception) {
			// Cache is derived data. Preserve the latest values without allowing a broken storage
			// backend to spin forever or surface into translation/network progression.
			requeue(snapshot, retryAutomatically = true)
		}
	}

	private fun requeue(
		snapshot: Map<NovelTranslationSemanticKey, String>,
		retryAutomatically: Boolean,
	) = synchronized(lock) {
		snapshot.forEach { (key, value) -> if (!pending.containsKey(key)) pending[key] = value }
		if (!retryAutomatically || consecutiveFailures >= MAX_AUTOMATIC_FLUSH_RETRIES) return@synchronized
		consecutiveFailures++
		val multiplier = 1L shl (consecutiveFailures - 1)
		val retryDelay = if (flushDelayMillis > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else flushDelayMillis * multiplier
		scheduleFlushLocked(retryDelay)
	}

	private fun scheduleFlushLocked(delayMillis: Long) {
		if (flushJob?.isActive == true) return
		flushJob = scope.launch {
			if (delayMillis > 0L) delay(delayMillis)
			flushNow()
		}
	}

	internal fun pendingCount(): Int = synchronized(lock) { pending.size }
	internal fun automaticRetryCount(): Int = synchronized(lock) { consecutiveFailures }

	private companion object {
		private const val DEFAULT_FLUSH_DELAY_MILLIS = 100L
		private const val MAX_AUTOMATIC_FLUSH_RETRIES = 3
	}
}