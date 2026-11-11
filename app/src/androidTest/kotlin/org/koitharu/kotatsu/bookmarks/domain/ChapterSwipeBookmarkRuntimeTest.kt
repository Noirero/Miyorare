package org.koitharu.kotatsu.bookmarks.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import java.time.Instant

/** Database-backed ownership regression: gesture removal must not touch Reader-owned pages. */
@RunWith(AndroidJUnit4::class)
class ChapterSwipeBookmarkRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manga get() = SampleData.mangaDetails
    private val chapterId get() = requireNotNull(manga.chapters).first().id

    private fun bookmark(pageId: Long, index: Int) = Bookmark(
        manga = manga,
        pageId = pageId,
        chapterId = chapterId,
        page = index,
        scroll = 0,
        imageUrl = "https://example.com/page/$pageId",
        createdAt = Instant.EPOCH,
        percent = 0.1f,
    )

    @Test
    fun swipeRemovalPreservesReaderBookmarksInSameChapter() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try {
            val repo = BookmarksRepository(database)
            val manual = bookmark(501L, 2)
            val swipe = bookmark(502L, 7)
            repo.addBookmark(manual)
            assertEquals(SwipeBookmarkWriteResult.ADDED, repo.addSwipeBookmark(swipe))
            val before = repo.observeBookmarks(manga).first()
            assertEquals(2, before.size)
            assertTrue(before.any { it.pageId == 501L && it.origin == BOOKMARK_ORIGIN_READER })
            assertTrue(before.any { it.pageId == 502L && it.origin == BOOKMARK_ORIGIN_DETAILS_SWIPE })
            assertTrue(repo.removeSwipeBookmark(manga.id, chapterId))
            val after = repo.observeBookmarks(manga).first()
            assertEquals(listOf(501L), after.map { it.pageId })
            assertFalse(repo.removeSwipeBookmark(manga.id, chapterId))
        } finally {
            database.close()
        }
    }

    @Test
    fun samePageReaderBookmarkCannotBeHijacked() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try {
            val repo = BookmarksRepository(database)
            val manual = bookmark(601L, 4)
            repo.addBookmark(manual)
            assertEquals(SwipeBookmarkWriteResult.PAGE_ALREADY_BOOKMARKED, repo.addSwipeBookmark(manual))
            assertFalse(repo.removeSwipeBookmark(manga.id, chapterId))
            assertEquals(BOOKMARK_ORIGIN_READER, repo.observeBookmarks(manga).first().single().origin)
        } finally {
            database.close()
        }
    }

    @Test
    fun annotatingSwipeBookmarkPromotesItToReaderOwnership() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try {
            val repo = BookmarksRepository(database)
            val swipe = bookmark(701L, 3)
            assertEquals(SwipeBookmarkWriteResult.ADDED, repo.addSwipeBookmark(swipe))
            repo.updateBookmarkNote(swipe, "Saved in Reader")
            assertFalse(repo.removeSwipeBookmark(manga.id, chapterId))
            val kept = repo.observeBookmarks(manga).first().single()
            assertEquals(BOOKMARK_ORIGIN_READER, kept.origin)
            assertEquals("Saved in Reader", kept.note)
        } finally {
            database.close()
        }
    }
}
