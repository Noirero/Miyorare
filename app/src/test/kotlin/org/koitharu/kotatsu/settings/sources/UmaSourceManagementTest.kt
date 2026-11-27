package org.koitharu.kotatsu.settings.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.tsuki.model.TsukiCompatibilityStatus
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor

class UmaSourceManagementTest {

	@Test
	fun languageFilterIsScopedToUmaAndKeepsUnknownDiscoverable() {
		assertTrue(matchesUmaLanguage(TsukiPluginProvider.UMA, " ID ", "id"))
		assertFalse(matchesUmaLanguage(TsukiPluginProvider.UMA, "en", "id"))
		assertTrue(matchesUmaLanguage(TsukiPluginProvider.UMA, "", ""))
		assertTrue(matchesUmaLanguage(TsukiPluginProvider.UMA, "ja", null))
		assertTrue(matchesUmaLanguage(TsukiPluginProvider.GEKKOUSHI, "en", "id"))
		assertTrue(matchesUmaLanguage(TsukiPluginProvider.CUSTOM, "en", "id"))
	}

	@Test
	fun websiteIconIsHttpsOriginOnlyAndRejectsCredentials() {
		assertEquals("https://shelf.example/favicon.ico", umaWebsiteIconUrl("shelf.example"))
		assertEquals("https://shelf.example/favicon.ico", umaWebsiteIconUrl("https://shelf.example/reader"))
		assertNull(umaWebsiteIconUrl("http://shelf.example"))
		assertNull(umaWebsiteIconUrl("file:///tmp/source"))
		assertNull(umaWebsiteIconUrl("https://user:secret@shelf.example"))
		assertNull(umaWebsiteIconUrl(""))
		assertNull(umaWebsiteIconUrl("https://127.0.0.1"))
		assertNull(umaWebsiteIconUrl("https://[::1]"))
		assertNull(umaWebsiteIconUrl("https://localhost"))
		assertNull(umaWebsiteIconUrl("https://device.local"))
	}
	@Test
	fun navigationRequiresUsablePluginButNotEnabledSourceAndDiagnosticAllowsBroken() {
		val source = TsukiSourceDescriptor("FIXTURE", "Fixture", "en", "MANGA")
		val plugin = TsukiPluginDescriptor(
			pluginId = "fixture", displayName = "Fixture", provider = TsukiPluginProvider.UMA,
			version = "1", origin = "", sha256 = "fixture", fileSize = 0, lastModified = 0,
			requiredApi = null, compatibility = TsukiCompatibilityStatus.COMPATIBLE,
			state = TsukiPluginState.ENABLED, sources = listOf(source), enabledSourceNames = emptySet(),
		)
		assertTrue(canNavigateTsukiSource(plugin, source))
		assertFalse(canNavigateTsukiSource(plugin.copy(state = TsukiPluginState.DISABLED), source))
		assertFalse(canNavigateTsukiSource(plugin.copy(compatibility = TsukiCompatibilityStatus.INCOMPATIBLE), source))
		val broken = source.copy(isBroken = true)
		assertFalse(canNavigateTsukiSource(plugin, broken))
		assertTrue(canNavigateTsukiSource(plugin, broken, allowBroken = true))
		assertFalse(canNavigateTsukiSource(plugin.copy(state = TsukiPluginState.BROKEN), broken, allowBroken = true))
	}
}
