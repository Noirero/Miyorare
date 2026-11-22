package org.koitharu.kotatsu.download.domain

import android.content.Context

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.diagnostics.DownloadEvent
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics
import org.koitharu.kotatsu.core.logs.VerboseLogStore
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.reader.domain.ReaderDiagnostics
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class DownloadDiagnosticPrivacyRuntimeTest {
	@get:Rule val directory = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)

	@Test
	fun onlyDownloadsQueuedByThePublicReaderReachTheExport() = runBlocking {
		PerformanceDiagnostics.start(ApplicationProvider.getApplicationContext())
		val report = try {
			simulateDownload(fromReader(incognito = false), pages = 412)
			simulateDownload(fromReader(incognito = true), pages = 413)
			simulateDownload(fromReader(incognito = null), pages = 414)
			simulateDownload(fromReader(incognito = false, peek = true), pages = 415)
			simulateDownload(fromReader(incognito = false, space = FavouriteSpace.PRIVATE), pages = 416)
			simulateDownload(DownloadTask(Data.Builder().putLong("manga_id", 1L).build()), pages = 417)
			simulateDownload(fromReader(incognito = false), pages = 418, isPublic = false)
			requireNotNull(PerformanceDiagnostics.exportSnapshot())
		} finally {
			PerformanceDiagnostics.stop()
		}

		val store = VerboseLogStore(directory.newFolder())
		store.finish(report, partial = false)
		val output = ByteArrayOutputStream()
		assertTrue(store.export { output })
		val exported = output.toString(Charsets.UTF_8.name())
		assertTrue(exported.contains("expected_pages=412 actual_pages=412"))
		for (pages in 413..418) assertFalse(exported.contains("expected_pages=$pages"))
	}

	private fun fromReader(
		incognito: Boolean?,
		peek: Boolean = false,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): DownloadTask = DownloadTask(
		mangaId = 42L,
		isPaused = false,
		isSilent = false,
		chaptersIds = longArrayOf(7L),
		destination = null,
		format = null,
		allowMeteredNetwork = true,
		favouriteSpace = space,
		allowDiagnosticExport = ReaderDiagnostics.isPublicMode(incognito, peek),
	)

	private suspend fun simulateDownload(queued: DownloadTask, pages: Int, isPublic: Boolean = true) {
		val task = DownloadTask(queued.toData())
		DownloadObservation.exportFor(task) { isPublic }
			?.record(DownloadEvent.PAGES_RESOLVED, pages, pages)
	}
}
