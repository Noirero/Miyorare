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
}
