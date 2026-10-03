package org.koitharu.kotatsu.download.ui.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadChapterAccountingTest {
	@Test
	fun monolithicContainerCountsStagedChapterBeforeFinish() {
		val accounting = accountStagedChapter(flushCreatedArtifact = false)
		assertTrue(accounting.requestedChapterCompleted)
		assertFalse(accounting.durableArtifactAvailable)
	}

	@Test
	fun perChapterArtifactCountsAndCanRecordOwnershipImmediately() {
		val accounting = accountStagedChapter(flushCreatedArtifact = true)
		assertTrue(accounting.requestedChapterCompleted)
		assertTrue(accounting.durableArtifactAvailable)
	}
}
