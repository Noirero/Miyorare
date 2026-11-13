package org.koitharu.kotatsu.discovery

import org.koitharu.kotatsu.tsuki.model.*

internal fun readingSourceFixture(
	provider: TsukiPluginProvider,
	descriptor: TsukiSourceDescriptor = TsukiSourceDescriptor("fixture-source", "Same catalogue", "en", "MANGA"),
): TsukiMangaSource {
	val plugin = TsukiPluginDescriptor(
		pluginId = "fixture", displayName = "Fixture", provider = provider, version = "1",
		origin = "test", sha256 = "", fileSize = 0L, lastModified = 0L, requiredApi = null,
		compatibility = TsukiCompatibilityStatus.COMPATIBLE, state = TsukiPluginState.ENABLED,
		sources = listOf(descriptor), enabledSourceNames = setOf(descriptor.name),
	)
	return TsukiMangaSource(plugin, descriptor)
}
