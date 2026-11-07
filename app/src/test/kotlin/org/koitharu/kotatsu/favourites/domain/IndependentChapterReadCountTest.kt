package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.details.data.ChapterPersonalKey
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.CHAPTERS_READ

class IndependentChapterReadCountTest {
    private val chapters = (200..218).mapIndexed { index, number ->
        ChapterEntity(
            chapterId = number.toLong(), mangaId = 37L, title = "Chapter $number",
            number = number.toFloat(), volume = 0, url = "/$number",
            scanlator = null, uploadDate = 0L, branch = null, source = "TEST", index = index,
        )
    }

    @Test fun novelChapterCountKeepsHistoricalProgressWhileMangaUsesIndependentVisits() {
        val legacy = HistoryEntity(
            mangaId = 37L, createdAt = 0L, updatedAt = 0L,
            chapterId = 218L, page = 0, scroll = 0f,
            percent = 18f / 19f, deletedAt = 0L, chaptersCount = 19,
        )
        val histories = mapOf(37L to legacy)
        val unread = mapOf(37L to 18)
        val authority = setOf(37L)
        val read = mapOf(37L to 1)
        val totals = mapOf(37L to 19)
        val manga = FavouriteUnreadCounter.Snapshot(histories, unread, authority, read, totals)
        val novel = FavouriteUnreadCounter.Snapshot(histories, unread, authority, read, totals, legacyNovelIds = setOf(37L))
        assertEquals(1f / 19f, manga.getProgress(37L, CHAPTERS_READ)!!.percent, 0.0001f)
        assertEquals(18f / 19f, novel.getProgress(37L, CHAPTERS_READ)!!.percent, 0.0001f)

        // Novel History can outlive its replaceable chapter cache: the legacy
        // story-position badge must not disappear when totalCounts is unavailable.
        val noCachedChapters = FavouriteUnreadCounter.Snapshot(
            histories = histories, unreadCounts = emptyMap(),
            legacyNovelIds = setOf(37L),
        )
        assertEquals(18f / 19f, noCachedChapters.getProgress(37L, CHAPTERS_READ)!!.percent, 0.0001f)
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
