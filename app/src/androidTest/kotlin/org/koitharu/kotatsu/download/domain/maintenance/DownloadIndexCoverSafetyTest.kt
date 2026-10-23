package org.koitharu.kotatsu.download.domain.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DownloadIndexCoverSafetyTest {
	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var parent: File
	private lateinit var root: File

	@Before
	fun setUp() {
		parent = File(context.cacheDir, "download-index-cover-${System.nanoTime()}").canonicalFile
		root = File(parent, "Manga").canonicalFile
		check(root.mkdirs())
	}

	@After
	fun tearDown() {
		parent.deleteRecursively()
	}

	@Test
	fun invalidCoverTraversalIsRemovedWithoutTouchingOutsideFile() = runBlocking {
		val remote = remote()
		val chapter = chapterFile("Chapter 1.cbz")
		val outsideCover = File(parent, "outside-cover.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
		val before = outsideCover.readBytes()
		val old = index(remote, chapter.name, "../outside-cover.jpg")

		val plan = plan(remote, old)

		assertTrue(plan.complete)
		val content = assertNotNull(plan.content)
		assertNull(MangaIndex(content).getCoverEntry())
		assertArrayEquals(before, outsideCover.readBytes())
	}

	@Test
	fun invalidCoverTraversalUsesSafeInRootFallback() = runBlocking {
		val remote = remote()
		val chapter = chapterFile("Chapter 1.cbz")
		File(root, "cover.jpeg").writeBytes(byteArrayOf(1, 2, 3))
		val old = index(remote, chapter.name, "../outside-cover.jpg")

		val plan = plan(remote, old)

		assertTrue(plan.complete)
		val content = assertNotNull(plan.content)
		assertEquals("cover.jpeg", MangaIndex(content).getCoverEntry())
	}

	@Test
	fun validInRootCoverAndMappingsRemainNoOp() = runBlocking {
		val remote = remote()
		val chapter = chapterFile("Chapter 1.cbz")
		File(root, "cover.jpg").writeBytes(byteArrayOf(4, 5, 6))
		val old = index(remote, chapter.name, "cover.jpg")

		val plan = plan(remote, old)

		assertTrue(plan.complete)
		assertNull(plan.content)
	}

	private suspend fun plan(remote: Manga, old: MangaIndex): DownloadIndexRepair.Plan {
		val physical = LocalMangaParser(root, true).getManga(true)
		val restored = DownloadIndexRepair.restoreValidMappings(root, old, physical)
		return DownloadIndexRepair.plan(root, old.toString(), remote, physical, restored)
	}

	private fun index(remote: Manga, chapterFileName: String, coverEntry: String): MangaIndex = MangaIndex(null).apply {
		setMangaInfo(remote)
		addChapter(IndexedValue(0, remote.chapters!!.single()), chapterFileName)
		setCoverEntry(coverEntry)
	}

	private fun remote(): Manga {
		val source = MissingMangaSource("MAINTENANCE_COVER_SAFETY")
		return SampleData.manga.copy(
			id = 950_011L,
			title = "Cover safety",
			source = source,
			chapters = listOf(
				SampleData.chapter.copy(
					id = 201L,
					title = "Chapter 1",
					number = 1f,
					scanlator = null,
					branch = null,
					source = source,
					url = "https://example.invalid/cover-safety/1",
				),
			),
		)
	}

	private fun chapterFile(name: String): File = File(root, name).apply {
		ZipOutputStream(outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("1.jpg"))
			zip.write(byteArrayOf(1, 2, 3))
			zip.closeEntry()
		}
	}
}
