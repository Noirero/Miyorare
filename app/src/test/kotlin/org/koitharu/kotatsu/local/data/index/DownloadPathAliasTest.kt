package org.koitharu.kotatsu.local.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadPathAliasTest {

	@Test
	fun `alias round trips paths containing separator characters`() {
		val alias = DownloadPathAlias(
			localMangaId = 42L,
			path = "/storage/emulated/0/Miyorare/downloads/Source/A|B",
		)
		assertEquals(alias, DownloadPathAlias.parse(alias.serialize()))
	}

	@Test
	fun `malformed aliases are rejected`() {
		assertNull(DownloadPathAlias.parse(null))
		assertNull(DownloadPathAlias.parse(""))
		assertNull(DownloadPathAlias.parse("abc|/path"))
		assertNull(DownloadPathAlias.parse("42|"))
	}

    @Test
    fun `migration redirects round trip without changing legacy path escaping`() {
        val alias = DownloadPathAlias(-42, "/download/A|B#100%", mapOf(-1L to 101L, 2L to -202L))
        assertEquals(alias, DownloadPathAlias.parse(alias.serialize()))
        assertEquals("42|/path", DownloadPathAlias(42, "/path").serialize())
        assertEquals(emptyMap<Long, Long>(), DownloadPathAlias.parse("42|/path")?.chapterIds)
    }

    @Test
    fun `second migration composes only previously approved physical chapter ids`() {
        val original = DownloadPathAlias(42, "/path", mapOf(1L to 10L, 2L to 20L))
        val next = original.forMigration(mapOf(10L to 100L, 30L to 300L))
        assertEquals(mapOf(1L to 100L, 2L to 20L), next.chapterIds)
        assertEquals(true, original.sameContainer(next))
        assertEquals(mapOf(1L to 10L, 2L to 20L), original.chapterIds)
    }

    @Test
    fun `corrupt or duplicate migration chapter redirects are rejected`() {
        for (raw in listOf("m1:42:1>x|/path", "m1:42:1>2,1>3|/path", "m1:42:|/path", "m2:42:1>2|/path")) {
            assertNull(DownloadPathAlias.parse(raw))
        }
    }
}
