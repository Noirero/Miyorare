package org.koitharu.kotatsu.local.data

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FastDownloadedNovelResolutionTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject lateinit var repository: LocalMangaRepository
	@Inject lateinit var localIndex: LocalMangaIndex

	private lateinit var root: File

	@Before
	fun setUp() {
		hiltRule.inject()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		root = File.createTempFile("fast-downloaded-novel-", ".dir", context.cacheDir)
		check(root.delete() && root.mkdir())
	}

	@After
	fun tearDown() = runBlocking {
		localIndex.delete(MANGA_ID)
		root.deleteRecursively()
		Unit
	}

	@Test
	fun fastNovelResolutionPointsReaderAtEmbeddedXhtml() = runBlocking {
		val remote = remoteNovel()
		val chapter = requireNotNull(remote.chapters).single()
		val epub = File(root, "Volume 3.epub")
		writeDownloadedEpub(epub, remote, chapter)

		val resolved = requireNotNull(repository.findSavedMangaAtPath(
			remoteManga = remote,
			file = root,
			withDetails = true,
			rememberIdentity = false,
		))
		val localChapter = requireNotNull(resolved.manga.chapters).single()
		val uri = localChapter.url.toUri()

		assertEquals(chapter.id, localChapter.id)
		assertEquals(URI_SCHEME_ZIP, uri.scheme)
		assertEquals(CONTENT_ENTRY, uri.fragment)
		assertTrue(File(uri.schemeSpecificPart).canonicalFile == epub.canonicalFile)
	}

	private fun remoteNovel(): Manga {
		val source = MissingMangaSource("LN_FAST_DOWNLOADED_NOVEL_TEST")
		val chapter = SampleData.chapter.copy(
			id = CHAPTER_ID,
			title = "Volume 3",
			number = 3f,
			url = "https://example.invalid/volume-3",
			source = source,
			branch = null,
		)
		return SampleData.manga.copy(
			id = MANGA_ID,
			title = "Fast downloaded novel",
			url = "https://example.invalid/novel",
			publicUrl = "https://example.invalid/novel",
			source = source,
			chapters = listOf(chapter),
		)
	}

	private fun writeDownloadedEpub(file: File, manga: Manga, chapter: MangaChapter) {
		val index = MangaIndex(null).apply {
			setMangaInfo(manga)
			addChapter(IndexedValue(0, chapter), CONTENT_ENTRY)
		}
		ZipOutputStream(file.outputStream()).use { zip ->
			for ((name, bytes) in listOf(
				LocalMangaOutput.ENTRY_NAME_INDEX to index.toString().toByteArray(),
				CONTENT_ENTRY to "<html><body><p>Downloaded novel text</p></body></html>".toByteArray(),
			)) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(bytes)
				zip.closeEntry()
			}
		}
	}

	private companion object {
		const val MANGA_ID = 9_223_372_034_906_949_409L
		const val CHAPTER_ID = 985_467_770L
		const val CONTENT_ENTRY = "OEBPS/00000000_00004.xhtml"
	}
}
