package org.koitharu.kotatsu.reader.ui.epub.translation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelOnlineTranslationStabilityTest {

	@Test
	fun `online provider starts with conservative request pacing`() {
		assertEquals(1, MIYORARE_ONLINE_MAX_CONCURRENCY)
		assertTrue(MIYORARE_ONLINE_REQUEST_GAP_MS >= 750L)
	}

	@Test
	fun `online forbidden response is treated as retryable provider pressure`() {
		val failure = onlineTranslationFailure(403, retryAfter = null).toNovelTranslationFailure()
		val capabilities = capabilities()
		val retryPolicy = NovelTranslationRetryPolicy(
			nowMillis = { 0L },
			jitterMillis = { 0L },
		)

		assertEquals(NovelTranslationFailureCategory.PROVIDER_UNAVAILABLE, failure.category)
		assertEquals(403, failure.statusCode)
		assertNotNull(retryPolicy.retryDelayMillis(failure, failedAttempt = 1, capabilities = capabilities))
	}

	@Test
	fun `online chapter translation stays source ordered and publishes atomically`() = runTest {
		val calls = mutableListOf<String>()
		val published = mutableListOf<Int>()
		val provider = NovelTranslationProvider(
			engine = object : NovelTranslationEngine {
				override suspend fun translate(request: NovelTranslationRequest): String {
					calls += request.text.take(1)
					return request.text
				}
			},
			capabilities = capabilities(),
		)
		val coordinator = NovelTranslationCoordinator(
			providerResolver = NovelTranslationProviderResolver { provider },
			scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {}),
			retryPolicy = NovelTranslationRetryPolicy(nowMillis = { 0L }, jitterMillis = { 0L }),
			sleep = {},
		)
		val text = "A".repeat(256) + "B".repeat(256) + "C".repeat(256)
		val result = coordinator.translate(
			request = NovelTranslationOperationRequest(
				text = text,
				selection = NovelTranslationSelection(
					engine = NovelTranslationEngineKind.ONLINE,
					sourceLanguage = NovelTranslationSettings.LANGUAGE_AUTO,
					targetLanguage = "id",
					style = NovelTranslationStyle.NATURAL,
					contextAware = false,
				),
				priorityController = NovelTranslationPriorityController(512..767),
			),
			onSegmentTranslated = { segment, _, _ -> published += segment.source.start },
		)

		assertEquals(listOf("A", "B", "C"), calls)
		assertTrue(published.isEmpty())
		assertEquals(text, result.translatedText)
	}

	private fun capabilities() = NovelTranslationProviderCapabilities(
		providerId = MIYORARE_ONLINE_PROVIDER_ID,
		maxBatchChars = 256,
		maxSingleRequestChars = 256,
		credentialRequired = false,
		maxConcurrency = MIYORARE_ONLINE_MAX_CONCURRENCY,
		minRequestGapMillis = MIYORARE_ONLINE_REQUEST_GAP_MS,
		maxAttempts = 3,
		initialBackoffMillis = 750L,
		maxRetryDelayMillis = 5_000L,
	)
}
