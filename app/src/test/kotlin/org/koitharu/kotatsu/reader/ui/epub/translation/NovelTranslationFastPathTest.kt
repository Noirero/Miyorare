package org.koitharu.kotatsu.reader.ui.epub.translation

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTranslationFastPathTest {

	@Test
	fun `visible segment is prioritized before chapter start`() = runTest {
		val calls = mutableListOf<String>()
		val coordinator = coordinator(
			engine = engine { request -> calls += request.text.take(1); request.text },
			capabilities = capabilities(maxConcurrency = 1),
		)
		val text = "A".repeat(256) + "B".repeat(256) + "C".repeat(256) + "D".repeat(256)
		val priority = NovelTranslationPriorityController(512..767)

		coordinator.translate(operation(text, priority))

		assertEquals("C", calls.first())
	}

	@Test
	fun `priority queue observes live viewport reprioritization`() = runTest {
		val segments = listOf(
			NovelTranslationSegment(0, 256, "A".repeat(256)),
			NovelTranslationSegment(256, 512, "B".repeat(256)),
			NovelTranslationSegment(512, 768, "C".repeat(256)),
		)
		val priority = NovelTranslationPriorityController(0..255)
		val queue = NovelTranslationPriorityQueue(segments, { it }, priority)

		assertEquals(0, queue.takeBatch(1, 256) { it.text.length }.single().start)
		priority.update(512..767)
		assertEquals(512, queue.takeBatch(1, 256) { it.text.length }.single().start)
	}

	@Test
	fun `true batch engine reduces provider request count`() = runTest {
		var singleCalls = 0
		var batchCalls = 0
		val batchEngine = object : NovelTranslationEngine, NovelTranslationBatchEngine {
			override suspend fun translate(request: NovelTranslationRequest): String {
				singleCalls++
				return request.text
			}
			override suspend fun translateBatch(requests: List<NovelTranslationRequest>): List<String> {
				batchCalls++
				return requests.map { it.text }
			}
		}
		val coordinator = coordinator(
			engine = batchEngine,
			capabilities = capabilities(maxConcurrency = 2).copy(
				supportsBatch = true,
				maxBatchItems = 10,
				maxBatchChars = 2560,
			),
		)
		val text = buildString { repeat(20) { append(('A'.code + it).toChar().toString().repeat(256)) } }

		val result = coordinator.translate(operation(text))

		assertEquals(text, result.translatedText)
		assertEquals(0, singleCalls)
		assertEquals(2, batchCalls)
	}

	@Test
	fun `visible result is published before full chapter completion`() = runTest {
		val published = mutableListOf<Int>()
		val coordinator = coordinator(
			engine = engine { request -> delay(10); request.text },
			capabilities = capabilities(maxConcurrency = 1),
		)
		val text = "A".repeat(256) + "B".repeat(256) + "C".repeat(256) + "D".repeat(256)
		val priority = NovelTranslationPriorityController(512..767)

		coordinator.translate(operation(text, priority), onSegmentTranslated = { segment, completed, total ->
			assertTrue(completed < total || published.isNotEmpty())
			published += segment.source.start
		})

		assertEquals(512, published.first())
		assertEquals(4, published.size)
	}

	@Test
	fun `provider pressure drops concurrency on rate limit and recovers gradually`() {
		val health = NovelTranslationProviderHealthController()
		val capabilities = capabilities(maxConcurrency = 3).copy(minRequestGapMillis = 100L)
		val rateLimited = NovelTranslationFailure(
			category = NovelTranslationFailureCategory.RATE_LIMITED,
			message = "429",
		)

		assertEquals(3, health.policy(capabilities).maxConcurrency)
		assertTrue(health.onFailure(capabilities.providerId, rateLimited))
		assertEquals(1, health.policy(capabilities).maxConcurrency)
		assertTrue(health.policy(capabilities).minRequestGapMillis >= 750L)

		repeat(2) { assertFalse(health.onSuccess(capabilities.providerId)) }
		assertTrue(health.onSuccess(capabilities.providerId))
		assertTrue(health.policy(capabilities).maxConcurrency >= 2)
	}

	@Test
	fun `memory cache is bounded LRU`() {
		val cache = NovelTranslationMemoryCache(maxEntries = 2)
		val first = NovelTranslationSemanticKey("first")
		val second = NovelTranslationSemanticKey("second")
		val third = NovelTranslationSemanticKey("third")
		cache.write(first, "1")
		cache.write(second, "2")
		assertEquals("1", cache.read(first))
		cache.write(third, "3")

		assertEquals("1", cache.read(first))
		assertEquals(null, cache.read(second))
		assertEquals("3", cache.read(third))
	}

	@Test
	fun `memory cache invalidates when durable backing changes`() = runTest {
		val persistent = CoherentRecordingCache()
		val cache = NovelTranslationMemoryCache(maxEntries = 2)
		cache.bindBackingCache(persistent)
		val key = NovelTranslationSemanticKey("key")
		persistent.write(key, "old")
		cache.write(key, "old")
		assertEquals("old", cache.read(key))

		persistent.write(key, "new")

		assertEquals(null, cache.read(key))
		assertEquals("new", persistent.read(key))
	}

	@Test
	fun `write behind exposes result before durable flush`() = runTest {
		val persistent = RecordingCache()
		val writer = NovelTranslationWriteBehindCache(persistent, flushDelayMillis = Long.MAX_VALUE)
		val key = NovelTranslationSemanticKey("key")

		writer.enqueue(key, "translated")
		assertEquals(1, writer.pendingCount())
		assertEquals(null, persistent.read(key))
		writer.flushNow()
		assertEquals("translated", persistent.read(key))
	}

	@Test
	fun `write behind persistence retries are bounded`() = runTest {
		val persistent = FailingCache()
		val writer = NovelTranslationWriteBehindCache(
			persistent = persistent,
			scope = this,
			flushDelayMillis = 1L,
		)
		val key = NovelTranslationSemanticKey("key")

		writer.enqueue(key, "translated")
		advanceUntilIdle()

		assertEquals(4, persistent.writeAttempts)
		assertEquals(3, writer.automaticRetryCount())
		assertEquals(1, writer.pendingCount())
	}

	private fun coordinator(
		engine: NovelTranslationEngine,
		capabilities: NovelTranslationProviderCapabilities,
	): NovelTranslationCoordinator {
		val health = NovelTranslationProviderHealthController()
		return NovelTranslationCoordinator(
			providerResolver = NovelTranslationProviderResolver { NovelTranslationProvider(engine, capabilities) },
			healthController = health,
			scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {}, healthController = health),
			retryPolicy = NovelTranslationRetryPolicy(nowMillis = { 0L }, jitterMillis = { 0L }),
			sleep = {},
		)
	}

	private fun capabilities(maxConcurrency: Int) = NovelTranslationProviderCapabilities(
		providerId = "fast-path-test",
		maxBatchChars = 256,
		maxSingleRequestChars = 256,
		credentialRequired = false,
		maxConcurrency = maxConcurrency,
		minRequestGapMillis = 0L,
		maxAttempts = 3,
		initialBackoffMillis = 1L,
		maxRetryDelayMillis = 5_000L,
	)

	private fun operation(text: String, priority: NovelTranslationPriorityController? = null) =
		NovelTranslationOperationRequest(
			text = text,
			selection = NovelTranslationSelection(
				engine = NovelTranslationEngineKind.ONLINE,
				sourceLanguage = "auto",
				targetLanguage = "id",
				style = NovelTranslationStyle.NATURAL,
				contextAware = false,
			),
			priorityController = priority,
		)

	private fun engine(block: suspend (NovelTranslationRequest) -> String) = object : NovelTranslationEngine {
		override suspend fun translate(request: NovelTranslationRequest): String = block(request)
	}

	private open class RecordingCache : NovelTranslationResultCache {
		protected val values = LinkedHashMap<NovelTranslationSemanticKey, String>()
		override suspend fun read(key: NovelTranslationSemanticKey): String? = values[key]
		override suspend fun write(key: NovelTranslationSemanticKey, translatedText: String): Boolean {
			values[key] = translatedText
			return true
		}
	}

	private class CoherentRecordingCache : RecordingCache(), NovelTranslationCacheCoherence {
		private val versions = HashMap<NovelTranslationSemanticKey, Int>()
		override suspend fun write(key: NovelTranslationSemanticKey, translatedText: String): Boolean {
			val stored = super.write(key, translatedText)
			versions[key] = (versions[key] ?: 0) + 1
			return stored
		}
		override fun backingStamp(key: NovelTranslationSemanticKey): String? = versions[key]?.toString()
	}

	private class FailingCache : NovelTranslationResultCache {
		var writeAttempts = 0
		override suspend fun read(key: NovelTranslationSemanticKey): String? = null
		override suspend fun write(key: NovelTranslationSemanticKey, translatedText: String): Boolean {
			writeAttempts++
			throw IOException("disk unavailable")
		}
	}
}
