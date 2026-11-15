package org.koitharu.kotatsu.download.ui.worker

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import java.io.File

class DownloadTaskTest {

	@Test
	fun `equivalent chapter requests share stable work identity`() {
		val first = task(
			chapters = longArrayOf(3L, 1L, 3L, 2L),
			paused = false,
			silent = false,
			metered = true,
		)
		val second = task(
			chapters = longArrayOf(2L, 1L, 3L),
			paused = true,
			silent = true,
			metered = false,
		)

		assertEquals(first.uniqueWorkName(), second.uniqueWorkName())
	}

	@Test
	fun `different physical download targets keep separate identities`() {
		val base = task(chapters = longArrayOf(7L))
		assertNotEquals(base.uniqueWorkName(), task(chapters = longArrayOf(8L)).uniqueWorkName())
		assertNotEquals(base.uniqueWorkName(), task(chapters = longArrayOf(7L), destination = File("/tmp/other")).uniqueWorkName())
		assertNotEquals(base.uniqueWorkName(), task(chapters = longArrayOf(7L), space = FavouriteSpace.PRIVATE).uniqueWorkName())
		assertNotEquals(base.uniqueWorkName(), task(chapters = longArrayOf(7L), format = DownloadFormat.SINGLE_CBZ).uniqueWorkName())
	}

	@Test
	fun `whole manga identity does not collide with chapter subset`() {
		assertNotEquals(task(chapters = null).uniqueWorkName(), task(chapters = longArrayOf(1L, 2L)).uniqueWorkName())
	}

	@Test
	fun `existing work data defaults to ordinary download behavior`() {
		val legacy = DownloadTask(Data.Builder().putLong("manga_id", 42L).build())
		assertEquals(false, legacy.appendGalleryIdToChapterFile)
		assertEquals(false, legacy.downloadSeparateCopy)
	}

	@Test
	fun `chapter filename and separate copy choices survive reconstruction`() {
		val regular = task(chapters = longArrayOf(7L))
		val named = task(chapters = longArrayOf(7L), appendGalleryId = true)
		val separate = task(chapters = longArrayOf(7L), separateCopy = true)
		val both = task(chapters = longArrayOf(7L), appendGalleryId = true, separateCopy = true)
		val requests = listOf(regular, named, separate, both)
		assertEquals(4, requests.map { it.uniqueWorkName() }.toSet().size)
		for (request in requests) {
			val restored = DownloadTask(request.toData())
			assertEquals(request, restored)
			assertEquals(request.uniqueWorkName(), restored.uniqueWorkName())
		}
	}
	private fun task(
		chapters: LongArray?,
		destination: File = File("/tmp/downloads"),
		space: FavouriteSpace = FavouriteSpace.NORMAL,
		format: DownloadFormat? = null,
		paused: Boolean = false,
		silent: Boolean = false,
		metered: Boolean = true,
		appendGalleryId: Boolean = false,
		separateCopy: Boolean = false,
	) = DownloadTask(
		mangaId = 42L,
		isPaused = paused,
		isSilent = silent,
		chaptersIds = chapters,
		destination = destination,
		format = format,
		allowMeteredNetwork = metered,
		favouriteSpace = space,
		appendGalleryIdToChapterFile = appendGalleryId,
		downloadSeparateCopy = separateCopy,
	)
}
