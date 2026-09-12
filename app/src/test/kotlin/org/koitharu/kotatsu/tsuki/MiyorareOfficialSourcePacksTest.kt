package org.koitharu.kotatsu.tsuki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiyorareOfficialSourcePacksTest {

	@Test
	fun `official packs are unique and use jar assets`() {
		val packs = MiyorareOfficialSourcePacks.packs
		assertEquals(2, packs.size)
		assertEquals(packs.size, packs.map { it.pluginId }.toSet().size)
		assertEquals(packs.size, packs.map { it.assetName }.toSet().size)
		assertTrue(packs.all { it.assetName.endsWith(".jar") })
		assertEquals(setOf("id", "en"), packs.map { it.language }.toSet())
	}

	@Test
	fun `only dedicated semantic release tags are accepted`() {
		assertEquals(SourcePackVersion(1, 2, 3), MiyorareOfficialSourcePacks.versionFromTag("miyorare-sources-v1.2.3"))
		assertNull(MiyorareOfficialSourcePacks.versionFromTag("v1.2.3"))
		assertNull(MiyorareOfficialSourcePacks.versionFromTag("miyorare-sources-v1.2"))
		assertNull(MiyorareOfficialSourcePacks.versionFromTag("miyorare-sources-v1.2.3-beta"))
	}

	@Test
	fun `newest source release is selected semantically`() {
		assertEquals(
			"miyorare-sources-v1.10.0",
			MiyorareOfficialSourcePacks.newestReleaseTag(
				listOf("miyorare-sources-v1.9.9", "v99.0.0", "miyorare-sources-v1.10.0"),
			),
		)
	}

	@Test
	fun `github sha256 digest must be complete hexadecimal`() {
		val digest = "a".repeat(64)
		assertEquals(digest, MiyorareOfficialSourcePacks.normalizeSha256Digest("sha256:$digest"))
		assertNull(MiyorareOfficialSourcePacks.normalizeSha256Digest("sha256:${"a".repeat(63)}"))
		assertNull(MiyorareOfficialSourcePacks.normalizeSha256Digest("md5:$digest"))
		assertNull(MiyorareOfficialSourcePacks.normalizeSha256Digest(null))
	}
}
