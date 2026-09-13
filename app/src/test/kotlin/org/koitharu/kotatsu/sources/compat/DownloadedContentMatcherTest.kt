package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedContentMatcherTest {

	@Test
	fun `exact id remains strongest match`() {
		val result = DownloadedContentMatcher.classify(
			remoteId = 42,
			downloadedId = 42,
			remotePublicUrl = "https://new.example/manga",
			downloadedPublicUrl = "https://old.example/manga",
			remoteCanonicalSource = CanonicalSourceId("provider:new"),
			downloadedCanonicalSource = CanonicalSourceId("provider:old"),
			remoteContentUrl = "/new",
			downloadedContentUrl = "/old",
		)
		assertEquals(DownloadedContentMatch.EXACT_ID, result)
	}

	@Test
	fun `public url reconnects across provider ids and harmless url formatting`() {
		val result = DownloadedContentMatcher.classify(
			remoteId = 1,
			downloadedId = 2,
			remotePublicUrl = "HTTPS://Example.COM:443/title/abc/#reader",
			downloadedPublicUrl = "https://example.com/title/abc",
			remoteCanonicalSource = CanonicalSourceId("provider:new"),
			downloadedCanonicalSource = CanonicalSourceId("provider:old"),
			remoteContentUrl = "/new-id",
			downloadedContentUrl = "/old-id",
		)
		assertEquals(DownloadedContentMatch.PUBLIC_URL, result)
	}

	@Test
	fun `verified source alias and same content url reconnect`() {
		val canonical = CanonicalSourceId("catalogue:123")
		val result = DownloadedContentMatcher.classify(
			remoteId = 1,
			downloadedId = 2,
			remotePublicUrl = null,
			downloadedPublicUrl = null,
			remoteCanonicalSource = canonical,
			downloadedCanonicalSource = canonical,
			remoteContentUrl = "/manga/abc/",
			downloadedContentUrl = "/manga/abc",
		)
		assertEquals(DownloadedContentMatch.SOURCE_ALIAS_AND_CONTENT_URL, result)
	}

	@Test
	fun `same content url under unrelated providers never auto reconnects`() {
		val result = DownloadedContentMatcher.classify(
			remoteId = 1,
			downloadedId = 2,
			remotePublicUrl = null,
			downloadedPublicUrl = null,
			remoteCanonicalSource = CanonicalSourceId("provider:one"),
			downloadedCanonicalSource = CanonicalSourceId("provider:two"),
			remoteContentUrl = "/manga/abc",
			downloadedContentUrl = "/manga/abc",
		)
		assertEquals(DownloadedContentMatch.NONE, result)
	}

	@Test
	fun `query is retained because it may be the content key`() {
		val first = DownloadedContentMatcher.normalizePublicUrl("https://example.com/read?id=1")
		val second = DownloadedContentMatcher.normalizePublicUrl("https://example.com/read?id=2")
		assertEquals(false, first == second)
	}
}
