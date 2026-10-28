package org.koitharu.kotatsu.tsuki.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider

class OfficialGlobalSourceResilienceTest {

	@Test
	fun onlyFourOrdinaryGlobalSourcesAreSupported() {
		val expected = setOf("COMICASURA", "GELBOORU", "RAWDEX", "NEWXTOON")
		assertEquals(expected, OfficialGlobalSourceResilience.supportedSourceNames)
		expected.forEach { sourceName ->
			assertTrue(
				OfficialGlobalSourceResilience.supports(
					TsukiPluginProvider.MIYORARE,
					OfficialGlobalSourceResilience.GLOBAL_PLUGIN_ID,
					sourceName,
				),
			)
		}
	}

	@Test
	fun exhentaiIsExplicitlyExcluded() {
		assertFalse(
			OfficialGlobalSourceResilience.supports(
				TsukiPluginProvider.MIYORARE,
				OfficialGlobalSourceResilience.GLOBAL_PLUGIN_ID,
				"EXHENTAI",
			),
		)
	}

	@Test
	fun otherPacksAndProvidersAreExcluded() {
		assertFalse(
			OfficialGlobalSourceResilience.supports(
				TsukiPluginProvider.MIYORARE,
				"miyorare-en",
				"COMICASURA",
			),
		)
		assertFalse(
			OfficialGlobalSourceResilience.supports(
				TsukiPluginProvider.GEKKOUSHI,
				OfficialGlobalSourceResilience.GLOBAL_PLUGIN_ID,
				"COMICASURA",
			),
		)
	}

	@Test
	fun normalizesBareHostAndRootHttpsUrl() {
		assertEquals("example.org", OfficialGlobalSourceResilience.normalizeDomainInput(" example.org "))
		assertEquals("example.org", OfficialGlobalSourceResilience.normalizeDomainInput("https://Example.org/"))
		assertEquals("example.org:8443", OfficialGlobalSourceResilience.normalizeDomainInput("https://example.org:8443/"))
	}

	@Test
	fun rejectsUnsafeOrLossyCustomUrlForms() {
		assertNull(OfficialGlobalSourceResilience.normalizeDomainInput("http://example.org"))
		assertNull(OfficialGlobalSourceResilience.normalizeDomainInput("https://user:pass@example.org/"))
		assertNull(OfficialGlobalSourceResilience.normalizeDomainInput("https://example.org/path"))
		assertNull(OfficialGlobalSourceResilience.normalizeDomainInput("https://example.org/?q=1"))
		assertNull(OfficialGlobalSourceResilience.normalizeDomainInput("not a host"))
	}

	@Test
	fun classifiesDefaultMirrorAndCustomDomains() {
		val presets = listOf("main.example", "mirror.example")
		assertEquals(
			OfficialGlobalDomainMode.DEFAULT,
			OfficialGlobalSourceResilience.domainMode("main.example", presets),
		)
		assertEquals(
			OfficialGlobalDomainMode.TRUSTED_MIRROR,
			OfficialGlobalSourceResilience.domainMode("mirror.example", presets),
		)
		assertEquals(
			OfficialGlobalDomainMode.CUSTOM,
			OfficialGlobalSourceResilience.domainMode("custom.example", presets),
		)
	}
}