package org.koitharu.kotatsu.download.ui.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DownloadTempFilesTest {

	@get:Rule val directory = TemporaryFolder()

	@Test
	fun `finishing A preserves B cover transfer and all foreign artifacts`() = runTest {
		val ready = CompletableDeferred<File>()
		val resume = CompletableDeferred<Unit>()
		val workerB = launch {
			DownloadTempFiles().use { files ->
				val cover = files.create(directory.root, "jpg")
				cover.outputStream().use { transfer ->
					transfer.write("first ".toByteArray())
					ready.complete(cover)
					resume.await()
					transfer.write("last".toByteArray())
				}
				assertEquals("first last", cover.readText())
			}
		}
		val foreignCover = ready.await()
		val foreignFiles = listOf("novel.epub.tmp", "manga.cbz.tmp", "page.part", "page.ready", "final.cbz")
			.map { File(directory.root, it).apply { writeText(it) } }
		lateinit var ownCover: File
		DownloadTempFiles().use { files ->
			ownCover = files.create(directory.root, "png").apply { writeText("A") }
		}
		assertFalse(ownCover.exists())
		assertTrue(foreignCover.isFile)
		for (file in foreignFiles) assertEquals(file.name, file.readText())
		resume.complete(Unit)
		workerB.join()
		assertFalse(foreignCover.exists())
	}

	@Test
	fun `failed cover consumption cleans every owned transfer without touching foreign temp`() {
		val foreign = File(directory.root, "foreign.tmp").apply { writeText("keep") }
		val owned = ArrayList<File>()
		val failure = runCatching {
			DownloadTempFiles().use { files ->
				for (extension in listOf("jpg", "png", null)) {
					owned += files.create(directory.root, extension).apply { writeText("partial") }
				}
				throw IOException("cover consumer failed")
			}
		}.exceptionOrNull()
		assertTrue(failure is IOException)
		assertTrue(owned.none(File::exists))
		assertEquals("keep", foreign.readText())
	}

	@Test
	fun `cancelled A preserves active B transfer while cleaning its own scratch file`() = runTest {
		val workerB = DownloadTempFiles()
		val foreign = workerB.create(directory.root, "jpg").apply { writeText("B") }
		try {
			val ready = CompletableDeferred<File>()
			val workerA = launch {
				val files = DownloadTempFiles()
				try {
					ready.complete(files.create(directory.root, "jpg").apply { writeText("A") })
					awaitCancellation()
				} finally {
					withContext(NonCancellable) { files.close() }
				}
			}
			val own = ready.await()
			workerA.cancelAndJoin()
			assertFalse(own.exists())
			assertEquals("B", foreign.readText())
		} finally {
			workerB.close()
		}
		assertFalse(foreign.exists())
	}

	@Test
	fun `cleanup is idempotent after a consumed cover was deleted`() {
		val files = DownloadTempFiles()
		val cover = files.create(directory.root, "jpg").apply { writeText("cover") }
		assertTrue(cover.delete())
		files.close()
		files.close()
		assertFalse(cover.exists())
	}
}
