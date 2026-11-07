package org.koitharu.kotatsu.details.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.reader.ui.ReaderState

/** File-backed database test: cache/VM lifetime cannot explain a successful resume. */
@RunWith(AndroidJUnit4::class)
class ChapterProgressRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings by lazy { AppSettings(context) }

    @Test fun lastPageForEachChapterSurvivesReopenAndCanMoveBackwards() = runTest {
        val dbName = "independent-chapter-progress-test.db"
        context.deleteDatabase(dbName)
        val manga = SampleData.mangaDetails.copy(id = 995922L)
        val a = ChapterPersonalKey("SOURCE", "/chapter/218")
        val b = ChapterPersonalKey("SOURCE", "/chapter/217")
        val first = Room.databaseBuilder(context, MangaDatabase::class.java, dbName).build()
        try {
            val repository = ChapterProgressRepository(first, settings)
            repository.record(manga, FavouriteSpace.NORMAL, a, ReaderState(218L, 13, 0), 38)
            repository.record(manga, FavouriteSpace.NORMAL, b, ReaderState(217L, 11, 0), 30)
            repository.record(manga, FavouriteSpace.NORMAL, a, ReaderState(218L, 8, 0), 38)
            assertNull(first.getHistoryDao().find(manga.id)) // no synthetic global History writes
            assertNull(repository.get(manga.id, FavouriteSpace.PRIVATE, a))
        } finally {
            first.close()
        }

        val reopened = Room.databaseBuilder(context, MangaDatabase::class.java, dbName).build()
        try {
            val repository = ChapterProgressRepository(reopened, settings)
            assertEquals(8, repository.get(manga.id, FavouriteSpace.NORMAL, a)?.page)
            assertEquals(38, repository.get(manga.id, FavouriteSpace.NORMAL, a)?.totalPages)
            assertEquals(11, repository.get(manga.id, FavouriteSpace.NORMAL, b)?.page)
        } finally {
            reopened.close()
            context.deleteDatabase(dbName)
        }
    }
    @Test fun manualUnreadClearsOnlyItsChapterAndRejectsDelayedReaderWrites() = runTest {
        val dbName = "chapter-progress-manual-unread-test.db"
        context.deleteDatabase(dbName)
        val manga = SampleData.mangaDetails.copy(id = 995923L)
        val chapterA = ChapterPersonalKey("SOURCE", "/chapter/218")
        val chapterB = ChapterPersonalKey("SOURCE", "/chapter/217")
        val db = Room.databaseBuilder(context, MangaDatabase::class.java, dbName).build()
        try {
            val repository = ChapterProgressRepository(db, settings)
            settings.setChapterReadOverrides(manga.id, emptyMap())
            repository.record(manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 70, 0), 71)
            repository.record(manga, FavouriteSpace.NORMAL, chapterB, ReaderState(217L, 11, 0), 30)
            assertEquals(70, repository.get(manga.id, FavouriteSpace.NORMAL, chapterA)?.page)

            settings.setChapterReadOverride(manga.id, 218L, false)
            repository.resetOnManualUnread(manga.id, FavouriteSpace.NORMAL, chapterA, 218L)
            assertNull(repository.get(manga.id, FavouriteSpace.NORMAL, chapterA))
            assertEquals(11, repository.get(manga.id, FavouriteSpace.NORMAL, chapterB)?.page)

            // Reader page events queued before the swipe cannot recreate the deleted position.
            repository.record(manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 70, 0), 71)
            assertNull(repository.get(manga.id, FavouriteSpace.NORMAL, chapterA))

            // A new explicit visit re-enables progress, beginning from page zero.
            settings.setChapterReadOverride(manga.id, 218L, true)
            repository.record(manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 0, 0), 71)
            assertEquals(0, repository.get(manga.id, FavouriteSpace.NORMAL, chapterA)?.page)
            assertEquals(11, repository.get(manga.id, FavouriteSpace.NORMAL, chapterB)?.page)
        } finally {
            settings.setChapterReadOverrides(manga.id, emptyMap())
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
