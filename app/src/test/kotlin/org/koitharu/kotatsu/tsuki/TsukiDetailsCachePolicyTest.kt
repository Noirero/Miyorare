package org.koitharu.kotatsu.tsuki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider

class TsukiDetailsCachePolicyTest {

	@Test
	fun officialMiyorareRejectsEmptyDetailsCache() {
		assertFalse(
			isTsukiDetailsCacheUsable(
				provider = TsukiPluginProvider.MIYORARE,
				sourceName = "TSUKI:MIYORARE:miyorare-id:TEST_SOURCE",
				requestedHasChapters = false,
				cachedHasChapters = false,
			),
		)
	}

	@Test
	fun officialMiyorareAcceptsChapterBearingDetailsCache() {
		assertTrue(
			isTsukiDetailsCacheUsable(
				provider = TsukiPluginProvider.MIYORARE,
				sourceName = "TSUKI:MIYORARE:miyorare-en:TEST_SOURCE",
				requestedHasChapters = false,
				cachedHasChapters = true,
			),
		)
	}

	@Test
	fun unrelatedThirdPartyTsukiKeepsExistingCacheFirstBehaviour() {
		assertTrue(
			isTsukiDetailsCacheUsable(
				provider = TsukiPluginProvider.UMA,
				sourceName = "TSUKI:UMA:third-party:TEST_SOURCE",
				requestedHasChapters = false,
				cachedHasChapters = false,
			),
		)
	}

	@Test
	fun officialExhentaiStillRejectsEmptyDetailsCache() {
		assertFalse(
			isTsukiDetailsCacheUsable(
				provider = TsukiPluginProvider.CUSTOM,
				sourceName = EhentaiSourceFamily.OFFICIAL_SOURCE_NAME,
				requestedHasChapters = false,
				cachedHasChapters = false,
			),
		)
	}
}
