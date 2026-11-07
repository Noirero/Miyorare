package org.koitharu.kotatsu.tracker.ui.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.history.data.HistoryEntity

class FeedUndoHistoryPolicyTest {

	private val applied = HistoryEntity(
		mangaId = 1L,
		createdAt = 100L,
		updatedAt = 100L,
		chapterId = 10L,
		page = 0,
		scroll = 0f,
		percent = 0.5f,
		deletedAt = 0L,
		chaptersCount = 20,
	)

	@Test
	fun `Undo restores only the exact Feed-written history snapshot`() {
		assertTrue(shouldRestoreFeedHistory(applied, applied.copy()))
	}

	@Test
	fun `Undo cannot roll back a Reader page on the same chapter`() {
		assertFalse(shouldRestoreFeedHistory(applied, applied.copy(page = 5)))
		assertFalse(shouldRestoreFeedHistory(applied, applied.copy(scroll = 0.35f)))
	}

	@Test
	fun `Undo cannot roll back a later Reader write or removed history`() {
		assertFalse(shouldRestoreFeedHistory(applied, applied.copy(updatedAt = 101L)))
		assertFalse(shouldRestoreFeedHistory(applied, applied.copy(chapterId = 11L)))
		assertFalse(shouldRestoreFeedHistory(applied, null))
	}
}
