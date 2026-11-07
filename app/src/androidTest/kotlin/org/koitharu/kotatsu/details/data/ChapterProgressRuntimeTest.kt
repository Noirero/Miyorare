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
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.reader.ui.ReaderState

/** File-backed database test: cache/VM lifetime cannot explain a successful resume. */
@RunWith(AndroidJUnit4::class)
class ChapterProgressRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun lastPageForEachChapterSurvivesReopenAndCanMoveBackwards() = runTest {
        val dbName = "independent-chapter-progress-test.db"
        context.deleteDatabase(dbName)
        val manga = SampleData.mangaDetails.copy(id = 995922L)
        val a = ChapterPersonalKey("SOURCE", "/chapter/218")
        val b = ChapterPersonalKey("SOURCE", "/chapter/217")
        val first = Room.databaseBuilder(context, MangaDatabase::class.java, dbName).build()
        try {
            val repository = ChapterProgressRepository(first)
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
            val repository = ChapterProgressRepository(reopened)
            assertEquals(8, repository.get(manga.id, FavouriteSpace.NORMAL, a)?.page)
            assertEquals(38, repository.get(manga.id, FavouriteSpace.NORMAL, a)?.totalPages)
            assertEquals(11, repository.get(manga.id, FavouriteSpace.NORMAL, b)?.page)
        } finally {
            reopened.close()
            context.deleteDatabase(dbName)
        }
    }
}
