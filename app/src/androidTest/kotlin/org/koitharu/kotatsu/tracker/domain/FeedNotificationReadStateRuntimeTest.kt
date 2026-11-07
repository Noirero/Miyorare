package org.koitharu.kotatsu.tracker.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity

@RunWith(AndroidJUnit4::class)
class FeedNotificationReadStateRuntimeTest {

	private val db = Room.inMemoryDatabaseBuilder(
		InstrumentationRegistry.getInstrumentation().targetContext, MangaDatabase::class.java,
	).build()

	@Before fun seed() { runBlocking {
		db.getMangaDao().upsert(MangaEntity(
			10L, "Feed fixture", null, "https://example.invalid/manga/10",
			"https://example.invalid/manga/10", 0f, false, null, "", null,
			null, null, null, "LOCAL", null,
		))
		db.getTracksDao().upsert(track(4))
		db.getTrackLogsDao().insert(log(101L, unread = true))
		db.getTrackLogsDao().insert(log(102L, unread = true))
	}

	}

	@After fun close() { db.close() }

	private fun track(count: Int, lastCheck: Long = 10L) = TrackEntity(
		mangaId = 10L, lastChapterId = 12L, newChapters = count,
		lastCheckTime = lastCheck, lastChapterDate = 9L,
		lastResult = TrackEntity.RESULT_HAS_UPDATE, lastError = null,
	)

	private fun log(id: Long, unread: Boolean) = TrackLogEntity(
		id = id, mangaId = 10L, chapters = "Chapter $id",
		chapterIds = id.toString(), createdAt = id, isUnread = unread,
	)

	private suspend fun unread(id: Long) = checkNotNull(db.getTrackLogsDao().findById(id)).isUnread
	private suspend fun counter() = db.getTracksDao().findNewChapters(10L)

	@Test fun individualReadOnlyChangesTheChosenNotificationAndNeverAdvancesHistory() = runBlocking {
		val h = db.updateFeedReadState(setOf(101L), read = true)
		assertNotNull(h)
		assertFalse(unread(101L)); assertTrue(unread(102L))
		assertEquals(4, counter())
		assertNull(db.getHistoryDao().findIncludingDeleted(10L))
		h!!.reverse()
		assertTrue(unread(101L)); assertTrue(unread(102L))
		assertEquals(4, counter())
	}

	@Test fun individualUnreadOnlyChangesTheChosenNotificationAndUndoRestoresIt() = runBlocking {
		val first = checkNotNull(db.updateFeedReadState(setOf(101L), read = true))
		val h = checkNotNull(db.updateFeedReadState(setOf(101L), read = false))
		assertTrue(unread(101L)); assertTrue(unread(102L))
		h.reverse()
		assertFalse(unread(101L)); assertTrue(unread(102L))
		first.reverse()
		assertTrue(unread(101L))
	}

	@Test fun explicitlySelectedBatchDoesNotAcknowledgeAnUnselectedLog() = runBlocking {
		db.getTrackLogsDao().insert(log(103L, unread = true))
		val h = checkNotNull(db.updateFeedReadState(setOf(101L, 102L), read = true))
		assertFalse(unread(101L)); assertFalse(unread(102L)); assertTrue(unread(103L))
		assertEquals(4, counter())
		h.reverse()
		assertTrue(unread(101L)); assertTrue(unread(102L)); assertTrue(unread(103L))
	}

	@Test fun finalAcknowledgementClearsCounterAndUndoSafelyRestoresIt() = runBlocking {
		val h = checkNotNull(db.updateFeedReadState(setOf(101L, 102L), read = true))
		assertFalse(unread(101L)); assertFalse(unread(102L))
		assertEquals(0, counter())
		h.reverse()
		assertTrue(unread(101L)); assertTrue(unread(102L))
		assertEquals(4, counter())
	}

	@Test fun undoNeverClobbersNewerTrackerCounterOrNewLog() = runBlocking {
		val h = checkNotNull(db.updateFeedReadState(setOf(101L, 102L), read = true))
		db.getTracksDao().upsert(track(7, lastCheck = 30L))
		db.getTrackLogsDao().insert(log(103L, unread = true))
		h.reverse()
		assertEquals(7, counter())
		assertTrue(unread(103L))
	}

	@Test fun undoDoesNotReopenLogOrCounterAfterReaderProgressChanges() = runBlocking {
		val h = checkNotNull(db.updateFeedReadState(setOf(101L, 102L), read = true))
		db.getHistoryDao().upsert(HistoryEntity(
			mangaId = 10L, createdAt = 50L, updatedAt = 51L, chapterId = 102L,
			page = 5, scroll = 0.2f, percent = 0.75f, deletedAt = 0L, chaptersCount = 2,
		))
		h.reverse()
		assertFalse(unread(101L)); assertFalse(unread(102L))
		assertEquals(0, counter())
		assertEquals(5, db.getHistoryDao().find(10L)?.page)
	}

	@Test fun noOpReadDoesNotClearCounterOrOfferUndo() = runBlocking {
		assertNull(db.updateFeedReadState(setOf(9999L), read = true))
		assertEquals(4, counter())
		val read = checkNotNull(db.updateFeedReadState(setOf(101L), read = true))
		assertNull(db.updateFeedReadState(setOf(101L), read = true))
		assertEquals(4, counter())
		read.reverse()
	}
}
