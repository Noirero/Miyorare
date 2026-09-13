package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredSourceIdentityTest {

	@Test
	fun `mihon stored identity uses catalogue id instead of display name`() {
		val first = StoredSourceIdentity.direct("MIHON_123:Hitomi")
		val second = StoredSourceIdentity.direct("MIHON_123:Hitomi English")

		assertEquals(CanonicalSourceId("catalogue:123"), first.canonicalId)
		assertEquals(first.canonicalId, second.canonicalId)
		assertEquals(SourceBackend.MIHON, first.backend)
		assertEquals(123L, first.catalogueSourceId)
	}

	@Test
	fun `legacy mapping resolves to same catalogue identity without rewriting stored name`() {
		val legacy = StoredSourceIdentity.mappedLegacy(
			storedName = "MANGADEX",
			catalogueSourceId = 2499283573021220255L,
			sourceName = "MangaDex",
			packageName = "eu.kanade.tachiyomi.extension.all.mangadex",
		)
		val mihon = StoredSourceIdentity.direct("MIHON_2499283573021220255:MangaDex")

		assertEquals(mihon.canonicalId, legacy.canonicalId)
		assertEquals("MANGADEX", legacy.storedName)
		assertTrue(legacy.isAlias)
	}

	@Test
	fun `tsuki providers stay isolated until an explicit alias exists`() {
		val uma = StoredSourceIdentity.direct("TSUKI:UMA:uma:MangaDex")
		val custom = StoredSourceIdentity.direct("TSUKI:CUSTOM:other:MangaDex")

		assertEquals(SourceBackend.TSUKI, uma.backend)
		assertFalse(uma.canonicalId == custom.canonicalId)
	}

	@Test
	fun `official miyorare namespace is reserved independently of third party providers`() {
		val direct = StoredSourceIdentity.direct("MIYORARE:en:mangadex")
		val pack = StoredSourceIdentity.direct("TSUKI:MIYORARE:miyorare-en:MangaDex")
		val thirdParty = StoredSourceIdentity.direct("TSUKI:UMA:uma:MangaDex")

		assertEquals(SourceBackend.MIYORARE, direct.backend)
		assertEquals(CanonicalSourceId("miyorare:en:mangadex"), direct.canonicalId)
		assertEquals(SourceBackend.MIYORARE, pack.backend)
		assertEquals(CanonicalSourceId("miyorare:miyorare-en:MangaDex"), pack.canonicalId)
		assertFalse(pack.canonicalId == thirdParty.canonicalId)
	}

	@Test
	fun `internal sources never collide with provider sources`() {
		assertEquals(CanonicalSourceId("internal:local"), StoredSourceIdentity.direct("LOCAL").canonicalId)
		assertEquals(CanonicalSourceId("internal:unknown"), StoredSourceIdentity.direct("UNKNOWN").canonicalId)
	}
}