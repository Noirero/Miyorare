package org.koitharu.kotatsu.reader.ui.epub.translation

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NovelTranslationCoordinatorTest {

	@Test
	fun `segmenter preserves source offsets and prefers paragraph boundary`() {
		val text = "a".repeat(180) + "\n\n" + "b".repeat(180)
		val segments = NovelTranslationSegmenter.split(text, 300)

		assertEquals(2, segments.size)
		assertEquals(0, segments.first().start)
		assertEquals(182, segments.first().end)
		assertEquals(text.length, segments.last().end)
		assertEquals(text, segments.joinToString("") { it.text })
	}

	@Test
	fun `segmenter never splits surrogate pair`() {
		val text = "a".repeat(255) + "😀" + "b".repeat(260)
		val segments = NovelTranslationSegmenter.split(text, 256)

		assertEquals(text, segments.joinToString("") { it.text })
		segments.dropLast(1).forEach { segment ->
			assertTrue(segment.text.isEmpty() || !Character.isHighSurrogate(segment.text.last()))
		}
	}

	@Test
	fun `retry after supports delta seconds`() {
		assertEquals(3_000L, parseRetryAfterMillis("3", nowMillis = 123L))
		assertNull(parseRetryAfterMillis("-1", nowMillis = 123L))
	}

	@Test
	fun `retry after supports HTTP date`() {
		val now = Instant.parse("2026-10-25T13:00:00Z")
		val retryAt = now.plusSeconds(12)
		val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(retryAt.atZone(ZoneOffset.UTC))

		assertEquals(12_000L, parseRetryAfterMillis(header, now.toEpochMilli()))
	}

	@Test
	fun `timeout retries and then succeeds`() = runTest {
		var calls = 0
		val coordinator = coordinator(
			engine = engine { request ->
				calls++
				if (calls == 1) throw NovelTranslationFailure(
					category = NovelTranslationFailureCategory.TIMEOUT,
					message = "timeout",
				)
				"ok:${request.text}"
			},
		)

		val result = coordinator.translate(operation("hello"))

		assertEquals(2, calls)
		assertEquals("ok:hello", result.translatedText)
	}

	@Test
	fun `HTTP 408 is classified retryable`() = runTest {
		var calls = 0
		val coordinator = coordinator(
			engine = engine { request ->
				calls++
				if (calls == 1) throw NovelTranslationException.RequestRejected(408)
				request.text
			},
		)

		coordinator.translate(operation("hello"))

		assertEquals(2, calls)
	}

	@Test
	fun `network failure retries and then succeeds`() = runTest {
		var calls = 0
		val coordinator = coordinator(
			engine = engine { request ->
				calls++
				if (calls == 1) throw IOException("offline")
				request.text
			},
		)

		coordinator.translate(operation("hello"))

		assertEquals(2, calls)
	}

	@Test
	fun `provider unavailable retries and then succeeds`() = runTest {
		var calls = 0
		val coordinator = coordinator(
			engine = engine { request ->
				calls++
				if (calls == 1) throw NovelTranslationException.ProviderUnavailable(503)
				request.text
			},
		)

		coordinator.translate(operation("hello"))

		assertEquals(2, calls)
	}

	@Test
	fun `rate limit honors retry after before retrying`() = runTest {
		var calls = 0
		val slept = mutableListOf<Long>()
		val coordinator = coordinator(
			engine = engine { request ->
				calls++
				if (calls == 1) throw NovelTranslationFailure(
					category = NovelTranslationFailureCategory.RATE_LIMITED,
					retryAfter = "2",
					message = "rate limited",
				)
				request.text.uppercase()
			},
			sleep = { slept += it },
		)

		val result = coordinator.translate(operation("hello"))

		assertEquals(2, calls)
		assertEquals(listOf(2_000L), slept)
		assertEquals("HELLO", result.translatedText)
	}

	@Test
	fun `retry exhaustion stops after configured attempts`() = runTest {
		var calls = 0
		val slept = mutableListOf<Long>()
		val coordinator = coordinator(
			engine = engine {
				calls++
				throw NovelTranslationException.ProviderUnavailable(503)
			},
			sleep = { slept += it },
		)

		try {
			coordinator.translate(operation("hello"))
			fail("Expected provider failure")
		} catch (error: NovelTranslationFailure) {
			assertEquals(NovelTranslationFailureCategory.PROVIDER_UNAVAILABLE, error.category)
		}
		assertEquals(3, calls)
		assertEquals(2, slept.size)
	}

	@Test
	fun `authentication failure fails fast`() = runTest {
		var calls = 0
		val coordinator = coordinator(
			engine = engine {
				calls++
				throw NovelTranslationException.Authentication()
			},
		)

		try {
			coordinator.translate(operation("hello"))
			fail("Expected authentication failure")
		} catch (error: NovelTranslationFailure) {
			assertEquals(NovelTranslationFailureCategory.AUTHENTICATION, error.category)
		}
		assertEquals(1, calls)
	}

	@Test
	fun `configuration failure fails fast`() = runTest {
		var calls = 0
		val coordinator = coordinator(
			engine = engine {
				calls++
				throw NovelTranslationException.Configuration("missing model")
			},
		)

		try {
			coordinator.translate(operation("hello"))
			fail("Expected configuration failure")
		} catch (error: NovelTranslationFailure) {
			assertEquals(NovelTranslationFailureCategory.CONFIGURATION, error.category)
		}
		assertEquals(1, calls)
	}

	@Test
	fun `request rejected 400 fails fast`() = runTest {
		var calls = 0
		val coordinator = coordinator(
			engine = engine {
				calls++
				throw NovelTranslationException.RequestRejected(400)
			},
		)

		try {
			coordinator.translate(operation("hello"))
			fail("Expected request rejection")
		} catch (error: NovelTranslationFailure) {
			assertEquals(NovelTranslationFailureCategory.REQUEST_REJECTED, error.category)
			assertEquals(400, error.statusCode)
		}
		assertEquals(1, calls)
	}

	@Test
	fun `invalid response fails fast`() = runTest {
		var calls = 0
		val coordinator = coordinator(
			engine = engine {
				calls++
				throw NovelTranslationException.InvalidResponse("bad payload")
			},
		)

		try {
			coordinator.translate(operation("hello"))
			fail("Expected invalid response")
		} catch (error: NovelTranslationFailure) {
			assertEquals(NovelTranslationFailureCategory.INVALID_RESPONSE, error.category)
		}
		assertEquals(1, calls)
	}

	@Test
	fun `cancellation is terminal and never retried`() = runTest {
		var calls = 0
		val coordinator = coordinator(
			engine = engine {
				calls++
				throw CancellationException("cancel")
			},
		)

		try {
			coordinator.translate(operation("hello"))
			fail("Expected cancellation")
		} catch (_: CancellationException) {
			// Expected.
		}
		assertEquals(1, calls)
	}

	@Test
	fun `external context is preserved for a single context aware segment`() = runTest {
		var captured: NovelTranslationRequest? = null
		val coordinator = coordinator(
			engine = engine { request ->
				captured = request
				request.text
			},
		)
		val request = operation("hello", contextAware = true).copy(
			beforeContext = "before",
			afterContext = "after",
		)

		coordinator.translate(request)

		assertEquals("before", captured?.beforeContext)
		assertEquals("after", captured?.afterContext)
	}

	@Test
	fun `scheduler enforces concurrency bound`() = runTest {
		val scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {})
		val capabilities = capabilities(maxConcurrency = 1)
		var active = 0
		var maxActive = 0

		coroutineScope {
			(0 until 4).map {
				async {
					scheduler.execute(capabilities) {
						active++
						maxActive = maxOf(maxActive, active)
						yield()
						active--
					}
				}
			}.awaitAll()
		}

		assertEquals(1, maxActive)
	}

	@Test
	fun `scheduler enforces minimum request gap`() = runTest {
		var now = 0L
		val waits = mutableListOf<Long>()
		val scheduler = NovelTranslationRequestScheduler(
			nowMillis = { now },
			sleep = { wait -> waits += wait; now += wait },
		)
		val capabilities = capabilities().copy(minRequestGapMillis = 100L)

		scheduler.execute(capabilities) { Unit }
		scheduler.execute(capabilities) { Unit }

		assertEquals(listOf(100L), waits)
	}

	@Test
	fun `translated chunks preserve source paragraph whitespace`() = runTest {
		val text = "a".repeat(254) + "\n\n" + "b".repeat(256)
		val coordinator = coordinator(
			engine = engine { request -> if (request.text.startsWith('a')) "ONE" else "TWO" },
		)

		val result = coordinator.translate(operation(text))

		assertEquals("ONE\n\nTWO", result.translatedText)
	}

	@Test
	fun `scheduler cancellation removes queued work cleanly`() = runTest {
		val scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {})
		val capabilities = capabilities(maxConcurrency = 1)
		val started = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		var queuedExecuted = false
		val first = async {
			scheduler.execute(capabilities) {
				started.complete(Unit)
				release.await()
			}
		}
		started.await()
		val queued = async { scheduler.execute(capabilities) { queuedExecuted = true } }
		yield()
		queued.cancelAndJoin()
		release.complete(Unit)
		first.await()
		scheduler.execute(capabilities) { Unit }

		assertFalse(queuedExecuted)
	}

	@Test
	fun `scheduler lets queued small work run before large work reacquires`() = runTest {
		val scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {})
		val capabilities = capabilities(maxConcurrency = 1)
		val firstStarted = CompletableDeferred<Unit>()
		val releaseFirst = CompletableDeferred<Unit>()
		val order = mutableListOf<String>()
		val large = async {
			scheduler.execute(capabilities) {
				order += "large-1"
				firstStarted.complete(Unit)
				releaseFirst.await()
			}
			scheduler.execute(capabilities) { order += "large-2" }
		}
		firstStarted.await()
		val small = async { scheduler.execute(capabilities) { order += "small" } }
		yield()
		releaseFirst.complete(Unit)
		large.await()
		small.await()

		assertEquals(listOf("large-1", "small", "large-2"), order)
	}

	@Test
	fun `scheduler isolates provider lanes`() = runTest {
		val scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {})
		val providerA = capabilities(maxConcurrency = 1).copy(providerId = "provider-a")
		val providerB = capabilities(maxConcurrency = 1).copy(providerId = "provider-b")
		val aStarted = CompletableDeferred<Unit>()
		val releaseA = CompletableDeferred<Unit>()
		var secondARan = false
		var bRan = false
		val firstA = async {
			scheduler.execute(providerA) {
				aStarted.complete(Unit)
				releaseA.await()
			}
		}
		aStarted.await()
		val secondA = async { scheduler.execute(providerA) { secondARan = true } }
		val callB = async { scheduler.execute(providerB) { bRan = true } }
		callB.await()

		assertTrue(bRan)
		assertFalse(secondARan)
		releaseA.complete(Unit)
		firstA.await()
		secondA.await()
		assertTrue(secondARan)
	}

	private fun coordinator(
		engine: NovelTranslationEngine,
		sleep: suspend (Long) -> Unit = {},
	): NovelTranslationCoordinator {
		val capabilities = capabilities()
		return NovelTranslationCoordinator(
			providerResolver = NovelTranslationProviderResolver { NovelTranslationProvider(engine, capabilities) },
			scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {}),
			retryPolicy = NovelTranslationRetryPolicy(nowMillis = { 0L }, jitterMillis = { 0L }),
			sleep = sleep,
		)
	}

	private fun capabilities(maxConcurrency: Int = 1) = NovelTranslationProviderCapabilities(
		providerId = "fake",
		maxBatchChars = 256,
		maxSingleRequestChars = 256,
		credentialRequired = false,
		maxConcurrency = maxConcurrency,
		minRequestGapMillis = 0L,
		maxAttempts = 3,
		initialBackoffMillis = 1L,
		maxRetryDelayMillis = 5_000L,
	)

	private fun operation(text: String, contextAware: Boolean = false) = NovelTranslationOperationRequest(
		text = text,
		selection = NovelTranslationSelection(
			engine = NovelTranslationEngineKind.ONLINE,
			sourceLanguage = "auto",
			targetLanguage = "id",
			style = NovelTranslationStyle.NATURAL,
			contextAware = contextAware,
		),
	)

	private fun engine(block: suspend (NovelTranslationRequest) -> String) = object : NovelTranslationEngine {
		override suspend fun translate(request: NovelTranslationRequest): String = block(request)
	}
}
