package org.koitharu.kotatsu.reader.ui.epub.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelTranslationSemanticCacheSchemaTest {

	@get:Rule
	val temporary = TemporaryFolder()

	@Test
	fun `semantic cache v2 does not reuse legacy v1 entries`() = runBlocking {
		val directory = temporary.newFolder()
		val cache = NovelTranslationSemanticCache(directory)
		val provider = NovelTranslationProvider(
			engine = object : NovelTranslationEngine {
				override suspend fun translate(request: NovelTranslationRequest): String = request.text
			},
			capabilities = NovelTranslationProviderCapabilities(
				providerId = "provider-a",
				maxBatchChars = 256,
				maxSingleRequestChars = 256,
				credentialRequired = false,
			),
			semanticIdentity = NovelTranslationProviderSemanticIdentity(
				configurationFingerprint = novelTranslationDigest("model-a"),
				contractVersion = "prompt-v1",
				circuitKey = "provider-a:model-a",
			),
		)
		val selection = NovelTranslationSelection(
			engine = NovelTranslationEngineKind.ONLINE,
			sourceLanguage = "auto",
			targetLanguage = "id",
			style = NovelTranslationStyle.NATURAL,
			contextAware = false,
		)
		val segmentText = "source"
		val current = buildNovelTranslationSemanticKey(provider, selection, segmentText, "before", "after")

		fun keyForSchema(schema: String) = NovelTranslationSemanticKey(
			novelTranslationDigest(
				schema,
				provider.capabilities.providerId,
				provider.semanticIdentity.configurationFingerprint,
				provider.semanticIdentity.contractVersion,
				selection.sourceLanguage.trim().lowercase(),
				selection.targetLanguage.trim().lowercase(),
				selection.style.id,
				selection.contextAware.toString(),
				novelTranslationDigest(segmentText),
				"no-context",
				"no-context",
			),
		)

		val legacy = keyForSchema("novel-translation-semantic-v1")
		val expectedCurrent = keyForSchema("novel-translation-semantic-v2")

		assertEquals(expectedCurrent, current)
		assertNotEquals(legacy, current)
		assertTrue(cache.write(legacy, "legacy-result"))
		assertEquals("legacy-result", cache.read(legacy))
		assertNull(cache.read(current))
	}
}
