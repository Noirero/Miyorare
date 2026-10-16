package org.koitharu.kotatsu.local.data.output

import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Diagnostic round-trip for the exact downloaded-novel boundary:
 * source HTML -> LocalNovelEpubOutput -> LocalMangaParser -> file+zip URL -> reader-style HTML parse.
 *
 * Keep this probe independent from normal manga/CBZ output so a failure identifies the novel EPUB
 * producer/consumer boundary without changing production behavior merely to make the test pass.
 */
class DownloadedNovelRoundTripProbeTest {

	@Test
	fun `downloaded novel xhtml survives epub local parser and reader parse`() = runTest {
		val dir = Files.createTempDirectory("miyorare-novel-roundtrip").toFile()
		try {
			val epub = File(dir, "Chapter 1.epub")
			val htmlFile = File(dir, "source.html").apply {
				writeText("<article><h1>Chapter 1</h1><p>Round trip sentinel &amp; readable text.</p></article>")
			}
			val chapter = chapter()
			val manga = manga(chapter)
			val output = LocalNovelEpubOutput(epub, manga)

			try {
				output.addPage(IndexedValue(0, chapter), htmlFile, 0, null)
				output.finish()
			} finally {
				output.close()
			}

			assertTrue("producer must finalize a readable EPUB", epub.isFile && epub.length() > 0L)

			val local = LocalMangaParser(epub).getManga(withDetails = true).manga
			val localChapter = local.chapters?.singleOrNull()
			assertNotNull("Local parser must recover the downloaded chapter", localChapter)
			val chapterUrl = requireNotNull(localChapter).url
			assertTrue("downloaded chapter must point back into its EPUB", chapterUrl.startsWith("file+zip://"))

			val archivePath = chapterUrl.substringAfter("file+zip://").substringBeforeLast('#')
			val entryName = chapterUrl.substringAfterLast('#')
			assertTrue("chapter URL must preserve the generated XHTML entry", entryName.endsWith(".xhtml"))

			val xhtml = ZipFile(File(archivePath)).use { zip ->
				val entry = zip.getEntry(entryName)
				assertNotNull("chapter URL must resolve to a real EPUB entry", entry)
				zip.getInputStream(requireNotNull(entry)).bufferedReader().use { it.readText() }
			}
			assertTrue("reader must receive nonblank XHTML", xhtml.isNotBlank())

			val document = Jsoup.parse(xhtml)
			document.select("script,style,noscript").remove()
			val body = document.body()
			assertNotNull("reader parse must expose an HTML body", body)
			val parsedText = requireNotNull(body).text()
			assertFalse("reader parse must not collapse the downloaded chapter to blank", parsedText.isBlank())
			assertEquals("Chapter 1 Round trip sentinel & readable text.", parsedText)
		} finally {
			dir.deleteRecursively()
		}
	}

	private fun manga(chapter: MangaChapter) = Manga(
		id = 1001L,
		title = "Round Trip Novel",
		altTitles = emptySet(),
		state = null,
		rating = 0f,
		contentRating = null,
		url = "/novel",
		publicUrl = "https://example.invalid/novel",
		coverUrl = null,
		largeCoverUrl = null,
		authors = setOf("Probe Author"),
		description = null,
		source = SOURCE,
		tags = emptySet(),
		chapters = listOf(chapter),
	)

	private fun chapter() = MangaChapter(
		id = 2001L,
		title = "Chapter 1",
		number = 1f,
		volume = 0,
		url = "/novel/chapter-1",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = SOURCE,
	)

	private companion object {
		val SOURCE = MangaSource("MIHON_1")
	}
}
