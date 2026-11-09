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
	fun `bilingual pairs expose exact half-open source and translation ranges without new text`() {
		val mode = NovelTranslationDisplayMode.BILINGUAL
		val text = NovelTranslationProjection.displayText(original, segments, mode)
		val pairs = NovelTranslationProjection.bilingualPairRanges(original.length, segments)
		assertEquals(2, pairs.size)
		assertEquals("Hello", text.substring(pairs[0].originalStart, pairs[0].originalEnd))
		assertEquals("Halo", text.substring(pairs[0].translationStart, pairs[0].translationEnd))
		assertEquals("world", text.substring(pairs[1].originalStart, pairs[1].originalEnd))
		assertEquals("dunia", text.substring(pairs[1].translationStart, pairs[1].translationEnd))
		assertEquals(5, NovelTranslationProjection.displayToSourceOffset(original.length, segments, mode, pairs[0].translationStart))
		assertEquals(11, NovelTranslationProjection.sourceToDisplayOffset(original.length, segments, mode, 5, afterBoundary = true))
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
	fun `translated only preserves whitespace between adjacent translated chunks`() {
		val source = "alpha\n\nbeta"
		val chunks = listOf(
			NovelTranslationDisplaySegment(0, 7, "satu\n\n"),
			NovelTranslationDisplaySegment(7, 11, "dua"),
		)

		assertEquals(
			"satu\n\ndua",
			NovelTranslationProjection.displayText(source, chunks, NovelTranslationDisplayMode.TRANSLATED_ONLY),
		)
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
