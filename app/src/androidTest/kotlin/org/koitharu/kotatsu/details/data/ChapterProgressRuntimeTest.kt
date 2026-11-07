package org.koitharu.kotatsu.details.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
            val pendingAtStart = settings.getChapterPageResetStamp(manga.id, 218L)
            if (pendingAtStart < 0) settings.completeChapterPageReset(manga.id, 218L, pendingAtStart)
            repository.record(manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 70, 0), 71)
            repository.record(manga, FavouriteSpace.NORMAL, chapterB, ReaderState(217L, 11, 0), 30)
            repository.record(manga, FavouriteSpace.PRIVATE, chapterA, ReaderState(218L, 6, 0), 71)
            assertEquals(70, repository.get(manga.id, FavouriteSpace.NORMAL, chapterA)?.page)

            val previousStamp = settings.getChapterPageResetStamp(manga.id, 218L)
            settings.setChapterReadOverride(manga.id, 218L, false)
            // Reset is scheduled from a short-lived caller (like a Details ViewModel).
            val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val requestedResetJob = CompletableDeferred<kotlinx.coroutines.Job>()
            caller.launch {
                requestedResetJob.complete(repository.requestManualUnreadReset(
                    manga.id, FavouriteSpace.NORMAL, chapterA, 218L,
                ))
            }
            val resetJob = requestedResetJob.await()
            val resetStamp = settings.getChapterPageResetStamp(manga.id, 218L)
            assertEquals(true, resetStamp < 0)
            assertEquals(true, kotlin.math.abs(resetStamp) > kotlin.math.abs(previousStamp))
            // The next gesture arrives before the async reset completes.
            settings.setChapterReadOverride(manga.id, 218L, true)
            caller.cancel() // cancelling the caller must not cancel the DB cleanup
            assertNull(repository.get(manga.id, FavouriteSpace.NORMAL, chapterA, 218L))
            resetJob.join()
            assertNull(repository.get(manga.id, FavouriteSpace.NORMAL, chapterA))
            assertEquals(11, repository.get(manga.id, FavouriteSpace.NORMAL, chapterB)?.page)
            assertEquals(6, repository.get(manga.id, FavouriteSpace.PRIVATE, chapterA, 218L)?.page)
            assertEquals(0L, settings.getChapterPageResetStamp(manga.id, 218L, FavouriteSpace.PRIVATE.dbValue))

            // Writes captured before Mark as unread are invalid even after Mark as read.
            repository.record(
                manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 70, 0), 71,
                eventStamp = previousStamp, visitToken = 1L, isNewVisit = true,
            )
            assertNull(repository.get(manga.id, FavouriteSpace.NORMAL, chapterA))

            // Page events from the OLD open reader cannot recreate page 71.
            repository.record(
                manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 70, 0), 71,
                eventStamp = resetStamp, visitToken = 1L, isNewVisit = false,
            )
            assertNull(repository.get(manga.id, FavouriteSpace.NORMAL, chapterA))

            // A genuinely new normal Reader visit begins from page one (zero-based page 0).
            repository.record(
                manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 0, 0), 71,
                eventStamp = resetStamp, visitToken = 2L, isNewVisit = true,
            )
            assertEquals(0, repository.get(manga.id, FavouriteSpace.NORMAL, chapterA, 218L)?.page)
            assertEquals(true, settings.getChapterPageResetStamp(manga.id, 218L) > 0)
            // Subsequent rapid page changes from that same visit must not be dropped.
            repository.record(
                manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 3, 0), 71,
                eventStamp = resetStamp, visitToken = 2L, isNewVisit = false,
            )
            assertEquals(3, repository.get(manga.id, FavouriteSpace.NORMAL, chapterA, 218L)?.page)

            // A second genuine opening supersedes the first; delayed writes from the older
            // Reader session (whether captured before or after reset completion) are rejected.
            val committedStamp = settings.getChapterPageResetStamp(manga.id, 218L)
            repository.record(
                manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 4, 0), 71,
                eventStamp = committedStamp, visitToken = 3L, isNewVisit = true,
            )
            repository.record(
                manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 70, 0), 71,
                eventStamp = resetStamp, visitToken = 2L, isNewVisit = false,
            )
            repository.record(
                manga, FavouriteSpace.NORMAL, chapterA, ReaderState(218L, 70, 0), 71,
                eventStamp = committedStamp, visitToken = 2L, isNewVisit = false,
            )
            assertEquals(4, repository.get(manga.id, FavouriteSpace.NORMAL, chapterA, 218L)?.page)
            assertEquals(11, repository.get(manga.id, FavouriteSpace.NORMAL, chapterB)?.page)
            // The normal-library reset must not delete private-library last-page data.
            assertEquals(6, repository.get(manga.id, FavouriteSpace.PRIVATE, chapterA, 218L)?.page)
        } finally {
            settings.setChapterReadOverrides(manga.id, emptyMap())
            // A positive generation is harmless outside this isolated test manga.
            val pending = settings.getChapterPageResetStamp(manga.id, 218L)
            if (pending < 0) settings.completeChapterPageReset(manga.id, 218L, pending)
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
