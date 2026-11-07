package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.details.data.ChapterPersonalKey

class IndependentChapterReadCountTest {
    private val chapters = (200..218).mapIndexed { index, number ->
        ChapterEntity(
            chapterId = number.toLong(), mangaId = 37L, title = "Chapter $number",
            number = number.toFloat(), volume = 0, url = "/$number",
            scanlator = null, uploadDate = 0L, branch = null, source = "TEST", index = index,
        )
    }

    @Test fun latestHistoryDoesNotImplicitlyReadEarlierChapters() {
        assertEquals(1, countReadChapters(chapters, emptySet(), emptyMap(), 218L))
        assertEquals(0, countReadChapters(chapters, emptySet(), emptyMap(), null))
    }

    @Test fun visitsAndExplicitOverridesAreIndependent() {
        val visits = setOf(ChapterPersonalKey("TEST", "/217"))
        assertEquals(2, countReadChapters(chapters, visits, emptyMap(), 218L))
        assertEquals(1, countReadChapters(chapters, visits, mapOf(218L to false), 218L))
        assertEquals(3, countReadChapters(chapters, visits, mapOf(200L to true), 218L))
    }

    @Test fun visitedChapterMayStillBeManuallyUnread() {
        val visits = setOf(ChapterPersonalKey("TEST", "/216"))
        assertEquals(0, countReadChapters(chapters, visits, mapOf(216L to false), null))
        assertEquals(1, countReadChapters(chapters, visits, mapOf(216L to true), null))
    }

    @Test fun similarChapterNumbersAcrossSourcesNeverShareVisitKeys() {
        assertEquals(0, countReadChapters(chapters, setOf(ChapterPersonalKey("OTHER", "/217")), emptyMap(), null))
        assertEquals(0, countReadChapters(chapters, setOf(ChapterPersonalKey("TEST", "/same-number")), emptyMap(), null))
    }
}
