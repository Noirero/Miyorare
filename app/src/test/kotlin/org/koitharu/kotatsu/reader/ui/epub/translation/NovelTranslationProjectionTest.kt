package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTranslationProjectionTest {

	private val original = "Hello world!"
	private val segments = listOf(
		NovelTranslationDisplaySegment(0, 5, "Halo"),
		NovelTranslationDisplaySegment(6, 11, "dunia"),
	)

	@Test
	fun `original projection is canonical text with identity mapping`() {
		assertEquals(original, NovelTranslationProjection.displayText(original, segments, NovelTranslationDisplayMode.ORIGINAL))
		assertEquals(7, NovelTranslationProjection.sourceToDisplayOffset(original.length, segments, NovelTranslationDisplayMode.ORIGINAL, 7))
		assertEquals(7, NovelTranslationProjection.displayToSourceOffset(original.length, segments, NovelTranslationDisplayMode.ORIGINAL, 7))
	}

	@Test
	fun `bilingual projection inserts derived text without changing source mapping`() {
		val mode = NovelTranslationDisplayMode.BILINGUAL
		assertEquals("Hello\nHalo\n world\ndunia\n!", NovelTranslationProjection.displayText(original, segments, mode))
		assertEquals(5, NovelTranslationProjection.sourceToDisplayOffset(original.length, segments, mode, 5))
		assertEquals(11, NovelTranslationProjection.sourceToDisplayOffset(original.length, segments, mode, 5, afterBoundary = true))
		assertEquals(5, NovelTranslationProjection.displayToSourceOffset(original.length, segments, mode, 8))
		assertTrue(NovelTranslationProjection.displayRangeTouchesUnmappedTranslation(original.length, segments, mode, 6, 10))
		assertEquals(
			listOf(0 to 5, 11 to 17),
			NovelTranslationProjection.sourceRangeToDisplaySegments(original.length, segments, mode, 0, 11),
		)
	}

	@Test
	fun `translated only projection replaces source ranges and maps variable lengths`() {
		val mode = NovelTranslationDisplayMode.TRANSLATED_ONLY
		assertEquals("Halo dunia!", NovelTranslationProjection.displayText(original, segments, mode))
		assertEquals(4, NovelTranslationProjection.sourceToDisplayOffset(original.length, segments, mode, 5))
		assertEquals(5, NovelTranslationProjection.sourceToDisplayOffset(original.length, segments, mode, 6))
		assertEquals(5, NovelTranslationProjection.displayToSourceOffset(original.length, segments, mode, 4))
		assertEquals(6, NovelTranslationProjection.displayToSourceOffset(original.length, segments, mode, 5))
		assertFalse(NovelTranslationProjection.displayRangeTouchesUnmappedTranslation(original.length, segments, mode, 0, 4))
	}

	@Test
	fun `translated only source boundaries remain stable across shorter and longer replacements`() {
		val mode = NovelTranslationDisplayMode.TRANSLATED_ONLY
		val variable = listOf(
			NovelTranslationDisplaySegment(0, 2, "A"),
			NovelTranslationDisplaySegment(2, 4, "LONG"),
		)
		val source = "abcd"
		assertEquals("ALONG", NovelTranslationProjection.displayText(source, variable, mode))
		assertEquals(0, NovelTranslationProjection.sourceToDisplayOffset(source.length, variable, mode, 0))
		assertEquals(1, NovelTranslationProjection.sourceToDisplayOffset(source.length, variable, mode, 2))
		assertEquals(5, NovelTranslationProjection.sourceToDisplayOffset(source.length, variable, mode, 4))
		assertEquals(0, NovelTranslationProjection.displayToSourceOffset(source.length, variable, mode, 0))
		assertEquals(2, NovelTranslationProjection.displayToSourceOffset(source.length, variable, mode, 1))
		assertEquals(4, NovelTranslationProjection.displayToSourceOffset(source.length, variable, mode, 5))
	}

	@Test
	fun `invalid or overlapping display segments cannot escape source bounds`() {
		val mode = NovelTranslationDisplayMode.TRANSLATED_ONLY
		val malformed = listOf(
			NovelTranslationDisplaySegment(-5, 2, "one"),
			NovelTranslationDisplaySegment(1, 100, "two"),
		)
		val display = NovelTranslationProjection.displayText("abcd", malformed, mode)
		assertTrue(display.isNotEmpty())
		assertTrue(NovelTranslationProjection.sourceToDisplayOffset(4, malformed, mode, 100) in 0..display.length)
		assertTrue(NovelTranslationProjection.displayToSourceOffset(4, malformed, mode, 100) in 0..4)
	}
}
