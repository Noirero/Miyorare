package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EhentaiSourceFamilyTest {

	@Test
	fun `all verified language source ids share the official canonical family`() {
		val official = EhentaiSourceFamily.canonicalize(
			StoredSourceIdentity.direct(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME),
		)
		assertEquals(EhentaiSourceFamily.CANONICAL_ID, official?.canonicalId)
		assertFalse(official?.isAlias ?: true)

		val ids = EhentaiSourceFamily.languagePresets.mapNotNull { it.mihonSourceId }
		assertEquals(17, ids.size)
		assertEquals(ids.size, ids.toSet().size)
		for (sourceId in ids) {
			val identity = EhentaiSourceFamily.canonicalize(
				StoredSourceIdentity.direct("MIHON_$sourceId:E-Hentai"),
			)
			assertEquals(EhentaiSourceFamily.CANONICAL_ID, identity?.canonicalId)
			assertTrue(identity?.isAlias == true)
		}
	}

	@Test
	fun `language presets include all none other and every legacy folder`() {
		val codes = EhentaiSourceFamily.languagePresets.map { it.code }.toSet()
		assertTrue("all" in codes)
		assertTrue("none" in codes)
		assertTrue("other" in codes)
		assertTrue("pt-BR" in codes)
		assertTrue("E-Hentai (ALL)" in EhentaiSourceFamily.legacySourceDirectoryNames)
		assertTrue("E-Hentai (JA)" in EhentaiSourceFamily.legacySourceDirectoryNames)
		assertTrue("E-Hentai (PT-BR)" in EhentaiSourceFamily.legacySourceDirectoryNames)
		assertTrue("E-Hentai (OTHER)" in EhentaiSourceFamily.legacySourceDirectoryNames)
	}

	@Test
	fun `gallery id is stable across ehentai exhentai pagination and token differences`() {
		assertEquals(
			123456L,
			EhentaiSourceFamily.galleryId("https://e-hentai.org/g/123456/abc123/"),
		)
		assertEquals(
			123456L,
			EhentaiSourceFamily.galleryId("https://exhentai.org/g/123456/other-token/?p=2#reader"),
		)
		assertEquals(123456L, EhentaiSourceFamily.galleryId("/g/123456/abc123/?p=1"))
		assertNull(EhentaiSourceFamily.galleryId("https://example.org/g/123456/abc123/"))
	}

	@Test
	fun `unrelated mihon source does not enter ehentai family`() {
		assertNull(
			EhentaiSourceFamily.canonicalize(
				StoredSourceIdentity.direct("MIHON_999999:Example"),
			),
		)
	}
}
