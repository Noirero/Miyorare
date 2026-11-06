package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelTranslationPageScopeTest {

    @Test
    fun pageScopeExpandsOnlyToWordBoundaries() {
        val source = "The first page continues to another page."
        assertEquals(4..13, NovelTranslationPageScope.expandToWordBoundaries(source, 6..10))
    }

    @Test
    fun pageScopeBoundsAreClampedAndEmptySourceIsRejected() {
        assertNull(NovelTranslationPageScope.expandToWordBoundaries("", 0..1))
        assertEquals(0..2, NovelTranslationPageScope.expandToWordBoundaries("Hi!", -30..300))
    }

    @Test
    fun alreadyTranslatedRangesAreNotSubmittedAgain() {
        assertEquals(
            listOf(10..19, 30..39, 60..69),
            NovelTranslationPageScope.uncovered(10..69, listOf(20..29, 40..59)),
        )
    }

    @Test
    fun overlappingAndOutOfOrderRangesDoNotLoseUntranslatedText() {
        assertEquals(
            listOf(5..9, 35..39),
            NovelTranslationPageScope.uncovered(5..39, listOf(20..34, 10..25, 0..3)),
        )
        assertEquals(emptyList<IntRange>(), NovelTranslationPageScope.uncovered(5..10, listOf(0..20)))
    }

    @Test
    fun translatedRangesOutsidePageAreIgnored() {
        assertEquals(
            listOf(5..10),
            NovelTranslationPageScope.uncovered(5..10, listOf(11..30, 0..4)),
        )
    }
}
