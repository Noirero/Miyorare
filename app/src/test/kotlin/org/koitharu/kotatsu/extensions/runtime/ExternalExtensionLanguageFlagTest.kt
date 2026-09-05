package org.koitharu.kotatsu.extensions.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalExtensionLanguageFlagTest {

	@Test
	fun `language codes use expected flags`() {
		assertEquals("🇮🇩", getExternalExtensionLanguageFlag("id"))
		assertEquals("🇨🇳", getExternalExtensionLanguageFlag("zh"))
		assertEquals("🇬🇧", getExternalExtensionLanguageFlag("en"))
		assertEquals("🇪🇸", getExternalExtensionLanguageFlag("eu"))
	}

	@Test
	fun `region overrides the language default`() {
		assertEquals("🇧🇷", getExternalExtensionLanguageFlag("pt-BR"))
		assertEquals("🇹🇼", getExternalExtensionLanguageFlag("zh_TW"))
		assertEquals("🇬🇧", getExternalExtensionLanguageFlag("en-UK"))
	}

	@Test
	fun `script fallback does not pretend the script is a country`() {
		assertEquals("🇨🇳", getExternalExtensionLanguageFlag("zh-Hans"))
		assertEquals("🇹🇼", getExternalExtensionLanguageFlag("zh-Hant"))
		assertEquals("🇵🇰", getExternalExtensionLanguageFlag("pa-Arab"))
	}

	@Test
	fun `language names and legacy spelling normalize to the same code`() {
		assertEquals("en", getExternalExtensionLangCode("English"))
		assertEquals("id", getExternalExtensionLangCode("Bahasa Indonesia"))
		assertEquals("pt-BR", getExternalExtensionLangCode("PT_br"))
		assertEquals("🇮🇩", getExternalExtensionLanguageFlag("Bahasa Indonesia"))
	}

	@Test
	fun `non-country groups and unknown values have safe neutral symbols`() {
		assertEquals("🌐", getExternalExtensionLanguageFlag("all"))
		assertEquals("🏳️", getExternalExtensionLanguageFlag("other"))
		assertEquals("🏳️", getExternalExtensionLanguageFlag(""))
		assertEquals("🌐", getExternalExtensionLanguageFlag("es-419"))
		assertEquals("🌐", getExternalExtensionLanguageFlag("x-future"))
	}
}
