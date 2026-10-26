package org.koitharu.kotatsu.reader.ui.epub.translation

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelTranslationReliabilityTest {

	@get:Rule
	val temporary = TemporaryFolder()

	@Test
	fun `persistent cache survives recreation and corruption becomes miss`() = runBlocking {
		val directory = temporary.newFolder()
		val key = key("persist")
		val first = NovelTranslationSemanticCache(directory)

		assertNull(first.read(key))
		assertTrue(first.write(key, "translated"))
		assertEquals("translated", NovelTranslationSemanticCache(directory).read(key))

		val entry = directory.listFiles().orEmpty().single { it.name.endsWith(".translation") }
		entry.writeText("corrupt")
		try {
			NovelTranslationSemanticCache(directory).read(key)
			fail("Expected cache corruption signal")
		} catch (_: NovelTranslationCacheCorruptionException) {
			// Corruption is surfaced internally so the coordinator can record a typed recovery event.
		}
		assertFalse(entry.exists())
	}

	@Test
	fun `persistent cache evicts least recently used entry when bounded`() = runBlocking {
		val directory = temporary.newFolder()
		var now = 10_000L
		val cache = NovelTranslationSemanticCache(
			directory = directory,
			maxBytes = Long.MAX_VALUE,
			maxEntries = 1,
			maxIdleMillis = 1_000_000L,
			nowMillis = { now },
		)
		val first = key("first")
		val second = key("second")

		assertTrue(cache.write(first, "one"))
		now += 10
		assertTrue(cache.write(second, "two"))

		assertNull(cache.read(first))
		assertEquals("two", cache.read(second))
		assertEquals(1, cache.entryCount())
	}

	@Test
	fun `semantic key invalidates on source and output affecting configuration`() {
		val baseProvider = provider(engine { it.text }, providerId = "provider-a", configuration = "model-a", contract = "prompt-v1")
		val baseSelection = selection()
		val base = semanticKey(baseProvider, baseSelection, "source", "before", "after")

		assertNotEquals(base, semanticKey(baseProvider, baseSelection, "changed", "before", "after"))
		assertNotEquals(base, semanticKey(baseProvider, baseSelection.copy(targetLanguage = "fr"), "source", "before", "after"))
		assertNotEquals(base, semanticKey(provider(engine { it.text }, "provider-b", "model-a", "prompt-v1"), baseSelection, "source", "before", "after"))
		assertNotEquals(base, semanticKey(provider(engine { it.text }, "provider-a", "model-b", "prompt-v1"), baseSelection, "source", "before", "after"))
		assertNotEquals(base, semanticKey(baseProvider, baseSelection.copy(style = NovelTranslationStyle.LITERAL), "source", "before", "after"))
		assertNotEquals(base, semanticKey(baseProvider, baseSelection.copy(contextAware = true), "source", "before", "after"))
		assertNotEquals(base, semanticKey(provider(engine { it.text }, "provider-a", "model-a", "prompt-v2"), baseSelection, "source", "before", "after"))
		val contextSelection = baseSelection.copy(contextAware = true)
		assertNotEquals(
			semanticKey(baseProvider, contextSelection, "source", "before-a", "after"),
			semanticKey(baseProvider, contextSelection, "source", "before-b", "after"),
		)
	}

	@Test
	fun `partial success persists and next coordinator requests only missing segments`() = runBlocking {
		val directory = temporary.newFolder()
		val firstCalls = mutableListOf<Char>()
		val firstCache = NovelTranslationSemanticCache(directory)
		val firstCoordinator = coordinator(
			engine = engine { request ->
				val marker = request.text.first()
				firstCalls += marker
				if (marker == 'b') throw NovelTranslationException.Authentication()
				"translated-$marker"
			},
			cache = firstCache,
			maxAttempts = 1,
		)
		val text = "a".repeat(256) + "b".repeat(256) + "c".repeat(100)

		try {
			firstCoordinator.translate(operation(text))
			fail("Expected partial failure")
		} catch (_: NovelTranslationFailure) {
			// Expected after one successful segment was persisted.
		}
		assertEquals(2, firstCalls.size)
		assertEquals(setOf('a', 'b'), firstCalls.toSet())
		assertEquals(1, firstCalls.count { it == 'a' })
		assertEquals(1, firstCalls.count { it == 'b' })
		assertEquals(1, firstCache.entryCount())

		val secondCalls = mutableListOf<Char>()
		val reopened = NovelTranslationSemanticCache(directory)
		val secondCoordinator = coordinator(
			engine = engine { request ->
				val marker = request.text.first()
				secondCalls += marker
				"translated-$marker"
			},
			cache = reopened,
			maxAttempts = 1,
		)
		val result = secondCoordinator.translate(operation(text))

		assertEquals(2, secondCalls.size)
		assertEquals(setOf('b', 'c'), secondCalls.toSet())
		assertEquals(1, secondCalls.count { it == 'b' })
		assertEquals(1, secondCalls.count { it == 'c' })
		assertEquals(listOf('a', 'b', 'c'), result.segments.map { it.source.text.first() })
		assertEquals(listOf("translated-a", "translated-b", "translated-c"), result.segments.map { it.translatedText })
		assertEquals(3, reopened.entryCount())
	}

	@Test
	fun `identical simultaneous work shares one provider execution`() = runBlocking {
		val calls = AtomicInteger()
		val started = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val sharedReliability = newServices()
		val coordinator = coordinator(
			engine = engine { request ->
				calls.incrementAndGet()
				started.complete(Unit)
				release.await()
				"translated:${request.text}"
			},
			reliabilityServices = sharedReliability,
		)

		val first = async { coordinator.translate(operation("same")) }
		val second = async { coordinator.translate(operation("same")) }
		started.await()
		yield()
		release.complete(Unit)

		assertEquals(first.await().translatedText, second.await().translatedText)
		assertEquals(1, calls.get())
	}

	@Test
	fun `non identical semantic work does not share single flight`() = runBlocking {
		val calls = AtomicInteger()
		val release = CompletableDeferred<Unit>()
		val bothStarted = CompletableDeferred<Unit>()
		val reliability = newServices()
		val coordinator = coordinator(
			engine = engine { request ->
				if (calls.incrementAndGet() == 2) bothStarted.complete(Unit)
				release.await()
				request.text
			},
			reliabilityServices = reliability,
		)

		val first = async { coordinator.translate(operation("first")) }
		val second = async { coordinator.translate(operation("second")) }
		bothStarted.await()
		release.complete(Unit)
		first.await()
		second.await()

		assertEquals(2, calls.get())
	}

	@Test
	fun `one single flight subscriber can cancel while another remains`() = runBlocking {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val singleFlight = NovelTranslationSingleFlight(scope)
		val sharedKey = key("shared")
		val started = CompletableDeferred<Unit>()
		val emitGate = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val secondObserved = CompletableDeferred<Unit>()
		val calls = AtomicInteger()

		val first = async {
			singleFlight.execute(sharedKey) { emit ->
				calls.incrementAndGet()
				started.complete(Unit)
				emitGate.await()
				emit(NovelTranslationFlightEvent.Attempt(1))
				release.await()
				"ok"
			}
		}
		started.await()
		val second = async {
			singleFlight.execute(
				key = sharedKey,
				onEvent = { if (it is NovelTranslationFlightEvent.Attempt) secondObserved.complete(Unit) },
			) { error("Second subscriber must not become producer") }
		}
		yield()
		emitGate.complete(Unit)
		secondObserved.await()
		first.cancelAndJoin()
		release.complete(Unit)

		assertEquals("ok", second.await())
		assertEquals(1, calls.get())
	}

	@Test
	fun `all single flight subscribers gone cancels provider work and future request is clean`() = runBlocking {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val singleFlight = NovelTranslationSingleFlight(scope)
		val sharedKey = key("cancel-all")
		val started = CompletableDeferred<Unit>()
		val cancelled = CompletableDeferred<Unit>()
		val calls = AtomicInteger()

		val first = async {
			singleFlight.execute(sharedKey) {
				calls.incrementAndGet()
				started.complete(Unit)
				try {
					awaitCancellation()
				} finally {
					cancelled.complete(Unit)
				}
			}
		}
		started.await()
		first.cancelAndJoin()
		cancelled.await()

		val fresh = singleFlight.execute(sharedKey) {
			calls.incrementAndGet()
			"fresh"
		}
		assertEquals("fresh", fresh)
		assertEquals(2, calls.get())
	}

	@Test
	fun `circuit opens only for repeated transient failures and recovers half open`() {
		var now = 1_000L
		val breaker = NovelTranslationCircuitBreaker(failureThreshold = 2, cooldownMillis = 100L, nowMillis = { now })
		val transient = NovelTranslationFailure(NovelTranslationFailureCategory.PROVIDER_UNAVAILABLE, message = "temporary")

		assertEquals(NovelTranslationCircuitState.CLOSED, breaker.beforeRequest("provider-a"))
		breaker.onFailure("provider-a", transient)
		assertEquals(NovelTranslationCircuitState.CLOSED, breaker.state("provider-a"))
		breaker.beforeRequest("provider-a")
		breaker.onFailure("provider-a", transient)
		assertEquals(NovelTranslationCircuitState.OPEN, breaker.state("provider-a"))
		assertCircuitRejected(breaker, "provider-a")
		assertEquals(NovelTranslationCircuitState.CLOSED, breaker.state("provider-b"))

		now += 100L
		assertEquals(NovelTranslationCircuitState.HALF_OPEN, breaker.beforeRequest("provider-a"))
		breaker.onSuccess("provider-a")
		assertEquals(NovelTranslationCircuitState.CLOSED, breaker.state("provider-a"))
	}

	@Test
	fun `authentication failure never enters provider cooldown`() {
		val breaker = NovelTranslationCircuitBreaker(failureThreshold = 1, cooldownMillis = 100L, nowMillis = { 1_000L })
		val auth = NovelTranslationFailure(NovelTranslationFailureCategory.AUTHENTICATION, message = "bad key")

		breaker.beforeRequest("provider")
		breaker.onFailure("provider", auth)

		assertEquals(NovelTranslationCircuitState.CLOSED, breaker.state("provider"))
		assertEquals(NovelTranslationCircuitState.CLOSED, breaker.beforeRequest("provider"))
	}

	@Test
	fun `diagnostics contain metadata but no source or translated text`() = runBlocking {
		val sourceSecret = "PRIVATE_SOURCE_SENTINEL"
		val translatedSecret = "PRIVATE_TRANSLATED_SENTINEL"
		val diagnostics = NovelTranslationDiagnostics()
		val reliability = newServices(diagnostics = diagnostics)
		val coordinator = coordinator(
			engine = engine { translatedSecret },
			reliabilityServices = reliability,
		)

		coordinator.translate(operation(sourceSecret))

		val dump = diagnostics.snapshot().joinToString("\n")
		assertFalse(dump.contains(sourceSecret))
		assertFalse(dump.contains(translatedSecret))
		assertTrue(dump.contains("provider-test"))
		assertTrue(diagnostics.snapshot().any { it.kind == NovelTranslationDiagnosticKind.SUCCESS })
	}

	@Test
	fun `corrupt cache is recovered as miss with typed diagnostic`() = runBlocking {
		val directory = temporary.newFolder()
		val diagnostics = NovelTranslationDiagnostics()
		val cache = NovelTranslationSemanticCache(directory)
		val reliability = newServices(cache = cache, diagnostics = diagnostics)
		coordinator(
			engine = engine { "first" },
			reliabilityServices = reliability,
			maxAttempts = 1,
		).translate(operation("source"))
		val entry = directory.listFiles().orEmpty().single { it.name.endsWith(".translation") }
		entry.writeText("corrupt")
		diagnostics.clear()
		var calls = 0

		val result = coordinator(
			engine = engine { calls++; "fresh" },
			reliabilityServices = reliability,
			maxAttempts = 1,
		).translate(operation("source"))

		assertEquals("fresh", result.translatedText)
		assertEquals(1, calls)
		assertTrue(diagnostics.snapshot().any { event ->
			event.kind == NovelTranslationDiagnosticKind.CACHE_CORRUPTION_RECOVERED &&
				event.errorCategory == NovelTranslationFailureCategory.CACHE_CORRUPTION
		})
	}

	@Test
	fun `cancelled half open probe releases provider for another probe`() {
		var now = 1_000L
		val breaker = NovelTranslationCircuitBreaker(failureThreshold = 1, cooldownMillis = 100L, nowMillis = { now })
		val transient = NovelTranslationFailure(NovelTranslationFailureCategory.PROVIDER_UNAVAILABLE, message = "temporary")

		assertEquals(NovelTranslationCircuitState.CLOSED, breaker.beforeRequest("provider"))
		breaker.onFailure("provider", transient)
		assertEquals(NovelTranslationCircuitState.OPEN, breaker.state("provider"))
		now += 100L
		assertEquals(NovelTranslationCircuitState.HALF_OPEN, breaker.beforeRequest("provider"))
		breaker.onCancellation("provider")
		assertEquals(NovelTranslationCircuitState.HALF_OPEN, breaker.state("provider"))
		assertEquals(NovelTranslationCircuitState.HALF_OPEN, breaker.beforeRequest("provider"))
	}

	private fun assertCircuitRejected(breaker: NovelTranslationCircuitBreaker, provider: String) {
		try {
			breaker.beforeRequest(provider)
			fail("Expected open circuit")
		} catch (error: NovelTranslationFailure) {
			assertEquals(NovelTranslationFailureCategory.CIRCUIT_OPEN, error.category)
		}
	}

	private fun semanticKey(
		provider: NovelTranslationProvider,
		selection: NovelTranslationSelection,
		text: String,
		before: String,
		after: String,
	): NovelTranslationSemanticKey = buildNovelTranslationSemanticKey(provider, selection, text, before, after)

	private fun key(value: String) = NovelTranslationSemanticKey(novelTranslationDigest(value))

	private fun coordinator(
		engine: NovelTranslationEngine,
		cache: NovelTranslationResultCache = NoOpNovelTranslationResultCache,
		reliabilityServices: NovelTranslationReliabilityServices? = null,
		maxAttempts: Int = 3,
	): NovelTranslationCoordinator {
		val provider = provider(engine, maxAttempts = maxAttempts)
		return NovelTranslationCoordinator(
			providerResolver = NovelTranslationProviderResolver { provider },
			scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {}),
			retryPolicy = NovelTranslationRetryPolicy(nowMillis = { 0L }, jitterMillis = { 0L }),
			sleep = {},
			reliability = reliabilityServices ?: newServices(cache = cache),
		)
	}

	private fun newServices(
		cache: NovelTranslationResultCache = NoOpNovelTranslationResultCache,
		diagnostics: NovelTranslationDiagnostics = NovelTranslationDiagnostics(),
	) = NovelTranslationReliabilityServices(
		cache = cache,
		singleFlight = NovelTranslationSingleFlight(CoroutineScope(SupervisorJob() + Dispatchers.Default)),
		circuitBreaker = NovelTranslationCircuitBreaker(failureThreshold = 10),
		diagnostics = diagnostics,
	)

	private fun provider(
		engine: NovelTranslationEngine,
		providerId: String = "provider-test",
		configuration: String = "config-v1",
		contract: String = "contract-v1",
		maxAttempts: Int = 3,
	) = NovelTranslationProvider(
		engine = engine,
		capabilities = NovelTranslationProviderCapabilities(
			providerId = providerId,
			maxBatchChars = 256,
			maxSingleRequestChars = 256,
			credentialRequired = false,
			maxConcurrency = 2,
			minRequestGapMillis = 0L,
			maxAttempts = maxAttempts,
			initialBackoffMillis = 1L,
			maxRetryDelayMillis = 5_000L,
		),
		semanticIdentity = NovelTranslationProviderSemanticIdentity(
			configurationFingerprint = novelTranslationDigest(configuration),
			contractVersion = contract,
			circuitKey = "$providerId:$configuration",
		),
	)

	private fun selection() = NovelTranslationSelection(
		engine = NovelTranslationEngineKind.ONLINE,
		sourceLanguage = "auto",
		targetLanguage = "id",
		style = NovelTranslationStyle.NATURAL,
		contextAware = false,
	)

	private fun operation(text: String) = NovelTranslationOperationRequest(text = text, selection = selection())

	private fun engine(block: suspend (NovelTranslationRequest) -> String) = object : NovelTranslationEngine {
		override suspend fun translate(request: NovelTranslationRequest): String = block(request)
	}
}
