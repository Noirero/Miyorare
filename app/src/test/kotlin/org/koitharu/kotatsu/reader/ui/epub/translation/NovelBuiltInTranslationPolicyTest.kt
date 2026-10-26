package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelBuiltInTranslationPolicyTest {

	@Test
	fun `built in provider is an on-device service with stable runtime identity`() {
		val provider = NovelAiProvider.BUILT_IN

		assertEquals("built_in", provider.id)
		assertEquals(NovelAiProtocol.ON_DEVICE, provider.protocol)
		assertEquals("on-device:mlkit-translate-17.0.3", provider.defaultBaseUrl)
		assertEquals("mlkit-translate-17.0.3", NovelTranslationSettings.BUILT_IN_RUNTIME_ID)
		assertFalse(provider.supportsModelDiscovery)
	}

	@Test
	fun `built in provider has a distinct auto translation boundary`() {
		assertEquals(
			"ai:built_in",
			NovelAutoTranslationPolicy.providerBinding(
				NovelTranslationEngineKind.AI,
				NovelAiProvider.BUILT_IN,
			),
		)
		assertEquals(
			"online",
			NovelAutoTranslationPolicy.providerBinding(
				NovelTranslationEngineKind.ONLINE,
				NovelAiProvider.BUILT_IN,
			),
		)
	}
}
