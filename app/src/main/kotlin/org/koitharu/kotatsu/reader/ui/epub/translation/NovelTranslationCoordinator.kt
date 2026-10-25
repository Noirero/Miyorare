package org.koitharu.kotatsu.reader.ui.epub.translation

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Explicit provider behavior used by the reliability layer. */
data class NovelTranslationProviderCapabilities(
	val providerId: String,
	val supportsBatch: Boolean = false,
	val maxBatchItems: Int = 1,
	val maxBatchChars: Int,
	val maxSingleRequestChars: Int,
	val supportsAutoDetect: Boolean = true,
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
)

fun interface NovelTranslationProviderResolver {
	fun resolve(selection: NovelTranslationSelection): NovelTranslationProvider
}

/**
 * Current provider resolver. Provider hardening can replace adapters later without moving
 * orchestration back into the Reader.
 */
class DefaultNovelTranslationProviderResolver(
	httpClient: OkHttpClient,
	private val settings: NovelTranslationSettings,
	private val secrets: NovelTranslationSecrets,
) : NovelTranslationProviderResolver {

	private val reliableHttpClient = httpClient.newBuilder()
		.addInterceptor(NovelTranslationReliabilityInterceptor())
		.build()

	override fun resolve(selection: NovelTranslationSelection): NovelTranslationProvider = when (selection.engine) {
		NovelTranslationEngineKind.ONLINE -> NovelTranslationProvider(
			engine = MiyorareOnlineTranslationEngine(reliableHttpClient),
			capabilities = NovelTranslationProviderCapabilities(
				providerId = "miyorare-online",
				maxBatchChars = ONLINE_SEGMENT_CHARS,
				maxSingleRequestChars = ONLINE_SEGMENT_CHARS,
				credentialRequired = false,
				maxConcurrency = 1,
				minRequestGapMillis = ONLINE_REQUEST_GAP_MS,
			),
		)
		NovelTranslationEngineKind.AI -> NovelTranslationProvider(
			engine = NovelAiTranslationEngine(reliableHttpClient, settings, secrets),
			capabilities = NovelTranslationProviderCapabilities(
				providerId = "ai:${settings.provider.id}",
				maxBatchChars = AI_SEGMENT_CHARS,
				maxSingleRequestChars = AI_SEGMENT_CHARS,
				credentialRequired = true,
				maxConcurrency = 1,
				minRequestGapMillis = AI_REQUEST_GAP_MS,
			),
		)
	}

	companion object {
		// Preserve the proven current reader limits while moving ownership below the Reader.
		private const val ONLINE_SEGMENT_CHARS = 1600
		private const val AI_SEGMENT_CHARS = 8000
		private const val ONLINE_REQUEST_GAP_MS = 250L
		private const val AI_REQUEST_GAP_MS = 150L
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
		val floor = (start + limit * 2 / 3).coerceAtMost(hardEnd)
		val paragraph = text.lastIndexOf("\n\n", hardEnd - 1).takeIf { it >= floor }?.plus(2)
		if (paragraph != null) return paragraph
		val line = text.lastIndexOf('\n', hardEnd - 1).takeIf { it >= floor }?.plus(1)
		if (line != null) return line
		for (index in hardEnd - 1 downTo floor) {
			if (text[index] in SENTENCE_ENDINGS && (index + 1 >= text.length || text[index + 1].isWhitespace())) {
				return index + 1
			}
		}
		for (index in hardEnd - 1 downTo floor) {
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

/** Provider-keyed request scheduler. It bounds concurrency and spaces request starts. */
class NovelTranslationRequestScheduler(
	private val nowMillis: () -> Long = System::currentTimeMillis,
	private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
	private data class Lane(
		val semaphore: Semaphore,
		val pacingMutex: Mutex = Mutex(),
		var lastRequestStartedAt: Long? = null,
	)

	private val lanes = ConcurrentHashMap<String, Lane>()

	suspend fun <T> execute(capabilities: NovelTranslationProviderCapabilities, block: suspend () -> T): T {
		val lane = lanes.getOrPut(capabilities.providerId) {
			Lane(Semaphore(capabilities.maxConcurrency.coerceAtLeast(1)))
		}
		return lane.semaphore.withPermit {
			lane.pacingMutex.withLock {
				val previous = lane.lastRequestStartedAt
				if (previous != null && capabilities.minRequestGapMillis > 0) {
					val wait = capabilities.minRequestGapMillis - (nowMillis() - previous)
					if (wait > 0) sleep(wait)
				}
				lane.lastRequestStartedAt = nowMillis()
			}
			block()
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
		if (retryAfter != null) {
			// Never retry earlier than the provider asks. If Retry-After exceeds this operation's
			// bounded retry budget, stop rather than turning the client into a long-lived sleeper.
			return retryAfter.takeIf { it <= capabilities.maxRetryDelayMillis }
		}
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
		NovelTranslationFailureCategory.INVALID_RESPONSE -> false
	}

	companion object {
		private const val MAX_BACKOFF_SHIFT = 8
	}
}

/**
 * Owns translation segmentation, request scheduling, pacing and retries. Reader code asks for an
 * operation and receives ordered source-mapped segments; it no longer loops provider calls itself.
 */
class NovelTranslationCoordinator(
	private val providerResolver: NovelTranslationProviderResolver,
	private val scheduler: NovelTranslationRequestScheduler = NovelTranslationRequestScheduler(),
	private val retryPolicy: NovelTranslationRetryPolicy = NovelTranslationRetryPolicy(),
	private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

	suspend fun translate(
		request: NovelTranslationOperationRequest,
		onProgress: suspend (NovelTranslationProgress) -> Unit = {},
	): NovelTranslationOperationResult {
		val provider = providerResolver.resolve(request.selection)
		val segments = NovelTranslationSegmenter.split(request.text, provider.capabilities.maxSingleRequestChars)
		val translated = ArrayList<NovelTranslatedSegment>(segments.size)
		for ((index, segment) in segments.withIndex()) {
			currentCoroutineContext().ensureActive()
			if (segment.text.isBlank()) {
				translated += NovelTranslatedSegment(segment, segment.text)
				onProgress(NovelTranslationProgress.Translating(index + 1, segments.size, index, 1))
				continue
			}
			var attempt = 1
			while (true) {
				currentCoroutineContext().ensureActive()
				onProgress(NovelTranslationProgress.Translating(index, segments.size, index, attempt))
				try {
					val translatedText = scheduler.execute(provider.capabilities) {
						provider.engine.translate(
							NovelTranslationRequest(
								text = segment.text,
								sourceLanguage = request.selection.sourceLanguage,
								targetLanguage = request.selection.targetLanguage,
								style = request.selection.style,
								contextAware = request.selection.contextAware,
								beforeContext = if (request.selection.contextAware) {
									segments.getOrNull(index - 1)?.text ?: request.beforeContext
								} else "",
								afterContext = if (request.selection.contextAware) {
									segments.getOrNull(index + 1)?.text ?: request.afterContext
								} else "",
							),
						)
					}
					translated += NovelTranslatedSegment(segment, translatedText)
					onProgress(NovelTranslationProgress.Translating(index + 1, segments.size, index, attempt))
					break
				} catch (error: CancellationException) {
					throw error
				} catch (error: Throwable) {
					val typed = error.toNovelTranslationFailure()
					val retryDelay = retryPolicy.retryDelayMillis(typed, attempt, provider.capabilities)
						?: throw typed
					onProgress(
						NovelTranslationProgress.WaitingToRetry(
							completedSegments = index,
							totalSegments = segments.size,
							segmentIndex = index,
							nextAttempt = attempt + 1,
							delayMillis = retryDelay,
							failure = typed,
						),
					)
					sleep(retryDelay)
					attempt++
				}
			}
		}
		return NovelTranslationOperationResult(translated)
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
