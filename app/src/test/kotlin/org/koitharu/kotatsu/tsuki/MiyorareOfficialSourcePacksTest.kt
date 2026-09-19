package org.koitharu.kotatsu.tsuki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiyorareOfficialSourcePacksTest {

	@Test
	fun `official packs are unique and define expected physical shards`() {
		val packs = MiyorareOfficialSourcePacks.packs
		assertEquals(3, packs.size)
		assertEquals(packs.size, packs.map { it.pluginId }.toSet().size)
		assertEquals(setOf("id", "en", "all"), packs.map { it.language }.toSet())
		assertEquals(2, packs.single { it.pluginId == MiyorareOfficialSourcePacks.ID_PLUGIN_ID }.shards.size)
		assertEquals(2, packs.single { it.pluginId == MiyorareOfficialSourcePacks.EN_PLUGIN_ID }.shards.size)
		assertEquals(1, packs.single { it.pluginId == MiyorareOfficialSourcePacks.GLOBAL_PLUGIN_ID }.shards.size)
		assertTrue(packs.flatMap { it.shards }.all { it.assetName.endsWith(".jar") })
		assertEquals(
			packs.sumOf { it.shards.size },
			packs.flatMap { it.shards }.map { it.assetName }.toSet().size,
		)
	}

	@Test
	fun `regional uma shards preserve logical plugin identity for legacy upgrade`() {
		val regional = MiyorareOfficialSourcePacks.packs.filter { it.language != "all" }
		for (pack in regional) {
			val uma = pack.shards.single { it.assetName.endsWith("-uma.jar") }
			val gekkoushi = pack.shards.single { it.assetName.endsWith("-gekkoushi.jar") }
			assertEquals(pack.pluginId, uma.pluginId)
			assertTrue(gekkoushi.pluginId.endsWith("-gekkoushi"))
			assertEquals(pack, MiyorareOfficialSourcePacks.findByInstalledPluginId(gekkoushi.pluginId))
		}
	}

	@Test
	fun `global pack has one canonical owner and no duplicate regional shard`() {
		val global = MiyorareOfficialSourcePacks.packs.single {
			it.pluginId == MiyorareOfficialSourcePacks.GLOBAL_PLUGIN_ID
		}
		assertEquals("all", global.language)
		assertEquals(1, global.shards.size)
		assertEquals(global.pluginId, global.shards.single().pluginId)
		assertTrue(global.shards.single().assetName.endsWith("-gekkoushi.jar"))
		assertEquals(global, MiyorareOfficialSourcePacks.findByInstalledPluginId(global.pluginId))
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
