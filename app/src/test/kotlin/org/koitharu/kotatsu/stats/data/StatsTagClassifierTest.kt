package org.koitharu.kotatsu.stats.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.db.entity.TagEntity

class StatsTagClassifierTest {

	@Test
	fun `technical namespaced tags never become genres`() {
		assertNull(StatsTagClassifier.genreLabel(tag("female:big breasts")))
		assertNull(StatsTagClassifier.genreLabel(tag("other:ai generated")))
		assertNull(StatsTagClassifier.genreLabel(tag("artist:someone")))
	}

	@Test
	fun `genre namespaces become readable labels`() {
		assertEquals("Action", StatsTagClassifier.genreLabel(tag("genre:action")))
		assertEquals("Slice Of Life", StatsTagClassifier.genreLabel(tag("theme:slice_of_life")))
		assertEquals("Romance", StatsTagClassifier.genreLabel(tag("Romance")))
	}

	@Test
	fun `explicit namespaces are privacy mature signals`() {
		assertTrue(StatsTagClassifier.isMatureTag(tag("female:glasses")))
		assertTrue(StatsTagClassifier.isMatureTag(tag("nsfw")))
	}

	@Test
	fun `format classifier recognizes explicit format metadata`() {
		assertEquals("Manhwa", StatsTagClassifier.formatLabel(false, listOf(tag("format:manhwa"))))
		assertEquals("Webtoon", StatsTagClassifier.formatLabel(false, listOf(tag("webtoon"))))
		assertEquals("Light Novel", StatsTagClassifier.formatLabel(true, listOf(tag("light_novel"))))
	}

	private fun tag(value: String) = TagEntity(
		id = value.hashCode().toLong(),
		title = value,
		key = value,
		source = "TEST",
		isPinned = false,
	)
}
