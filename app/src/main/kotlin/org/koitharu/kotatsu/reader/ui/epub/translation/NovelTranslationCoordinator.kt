package org.koitharu.kotatsu.reader.ui.epub.translation

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

internal const val MIYORARE_ONLINE_PROVIDER_ID = "miyorare-online"
internal const val MIYORARE_ONLINE_MAX_CONCURRENCY = 1
internal const val MIYORARE_ONLINE_REQUEST_GAP_MS = 750L

/** Explicit provider behavior used by the reliability/fast-path layer. */
data class NovelTranslationProviderCapabilities(
	val providerId: String,
	val supportsBatch: Boolean = false,
	val maxBatchItems: Int = 1,
	val maxBatchChars: Int,
	val maxSingleRequestChars: Int,
	val supportsAutoDetect: Boolean = true,
	val supportsProgressiveResultPublishing: Boolean = true,
	val credentialRequired: Boolean,
	val maxConcurrency: Int = 1,
	val minRequestGapMillis: Long = 0L,
	val maxAttempts: Int = 3,
	val initialBackoffMillis: Long = 750L,
	val maxRetryDelayMillis: Long = 15_000L,
)

data class NovelTranslationProvider(
	val engine: NovelTranslationEngine,
	val capabilities: NovelTranslationProviderCapabilities,
	val semanticIdentity: NovelTranslationProviderSemanticIdentity = NovelTranslationProviderSemanticIdentity(
		configurationFingerprint = novelTranslationDigest(capabilities.providerId),
		contractVersion = DEFAULT_PROVIDER_CONTRACT_VERSION,
		circuitKey = capabilities.providerId,
	),
)

fun interface NovelTranslationProviderResolver {
	fun resolve(selection: NovelTranslationSelection): NovelTranslationProvider
}

internal interface NovelTranslationReliabilityServicesProvider {
	fun reliabilityServices(): NovelTranslationReliabilityServices
}

/** Current provider resolver. Reader orchestration is provider-independent. */
class DefaultNovelTranslationProviderResolver(
	httpClient: OkHttpClient,
	private val settings: NovelTranslationSettings,
	private val secrets: NovelTranslationSecrets,
) : NovelTranslationProviderResolver, NovelTranslationReliabilityServicesProvider {

	private val reliableHttpClient = httpClient.newBuilder()
		.addInterceptor(NovelTranslationReliabilityInterceptor())
		.build()

	override fun reliabilityServices(): NovelTranslationReliabilityServices =
		NovelTranslationProcessReliability.services(settings.applicationContext)

	override fun resolve(selection: NovelTranslationSelection): NovelTranslationProvider = when (selection.engine) {
		NovelTranslationEngineKind.ONLINE -> {
			val providerId = MIYORARE_ONLINE_PROVIDER_ID
			NovelTranslationProvider(
				engine = MiyorareOnlineTranslationEngine(reliableHttpClient),
				capabilities = NovelTranslationProviderCapabilities(
					providerId = providerId,
					maxBatchChars = ONLINE_SEGMENT_CHARS,
					maxSingleRequestChars = ONLINE_SEGMENT_CHARS,
					supportsProgressiveResultPublishing = false,
					credentialRequired = false,
					maxConcurrency = MIYORARE_ONLINE_MAX_CONCURRENCY,
					minRequestGapMillis = MIYORARE_ONLINE_REQUEST_GAP_MS,
				),
				semanticIdentity = NovelTranslationProviderSemanticIdentity(
					configurationFingerprint = novelTranslationDigest(providerId, ONLINE_CONTRACT_VERSION),
					contractVersion = ONLINE_CONTRACT_VERSION,
					circuitKey = providerId,
				),
			)
		}
		NovelTranslationEngineKind.AI -> resolveAiProvider()
	}

	private fun resolveAiProvider(): NovelTranslationProvider {
		val configuration = NovelAiTranslationConfiguration.from(settings)
		val provider = configuration.provider
		val providerId = "ai:${provider.id}"
		val configurationFingerprint = novelTranslationDigest(
			provider.id,
			provider.protocol.name,
			configuration.model,
			configuration.resolvedBaseUrl,
			configuration.deeplFreeApi.toString(),
		)
		val supportsBatch = provider.protocol == NovelAiProtocol.DEEPL
		return NovelTranslationProvider(
			engine = NovelAiTranslationEngine(
				httpClient = reliableHttpClient,
				settings = settings,
				secrets = secrets,
				configuration = configuration,
			),
			capabilities = NovelTranslationProviderCapabilities(
				providerId = providerId,
				supportsBatch = supportsBatch,
				maxBatchItems = if (supportsBatch) DEEPL_MAX_BATCH_ITEMS else 1,
				maxBatchChars = if (supportsBatch) DEEPL_MAX_BATCH_CHARS else AI_SEGMENT_CHARS,
				maxSingleRequestChars = AI_SEGMENT_CHARS,
				credentialRequired = true,
				maxConcurrency = if (supportsBatch) DEEPL_MAX_CONCURRENCY else 1,
				minRequestGapMillis = if (supportsBatch) DEEPL_REQUEST_GAP_MS else AI_REQUEST_GAP_MS,
			),
			semanticIdentity = NovelTranslationProviderSemanticIdentity(
				configurationFingerprint = configurationFingerprint,
				contractVersion = AI_PROMPT_CONTRACT_VERSION,
				circuitKey = "$providerId:${configurationFingerprint.take(16)}",
			),
		)
	}

	companion object {
		private const val ONLINE_SEGMENT_CHARS = 1600
		private const val AI_SEGMENT_CHARS = 8000
		private const val AI_REQUEST_GAP_MS = 150L
		private const val DEEPL_MAX_BATCH_ITEMS = 10
		// Well below DeepL's documented 128 KiB request-body ceiling even for 4-byte UTF-8 text.
		private const val DEEPL_MAX_BATCH_CHARS = 24_000
		private const val DEEPL_MAX_CONCURRENCY = 2
		private const val DEEPL_REQUEST_GAP_MS = 75L
		private const val ONLINE_CONTRACT_VERSION = "online-v1"
		private const val AI_PROMPT_CONTRACT_VERSION = "ai-prompt-v1"
	}
}

data class NovelTranslationSegment(
	val start: Int,
	val end: Int,
	val text: String,
)

data class NovelTranslatedSegment(
	val source: NovelTranslationSegment,
	val translatedText: String,
)

data class NovelTranslationOperationRequest(
	val text: CharSequence,
	val selection: NovelTranslationSelection,
	val beforeContext: String = "",
	val afterContext: String = "",
	val priorityController: NovelTranslationPriorityController? = null,
)

data class NovelTranslationOperationResult(
	val segments: List<NovelTranslatedSegment>,
) {
	val translatedText: String by lazy(LazyThreadSafetyMode.NONE) {
		buildString { segments.forEach { append(it.translatedText) } }
	}
}

sealed interface NovelTranslationProgress {
	val completedSegments: Int
	val totalSegments: Int

	data class Translating(
		override val completedSegments: Int,
		override val totalSegments: Int,
		val segmentIndex: Int,
		val attempt: Int,
	) : NovelTranslationProgress

	data class WaitingToRetry(
		override val completedSegments: Int,
		override val totalSegments: Int,
		val segmentIndex: Int,
		val nextAttempt: Int,
		val delayMillis: Long,
		val failure: NovelTranslationFailure,
	) : NovelTranslationProgress
}

/** Deterministic, source-offset-preserving segmentation for reader translation. */
object NovelTranslationSegmenter {

	fun split(text: CharSequence, maxChars: Int): List<NovelTranslationSegment> {
		if (text.isEmpty()) return emptyList()
		val plain = text.toString()
		val limit = maxChars.coerceAtLeast(MIN_SEGMENT_CHARS)
		val result = ArrayList<NovelTranslationSegment>((plain.length / limit) + 1)
		var start = 0
		while (start < plain.length) {
			var end = min(start + limit, plain.length)
			if (end < plain.length) {
				end = preferredBreak(plain, start, end, limit)
				end = avoidSurrogateSplit(plain, start, end)
			}
			if (end <= start) {
				end = avoidSurrogateSplit(plain, start, min(start + limit, plain.length))
				if (end <= start) end = min(start + 1, plain.length)
			}
			result += NovelTranslationSegment(start, end, plain.substring(start, end))
			start = end
		}
		return result
	}

	private fun preferredBreak(text: String, start: Int, hardEnd: Int, limit: Int): Int {
		val paragraphFloor = (start + limit / 2).coerceAtMost(hardEnd)
		val fallbackFloor = (start + limit * 2 / 3).coerceAtMost(hardEnd)
		val paragraph = text.lastIndexOf("\n\n", hardEnd - 1).takeIf { it >= paragraphFloor }?.plus(2)
		if (paragraph != null) return paragraph
		val line = text.lastIndexOf('\n', hardEnd - 1).takeIf { it >= fallbackFloor }?.plus(1)
		if (line != null) return line
		for (index in hardEnd - 1 downTo fallbackFloor) {
			if (text[index] in SENTENCE_ENDINGS && (index + 1 >= text.length || text[index + 1].isWhitespace())) {
				return index + 1
			}
		}
		for (index in hardEnd - 1 downTo fallbackFloor) {
			if (text[index].isWhitespace()) return index + 1
		}
		return hardEnd
	}

	private fun avoidSurrogateSplit(text: String, start: Int, proposedEnd: Int): Int {
		var end = proposedEnd
		if (end in (start + 1) until text.length &&
			Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])
		) {
			end--
		}
		return end
	}

	private const val MIN_SEGMENT_CHARS = 256
	private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '。', '！', '？')
}

/** Provider-keyed dynamic scheduler with FIFO fairness, adaptive concurrency and adaptive pacing. */
class NovelTranslationRequestScheduler(
	private val nowMillis: () -> Long = System::currentTimeMillis,
	private val sleep: suspend (Long) -> Unit = { delay(it) },
	private val healthController: NovelTranslationProviderHealthController = NovelTranslationProviderHealthController(),
) {
	private data class Lane(
		val gateMutex: Mutex = Mutex(),
		val pacingMutex: Mutex = Mutex(),
		val waiters: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque(),
		var active: Int = 0,
		var lastRequestStartedAt: Long? = null,
	)

	private val lanes = ConcurrentHashMap<String, Lane>()

	suspend fun <T> execute(capabilities: NovelTranslationProviderCapabilities, block: suspend () -> T): T {
		val lane = lanes.getOrPut(capabilities.providerId) { Lane() }
		acquire(lane, capabilities)
		try {
			lane.pacingMutex.withLock {
				val policy = healthController.policy(capabilities)
				val previous = lane.lastRequestStartedAt
				if (previous != null && policy.minRequestGapMillis > 0L) {
					val wait = policy.minRequestGapMillis - (nowMillis() - previous)
					if (wait > 0L) sleep(wait)
				}
				lane.lastRequestStartedAt = nowMillis()
			}
			return block()
		} finally {
			release(lane, capabilities)
		}
	}

	suspend fun policyChanged(capabilities: NovelTranslationProviderCapabilities) {
		val lane = lanes[capabilities.providerId] ?: return
		lane.gateMutex.withLock { drainWaitersLocked(lane, capabilities) }
	}

	private suspend fun acquire(lane: Lane, capabilities: NovelTranslationProviderCapabilities) {
		val waiter = CompletableDeferred<Unit>()
		val acquired = lane.gateMutex.withLock {
			val limit = healthController.policy(capabilities).maxConcurrency
			if (lane.waiters.isEmpty() && lane.active < limit) {
				lane.active++
				true
			} else {
				lane.waiters.addLast(waiter)
				false
			}
		}
		if (acquired) return
		try {
			waiter.await()
		} catch (error: CancellationException) {
			lane.gateMutex.withLock {
				val removedBeforeGrant = lane.waiters.remove(waiter)
				if (!removedBeforeGrant && waiter.isCompleted) {
					// drainWaitersLocked accounts a granted waiter as active before completing it. If
					// cancellation wins immediately after that grant, execute() has not reached its
					// release/finally yet, so return the granted slot here.
					lane.active = (lane.active - 1).coerceAtLeast(0)
				}
				drainWaitersLocked(lane, capabilities)
			}
			throw error
		}
	}

	private suspend fun release(lane: Lane, capabilities: NovelTranslationProviderCapabilities) {
		lane.gateMutex.withLock {
			lane.active = (lane.active - 1).coerceAtLeast(0)
			drainWaitersLocked(lane, capabilities)
		}
	}

	private fun drainWaitersLocked(lane: Lane, capabilities: NovelTranslationProviderCapabilities) {
		val limit = healthController.policy(capabilities).maxConcurrency
		while (lane.active < limit && lane.waiters.isNotEmpty()) {
			lane.active++
			lane.waiters.removeFirst().complete(Unit)
		}
	}
}

class NovelTranslationRetryPolicy(
	private val nowMillis: () -> Long = System::currentTimeMillis,
	private val jitterMillis: (Long) -> Long = { upperExclusive ->
		if (upperExclusive <= 0L) 0L else Random.nextLong(upperExclusive)
	},
) {
	fun retryDelayMillis(
		error: NovelTranslationFailure,
		failedAttempt: Int,
		capabilities: NovelTranslationProviderCapabilities,
	): Long? {
		if (failedAttempt >= capabilities.maxAttempts) return null
		val retryAfter = error.retryAfter?.let { parseRetryAfterMillis(it, nowMillis()) }
		if (retryAfter != null) return retryAfter.takeIf { it <= capabilities.maxRetryDelayMillis }
		if (!error.isRetryable()) return null
		val shift = (failedAttempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
		val exponential = capabilities.initialBackoffMillis * (1L shl shift)
		val base = exponential.coerceAtMost(capabilities.maxRetryDelayMillis)
		val jitterBound = (base / 5L).coerceAtLeast(1L)
		return (base + jitterMillis(jitterBound).coerceIn(0L, jitterBound - 1L))
			.coerceAtMost(capabilities.maxRetryDelayMillis)
	}

	private fun NovelTranslationFailure.isRetryable(): Boolean = when (category) {
		NovelTranslationFailureCategory.RATE_LIMITED,
		NovelTranslationFailureCategory.NETWORK,
		NovelTranslationFailureCategory.TIMEOUT,
		NovelTranslationFailureCategory.PROVIDER_UNAVAILABLE -> true
		NovelTranslationFailureCategory.CONFIGURATION,
		NovelTranslationFailureCategory.AUTHENTICATION,
		NovelTranslationFailureCategory.UNSUPPORTED,
		NovelTranslationFailureCategory.REQUEST_REJECTED,
		NovelTranslationFailureCategory.INVALID_RESPONSE,
		NovelTranslationFailureCategory.CACHE_CORRUPTION,
		NovelTranslationFailureCategory.INTERNAL_RECOVERABLE,
		NovelTranslationFailureCategory.CIRCUIT_OPEN -> false
	}

	companion object {
		private const val MAX_BACKOFF_SHIFT = 8
	}
}

/**
 * Owns translation priority, batching, task-pool execution, cache/resume, provider health and retry.
 * Reader asks for one coherent operation while receiving progressive completed segments.
 */
class NovelTranslationCoordinator(
	private val providerResolver: NovelTranslationProviderResolver,
	private val healthController: NovelTranslationProviderHealthController = NovelTranslationProviderHealthController(),
	private val scheduler: NovelTranslationRequestScheduler = NovelTranslationRequestScheduler(healthController = healthController),
	private val retryPolicy: NovelTranslationRetryPolicy = NovelTranslationRetryPolicy(),
	private val sleep: suspend (Long) -> Unit = { delay(it) },
	private val reliability: NovelTranslationReliabilityServices =
		(providerResolver as? NovelTranslationReliabilityServicesProvider)?.reliabilityServices()
			?: NovelTranslationReliabilityServices(
				cache = NoOpNovelTranslationResultCache,
				singleFlight = NovelTranslationSingleFlight(),
				circuitBreaker = NovelTranslationCircuitBreaker(),
				diagnostics = NovelTranslationDiagnostics(),
			),
) {
	private data class WorkItem(
		val index: Int,
		val segment: NovelTranslationSegment,
		val key: NovelTranslationSemanticKey,
		val beforeContext: String,
		val afterContext: String,
	)

	suspend fun translate(
		request: NovelTranslationOperationRequest,
		onSegmentTranslated: suspend (NovelTranslatedSegment, completedSegments: Int, totalSegments: Int) -> Unit = { _, _, _ -> },
		onProgress: suspend (NovelTranslationProgress) -> Unit = {},
	): NovelTranslationOperationResult {
		val provider = providerResolver.resolve(request.selection)
		val progressiveCallbacks = provider.capabilities.supportsProgressiveResultPublishing
		val priorityController = request.priorityController.takeIf { progressiveCallbacks }
		val segments = NovelTranslationSegmenter.split(request.text, provider.capabilities.maxSingleRequestChars)
		if (segments.isEmpty()) return NovelTranslationOperationResult(emptyList())
		val operationStarted = System.nanoTime()
		val operationKey = NovelTranslationSemanticKey(
			novelTranslationDigest(
				"translation-operation-v1",
				provider.capabilities.providerId,
				provider.semanticIdentity.configurationFingerprint,
				request.selection.sourceLanguage,
				request.selection.targetLanguage,
				novelTranslationDigest(request.text.toString()),
			),
		)
		val work = segments.mapIndexed { index, segment ->
			val beforeContext = if (request.selection.contextAware) {
				segments.getOrNull(index - 1)?.text ?: request.beforeContext
			} else ""
			val afterContext = if (request.selection.contextAware) {
				segments.getOrNull(index + 1)?.text ?: request.afterContext
			} else ""
			WorkItem(
				index = index,
				segment = segment,
				key = buildNovelTranslationSemanticKey(
					provider = provider,
					selection = request.selection,
					segmentText = segment.text,
					beforeContext = beforeContext,
					afterContext = afterContext,
				),
				beforeContext = beforeContext,
				afterContext = afterContext,
			)
		}
		val results = arrayOfNulls<NovelTranslatedSegment>(segments.size)
		val completed = AtomicInteger(0)
		val firstVisibleRecorded = AtomicBoolean(false)

		suspend fun publish(item: WorkItem, translatedText: String, cacheTier: String? = null) {
			val translated = NovelTranslatedSegment(item.segment, translatedText)
			results[item.index] = translated
			val done = completed.incrementAndGet()
			cacheTier?.let {
				recordDiagnostic(
					kind = NovelTranslationDiagnosticKind.CACHE_HIT,
					provider = provider,
					key = item.key,
					segmentCount = segments.size,
					cacheTier = it,
				)
			}
			if (progressiveCallbacks) onSegmentTranslated(translated, done, segments.size)
			onProgress(NovelTranslationProgress.Translating(done, segments.size, item.index, 1))
			val visible = priorityController?.visibleRange
			val isVisible = visible == null || (item.segment.start <= visible.last && item.segment.end > visible.first)
			if (isVisible && firstVisibleRecorded.compareAndSet(false, true)) {
				recordDiagnostic(
					kind = NovelTranslationDiagnosticKind.FIRST_VISIBLE_RESULT,
					provider = provider,
					key = operationKey,
					latencyMillis = (System.nanoTime() - operationStarted) / 1_000_000L,
					segmentCount = segments.size,
					priorityClass = "visible",
				)
			}
		}

		val blankItems = work.filter { it.segment.text.isBlank() }
		blankItems.sortedBy { NovelTranslationPrioritizer.score(it.segment, priorityController?.visibleRange) }
			.forEach { publish(it, it.segment.text) }

		val translatable = work.filterNot { it.segment.text.isBlank() }
		val cached = safeCacheReadMany(translatable.map { it.key }, provider, segments.size)
		val misses = ArrayList<WorkItem>()
		translatable.forEach { item ->
			val hit = cached[item.key]
			if (hit == null) {
				misses += item
				recordDiagnostic(NovelTranslationDiagnosticKind.CACHE_MISS, provider, item.key, segmentCount = segments.size)
			}
		}
		translatable.filter { cached.containsKey(it.key) }
			.sortedWith(compareBy { NovelTranslationPrioritizer.score(it.segment, priorityController?.visibleRange) })
			.forEach { item ->
				val (value, tier) = checkNotNull(cached[item.key])
				publish(item, value, tier)
			}

		try {
			if (misses.isNotEmpty()) {
				val canBatch = provider.capabilities.supportsBatch && provider.engine is NovelTranslationBatchEngine
				val queue = NovelTranslationPriorityQueue(
					items = misses,
					segmentOf = WorkItem::segment,
					priorityController = priorityController,
				)
				val workers = minOf(provider.capabilities.maxConcurrency.coerceAtLeast(1), misses.size)
				while (true) {
					currentCoroutineContext().ensureActive()
					val wave = ArrayList<List<WorkItem>>(workers)
					while (wave.size < workers) {
						val batch = queue.takeBatch(
							maxItems = if (canBatch) provider.capabilities.maxBatchItems else 1,
							maxChars = if (canBatch) provider.capabilities.maxBatchChars else provider.capabilities.maxSingleRequestChars,
							charCount = { item: WorkItem -> item.segment.text.length },
						)
						if (batch.isEmpty()) break
						wave += batch
					}
					if (wave.isEmpty()) break
					val waveFailure = coroutineScope {
						wave.map { batch ->
							async {
								try {
									if (canBatch && batch.size > 1) {
										val values = translateBatch(
											provider = provider,
											selection = request.selection,
											items = batch,
											segmentCount = segments.size,
											completed = completed,
											onProgress = onProgress,
										)
										batch.zip(values).forEach { (item, value) -> publish(item, value) }
									} else {
										val item = batch.single()
										val value = translateSingle(
											provider = provider,
											selection = request.selection,
											item = item,
											segmentCount = segments.size,
											completed = completed,
											onProgress = onProgress,
										)
										publish(item, value)
									}
									null
								} catch (error: NovelTranslationFailure) {
									error
								}
							}
						}.awaitAll().firstOrNull { it != null }
					}
					waveFailure?.let { throw it }
				}
			}

			val finalSegments = results.mapIndexed { index, item ->
				item ?: NovelTranslatedSegment(segments[index], segments[index].text)
			}
			recordDiagnostic(
				kind = NovelTranslationDiagnosticKind.OPERATION_COMPLETE,
				provider = provider,
				key = operationKey,
				latencyMillis = (System.nanoTime() - operationStarted) / 1_000_000L,
				segmentCount = segments.size,
			)
			return NovelTranslationOperationResult(finalSegments)
		} finally {
			// Segment writes stay off the request hot path, but by the operation boundary every
			// completed segment must be reusable even when a later segment failed or the caller
			// cancelled. This preserves the reliability contract without fsync-per-request.
			withContext(NonCancellable) {
				reliability.writeBehindCache.flushNow()
			}
		}
	}

	private suspend fun translateSingle(
		provider: NovelTranslationProvider,
		selection: NovelTranslationSelection,
		item: WorkItem,
		segmentCount: Int,
		completed: AtomicInteger,
		onProgress: suspend (NovelTranslationProgress) -> Unit,
	): String = reliability.singleFlight.execute(
		key = item.key,
		onEvent = { event -> reportFlightEvent(event, item.index, segmentCount, completed.get(), onProgress) },
	) { emit ->
		reliability.memoryCache.read(item.key)?.let { return@execute it }
		val started = System.nanoTime()
		try {
			val value = translateMissingSegment(provider, selection, item, segmentCount, emit)
			cacheTranslatedValue(item.key, value, provider, segmentCount)
			recordDiagnostic(
				kind = NovelTranslationDiagnosticKind.SUCCESS,
				provider = provider,
				key = item.key,
				latencyMillis = (System.nanoTime() - started) / 1_000_000L,
				segmentCount = segmentCount,
			)
			value
		} catch (error: CancellationException) {
			recordDiagnostic(
				kind = NovelTranslationDiagnosticKind.CANCELLED,
				provider = provider,
				key = item.key,
				segmentCount = segmentCount,
				cancellationCategory = "subscriber-or-lifecycle",
			)
			throw error
		} catch (error: NovelTranslationFailure) {
			recordDiagnostic(
				kind = NovelTranslationDiagnosticKind.FAILURE,
				provider = provider,
				key = item.key,
				error = error,
				latencyMillis = (System.nanoTime() - started) / 1_000_000L,
				segmentCount = segmentCount,
			)
			throw error
		}
	}

	private suspend fun translateBatch(
		provider: NovelTranslationProvider,
		selection: NovelTranslationSelection,
		items: List<WorkItem>,
		segmentCount: Int,
		completed: AtomicInteger,
		onProgress: suspend (NovelTranslationProgress) -> Unit,
	): List<String> = reliability.singleFlight.executeBatch(
		keys = items.map { it.key },
		onEvent = { event -> reportFlightEvent(event, items.first().index, segmentCount, completed.get(), onProgress) },
	) { emit ->
		val memoryHits = reliability.memoryCache.readMany(items.map { it.key })
		if (memoryHits.size == items.size) return@executeBatch items.map { checkNotNull(memoryHits[it.key]) }
		val missing = items.filterNot { memoryHits.containsKey(it.key) }
		val translatedMissing = translateMissingBatch(provider, selection, missing, segmentCount, emit)
		missing.zip(translatedMissing).forEach { (item, value) -> cacheTranslatedValue(item.key, value, provider, segmentCount) }
		val translatedByKey = missing.zip(translatedMissing).associate { (item, value) -> item.key to value }
		items.map { item -> memoryHits[item.key] ?: checkNotNull(translatedByKey[item.key]) }
	}

	private suspend fun reportFlightEvent(
		event: NovelTranslationFlightEvent,
		segmentIndex: Int,
		segmentCount: Int,
		completed: Int,
		onProgress: suspend (NovelTranslationProgress) -> Unit,
	) {
		when (event) {
			is NovelTranslationFlightEvent.Attempt -> onProgress(
				NovelTranslationProgress.Translating(completed, segmentCount, segmentIndex, event.attempt),
			)
			is NovelTranslationFlightEvent.Retry -> onProgress(
				NovelTranslationProgress.WaitingToRetry(
					completedSegments = completed,
					totalSegments = segmentCount,
					segmentIndex = segmentIndex,
					nextAttempt = event.nextAttempt,
					delayMillis = event.delayMillis,
					failure = event.failure,
				),
			)
		}
	}

	private suspend fun translateMissingSegment(
		provider: NovelTranslationProvider,
		selection: NovelTranslationSelection,
		item: WorkItem,
		segmentCount: Int,
		emit: suspend (NovelTranslationFlightEvent) -> Unit,
	): String {
		return executeProviderWithRetry(provider, item.key, segmentCount, 1, emit) {
			provider.engine.translate(item.toProviderRequest(selection))
		}.let { preserveSegmentBoundaryWhitespace(item.segment.text, it) }
	}

	private suspend fun translateMissingBatch(
		provider: NovelTranslationProvider,
		selection: NovelTranslationSelection,
		items: List<WorkItem>,
		segmentCount: Int,
		emit: suspend (NovelTranslationFlightEvent) -> Unit,
	): List<String> {
		val engine = provider.engine as? NovelTranslationBatchEngine
			?: throw NovelTranslationFailure(
				category = NovelTranslationFailureCategory.UNSUPPORTED,
				message = "Translation provider does not implement batch requests",
			)
		return executeProviderWithRetry(provider, items.first().key, segmentCount, items.size, emit) {
			val translated = engine.translateBatch(items.map { it.toProviderRequest(selection) })
			if (translated.size != items.size) {
				throw NovelTranslationException.InvalidResponse("Translation provider returned an incomplete batch result")
			}
			translated.mapIndexed { index, value -> preserveSegmentBoundaryWhitespace(items[index].segment.text, value) }
		}
	}

	private suspend fun <T> executeProviderWithRetry(
		provider: NovelTranslationProvider,
		key: NovelTranslationSemanticKey,
		segmentCount: Int,
		batchItems: Int,
		emit: suspend (NovelTranslationFlightEvent) -> Unit,
		request: suspend () -> T,
	): T {
		var attempt = 1
		while (true) {
			currentCoroutineContext().ensureActive()
			val circuitState = try {
				reliability.circuitBreaker.beforeRequest(provider.semanticIdentity.circuitKey)
			} catch (error: NovelTranslationFailure) {
				recordDiagnostic(
					kind = NovelTranslationDiagnosticKind.CIRCUIT_REJECTED,
					provider = provider,
					key = key,
					error = error,
					segmentCount = segmentCount,
					circuitState = NovelTranslationCircuitState.OPEN,
				)
				throw error
			}
			emit(NovelTranslationFlightEvent.Attempt(attempt))
			val policy = healthController.policy(provider.capabilities)
			recordDiagnostic(
				kind = if (batchItems > 1) NovelTranslationDiagnosticKind.BATCH_REQUEST else NovelTranslationDiagnosticKind.REQUEST_ATTEMPT,
				provider = provider,
				key = key,
				attempt = attempt,
				segmentCount = segmentCount,
				batchItems = batchItems,
				activeConcurrency = policy.maxConcurrency,
				requestGapMillis = policy.minRequestGapMillis,
				pressureLevel = policy.pressureLevel,
				circuitState = circuitState,
			)
			try {
				val result = scheduler.execute(provider.capabilities, request)
				reliability.circuitBreaker.onSuccess(provider.semanticIdentity.circuitKey)
				if (healthController.onSuccess(provider.capabilities.providerId)) {
					scheduler.policyChanged(provider.capabilities)
					recordPressureChange(provider, key, segmentCount)
				}
				return result
			} catch (error: CancellationException) {
				reliability.circuitBreaker.onCancellation(provider.semanticIdentity.circuitKey)
				throw error
			} catch (error: Throwable) {
				val typed = error.toNovelTranslationFailure()
				val pressureChanged = healthController.onFailure(provider.capabilities.providerId, typed)
				if (pressureChanged) {
					scheduler.policyChanged(provider.capabilities)
					recordPressureChange(provider, key, segmentCount)
				}
				reliability.circuitBreaker.onFailure(provider.semanticIdentity.circuitKey, typed)
				if (reliability.circuitBreaker.state(provider.semanticIdentity.circuitKey) == NovelTranslationCircuitState.OPEN) {
					throw typed
				}
				val retryDelay = retryPolicy.retryDelayMillis(typed, attempt, provider.capabilities) ?: throw typed
				emit(NovelTranslationFlightEvent.Retry(attempt + 1, retryDelay, typed))
				recordDiagnostic(
					kind = NovelTranslationDiagnosticKind.RETRY,
					provider = provider,
					key = key,
					error = typed,
					attempt = attempt,
					retryDelayMillis = retryDelay,
					segmentCount = segmentCount,
					batchItems = batchItems,
					pressureLevel = healthController.pressureLevel(provider.capabilities.providerId),
					circuitState = reliability.circuitBreaker.state(provider.semanticIdentity.circuitKey),
				)
				sleep(retryDelay)
				attempt++
			}
		}
	}

	private fun WorkItem.toProviderRequest(selection: NovelTranslationSelection) = NovelTranslationRequest(
		text = segment.text,
		sourceLanguage = selection.sourceLanguage,
		targetLanguage = selection.targetLanguage,
		style = selection.style,
		contextAware = selection.contextAware,
		beforeContext = beforeContext,
		afterContext = afterContext,
	)

	private fun preserveSegmentBoundaryWhitespace(source: String, translated: String): String {
		val firstContent = source.indexOfFirst { !it.isWhitespace() }
		if (firstContent < 0) return source
		val lastContent = source.indexOfLast { !it.isWhitespace() }
		return buildString(source.length.coerceAtLeast(translated.length)) {
			append(source, 0, firstContent)
			append(translated.trim())
			append(source, lastContent + 1, source.length)
		}
	}

	private suspend fun safeCacheReadMany(
		keys: Collection<NovelTranslationSemanticKey>,
		provider: NovelTranslationProvider,
		segmentCount: Int,
	): Map<NovelTranslationSemanticKey, Pair<String, String>> {
		if (keys.isEmpty()) return emptyMap()
		val memory = reliability.memoryCache.readMany(keys)
		val result = LinkedHashMap<NovelTranslationSemanticKey, Pair<String, String>>(keys.size)
		memory.forEach { (key, value) -> result[key] = value to "memory" }
		val missing = keys.filterNot(memory::containsKey)
		if (missing.isEmpty()) return result
		val persistent = try {
			reliability.cache.readMany(missing)
		} catch (error: CancellationException) {
			throw error
		} catch (_: NovelTranslationCacheCorruptionException) {
			recordDiagnostic(
				kind = NovelTranslationDiagnosticKind.CACHE_CORRUPTION_RECOVERED,
				provider = provider,
				key = missing.first(),
				errorCategory = NovelTranslationFailureCategory.CACHE_CORRUPTION,
				segmentCount = segmentCount,
			)
			readPersistentIndividually(missing, provider, segmentCount)
		} catch (_: Exception) {
			recordDiagnostic(
				kind = NovelTranslationDiagnosticKind.CACHE_READ_ERROR_RECOVERED,
				provider = provider,
				key = missing.first(),
				errorCategory = NovelTranslationFailureCategory.INTERNAL_RECOVERABLE,
				segmentCount = segmentCount,
			)
			emptyMap()
		}
		reliability.memoryCache.writeMany(persistent)
		persistent.forEach { (key, value) -> result[key] = value to "persistent" }
		return result
	}

	private suspend fun readPersistentIndividually(
		keys: Collection<NovelTranslationSemanticKey>,
		provider: NovelTranslationProvider,
		segmentCount: Int,
	): Map<NovelTranslationSemanticKey, String> = buildMap {
		keys.forEach { key ->
			try {
				reliability.cache.read(key)?.let { put(key, it) }
			} catch (error: CancellationException) {
				throw error
			} catch (_: NovelTranslationCacheCorruptionException) {
				recordDiagnostic(
					kind = NovelTranslationDiagnosticKind.CACHE_CORRUPTION_RECOVERED,
					provider = provider,
					key = key,
					errorCategory = NovelTranslationFailureCategory.CACHE_CORRUPTION,
					segmentCount = segmentCount,
				)
			} catch (_: Exception) {
				recordDiagnostic(
					kind = NovelTranslationDiagnosticKind.CACHE_READ_ERROR_RECOVERED,
					provider = provider,
					key = key,
					errorCategory = NovelTranslationFailureCategory.INTERNAL_RECOVERABLE,
					segmentCount = segmentCount,
				)
			}
		}
	}

	private fun cacheTranslatedValue(
		key: NovelTranslationSemanticKey,
		value: String,
		provider: NovelTranslationProvider,
		segmentCount: Int,
	) {
		reliability.memoryCache.write(key, value)
		reliability.writeBehindCache.enqueue(key, value)
		recordDiagnostic(
			kind = NovelTranslationDiagnosticKind.CACHE_STORE,
			provider = provider,
			key = key,
			segmentCount = segmentCount,
			cacheTier = "write-behind",
		)
	}

	private fun recordPressureChange(
		provider: NovelTranslationProvider,
		key: NovelTranslationSemanticKey,
		segmentCount: Int,
	) {
		val policy = healthController.policy(provider.capabilities)
		recordDiagnostic(
			kind = NovelTranslationDiagnosticKind.PROVIDER_PRESSURE_CHANGED,
			provider = provider,
			key = key,
			segmentCount = segmentCount,
			activeConcurrency = policy.maxConcurrency,
			requestGapMillis = policy.minRequestGapMillis,
			pressureLevel = policy.pressureLevel,
		)
	}

	private fun recordDiagnostic(
		kind: NovelTranslationDiagnosticKind,
		provider: NovelTranslationProvider,
		key: NovelTranslationSemanticKey,
		error: NovelTranslationFailure? = null,
		errorCategory: NovelTranslationFailureCategory? = null,
		latencyMillis: Long? = null,
		attempt: Int? = null,
		retryDelayMillis: Long? = null,
		segmentCount: Int? = null,
		batchItems: Int? = null,
		activeConcurrency: Int? = null,
		requestGapMillis: Long? = null,
		pressureLevel: Int? = null,
		priorityClass: String? = null,
		cacheTier: String? = null,
		circuitState: NovelTranslationCircuitState? = null,
		cancellationCategory: String? = null,
	) {
		reliability.diagnostics.record(
			NovelTranslationDiagnosticEvent(
				kind = kind,
				provider = provider.capabilities.providerId,
				operationId = key.operationId,
				errorCategory = errorCategory ?: error?.category,
				httpStatus = error?.statusCode,
				latencyMillis = latencyMillis,
				attempt = attempt,
				retryDelayMillis = retryDelayMillis,
				segmentCount = segmentCount,
				batchItems = batchItems,
				activeConcurrency = activeConcurrency,
				requestGapMillis = requestGapMillis,
				pressureLevel = pressureLevel,
				priorityClass = priorityClass,
				cacheTier = cacheTier,
				circuitState = circuitState,
				cancellationCategory = cancellationCategory,
			),
		)
	}
}

enum class NovelTranslationFailureCategory {
	CONFIGURATION,
	AUTHENTICATION,
	UNSUPPORTED,
	RATE_LIMITED,
	NETWORK,
	TIMEOUT,
	PROVIDER_UNAVAILABLE,
	REQUEST_REJECTED,
	INVALID_RESPONSE,
	CACHE_CORRUPTION,
	INTERNAL_RECOVERABLE,
	CIRCUIT_OPEN,
}

class NovelTranslationFailure(
	val category: NovelTranslationFailureCategory,
	val statusCode: Int? = null,
	val retryAfter: String? = null,
	message: String,
	cause: Throwable? = null,
) : IOException(message, cause)

private class NovelTranslationReliabilityHttpException(
	val statusCode: Int,
	val retryAfter: String?,
) : IOException("Translation request failed with HTTP $statusCode")

private class NovelTranslationReliabilityInterceptor : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		if (response.code == 408 || response.code == 429) {
			val statusCode = response.code
			val retryAfter = response.header("Retry-After")
			response.close()
			throw NovelTranslationReliabilityHttpException(statusCode, retryAfter)
		}
		return response
	}
}

internal fun Throwable.toNovelTranslationFailure(): NovelTranslationFailure = when (this) {
	is NovelTranslationFailure -> this
	is NovelTranslationReliabilityHttpException -> when (statusCode) {
		408 -> NovelTranslationFailure(
			category = NovelTranslationFailureCategory.TIMEOUT,
			statusCode = statusCode,
			message = "Translation request timed out (HTTP 408)",
			cause = this,
		)
		429 -> NovelTranslationFailure(
			category = NovelTranslationFailureCategory.RATE_LIMITED,
			statusCode = statusCode,
			retryAfter = retryAfter,
			message = "Translation provider rate limit reached",
			cause = this,
		)
		else -> NovelTranslationFailure(
			category = NovelTranslationFailureCategory.REQUEST_REJECTED,
			statusCode = statusCode,
			message = message ?: "Translation request was rejected",
			cause = this,
		)
	}
	is NovelTranslationException.Configuration -> NovelTranslationFailure(
		NovelTranslationFailureCategory.CONFIGURATION,
		message = message ?: "Translation configuration is invalid",
		cause = this,
	)
	is NovelTranslationException.Authentication -> NovelTranslationFailure(
		NovelTranslationFailureCategory.AUTHENTICATION,
		statusCode = 401,
		message = message ?: "Translation authorization failed",
		cause = this,
	)
	is NovelTranslationException.RateLimited -> NovelTranslationFailure(
		NovelTranslationFailureCategory.RATE_LIMITED,
		statusCode = 429,
		retryAfter = retryAfterSeconds?.toString(),
		message = message ?: "Translation provider rate limit reached",
		cause = this,
	)
	is NovelTranslationException.ProviderUnavailable -> NovelTranslationFailure(
		NovelTranslationFailureCategory.PROVIDER_UNAVAILABLE,
		statusCode = statusCode,
		message = message ?: "Translation provider is unavailable",
		cause = this,
	)
	is NovelTranslationException.RequestRejected -> NovelTranslationFailure(
		if (statusCode == 408) NovelTranslationFailureCategory.TIMEOUT else NovelTranslationFailureCategory.REQUEST_REJECTED,
		statusCode = statusCode,
		message = message ?: "Translation request was rejected",
		cause = this,
	)
	is NovelTranslationException.InvalidResponse -> NovelTranslationFailure(
		NovelTranslationFailureCategory.INVALID_RESPONSE,
		message = message ?: "Translation provider returned an invalid response",
		cause = this,
	)
	is SocketTimeoutException, is InterruptedIOException -> NovelTranslationFailure(
		NovelTranslationFailureCategory.TIMEOUT,
		message = message ?: "Translation request timed out",
		cause = this,
	)
	is IOException -> NovelTranslationFailure(
		NovelTranslationFailureCategory.NETWORK,
		message = message ?: "Translation network request failed",
		cause = this,
	)
	else -> NovelTranslationFailure(
		NovelTranslationFailureCategory.INVALID_RESPONSE,
		message = message ?: "Translation failed",
		cause = this,
	)
}

internal fun parseRetryAfterMillis(value: String?, nowMillis: Long): Long? {
	val raw = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
	raw.toLongOrNull()?.let { seconds ->
		if (seconds < 0L) return null
		return seconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
	}
	return runCatching {
		(ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
			.coerceAtLeast(0L)
	}.getOrNull()
}

private const val DEFAULT_PROVIDER_CONTRACT_VERSION = "provider-v1"