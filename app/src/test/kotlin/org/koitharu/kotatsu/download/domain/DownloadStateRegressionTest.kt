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
}
