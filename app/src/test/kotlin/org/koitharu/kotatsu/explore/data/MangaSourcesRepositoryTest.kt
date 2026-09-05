package org.koitharu.kotatsu.explore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource

class MangaSourcesRepositoryTest {

	@Test
	fun `cold start wrapper keeps exact Mihon source id`() {
		assertEquals(123L, storedMihonSourceId(MissingMangaSource("MIHON_123")))
		assertEquals(456L, storedMihonSourceId(MissingMangaSource("MIHON_456:Legacy title")))
	}

	@Test
	fun `malformed and non Mihon wrappers do not resolve as Mihon source ids`() {
		assertNull(storedMihonSourceId(MissingMangaSource("MIHON_bad")))
		assertNull(storedMihonSourceId(MissingMangaSource("LN_demo")))
		assertNull(storedMihonSourceId(UnknownMangaSource))
	}
}
