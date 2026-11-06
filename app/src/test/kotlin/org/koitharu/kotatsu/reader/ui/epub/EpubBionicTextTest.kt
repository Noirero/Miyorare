package org.koitharu.kotatsu.reader.ui.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubBionicTextTest {
    @Test
    fun pageLocalPrefixesMatchWholeChapterAtWordBoundaries() {
        val text = "Previously translated text. An especiallylongword continues onto a different page."
        val start = text.indexOf("especially") + 6
        val end = text.indexOf("different") + 3
        val expected = EpubBionicText.prefixRanges(text, 0, text.length)
            .mapNotNull { range ->
                val left = maxOf(range.first, start)
                val right = minOf(range.last + 1, end)
                if (left < right) (left - start) until (right - start) else null
            }
        assertEquals(expected, EpubBionicText.prefixRanges(text, start, end))
    }

    @Test
    fun longUnbrokenChapterDoesNotForceFullChapterRegexOnPageBind() {
        val text = "x".repeat(100_000) + " normal words " + "y".repeat(100_000)
        val start = text.indexOf("normal")
        val end = start + "normal words".length
        val window = EpubBionicText.contextWindow(text, start, end)
        assertTrue(window.endExclusive - window.start <= end - start + 256)
        assertEquals(2, EpubBionicText.prefixRanges(text, start, end).size)
    }

    @Test
    fun scriptsWithoutWordSpacesAreExcluded() {
        val text = "東京テスト English words"
        val from = text.indexOf("English")
        assertEquals(
            EpubBionicText.prefixRanges(text, from, text.length),
            EpubBionicText.prefixRanges(text, 0, text.length).mapNotNull { range ->
                val left = maxOf(range.first, from)
                val right = minOf(range.last + 1, text.length)
                if (left < right) (left - from) until (right - from) else null
            },
        )
    }

    @Test
    fun emptyAndOutOfBoundsWindowsAreSafe() {
        assertEquals(emptyList<IntRange>(), EpubBionicText.prefixRanges("", 0, 10))
        assertEquals(emptyList<IntRange>(), EpubBionicText.prefixRanges("hello", 4, 4))
        assertTrue(EpubBionicText.prefixRanges("hello", -100, 900).isNotEmpty())
    }
}
