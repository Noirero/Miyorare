package org.koitharu.kotatsu.download.domain

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateRegressionTest {

	@Test
	fun `zero completed pages stays at zero progress`() {
		assertEquals(0, calculateDownloadProgress(totalPages = 12, currentChapter = 0, currentPage = 0))
	}

	@Test
	fun `last completed page does not exceed maximum`() {
		assertEquals(12, calculateDownloadProgress(totalPages = 12, currentChapter = 0, currentPage = 12))
		assertEquals(24, calculateDownloadProgress(totalPages = 12, currentChapter = 1, currentPage = 12))
	}

	@Test
	fun `finalizing phase survives WorkManager serialization`() {
		val finalizing = Data.Builder().putBoolean("finalizing", true).build()
		val transferring = Data.Builder().putBoolean("finalizing", false).build()

		assertTrue(DownloadState.isFinalizing(finalizing))
		assertFalse(DownloadState.isFinalizing(transferring))
	}

	@Test
	fun `download phase and retry metadata survive WorkManager serialization`() {
		val retrying = Data.Builder()
			.putString("phase", DownloadPhase.RETRYING.name)
			.putInt("phase_chapter", 2)
			.putInt("requested_chapters", 4)
			.putInt("retry_attempt", 2)
			.build()

		assertEquals(DownloadPhase.RETRYING, DownloadState.getPhase(retrying))
		assertEquals(2, DownloadState.getPhaseChapter(retrying))
		assertEquals(4, DownloadState.getRequestedChapters(retrying))
		assertEquals(2, DownloadState.getRetryAttempt(retrying))
	}

	@Test
	fun `legacy work data defaults to downloading phase without eta state corruption`() {
		val legacy = Data.EMPTY
		assertEquals(DownloadPhase.DOWNLOADING, DownloadState.getPhase(legacy))
		assertEquals(0, DownloadState.getRetryAttempt(legacy))
	}
}
