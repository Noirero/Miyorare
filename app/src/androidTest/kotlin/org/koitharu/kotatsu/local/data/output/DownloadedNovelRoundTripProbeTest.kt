package org.koitharu.kotatsu.local.data.output

import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Android integration probe for the exact downloaded-novel boundary:
 * source HTML -> LocalNovelEpubOutput -> LocalMangaParser -> file+zip URL -> reader HTML parse.
 */
class DownloadedNovelRoundTripProbeTest {

	@Test
	fun downloadedNovelXhtmlSurvivesEpubLocalParserAndReaderParse() = runTest {
		assertRoundTrip("Chapter 1.epub")
	}

	@Test
	fun downloadedNovelXhtmlSurvivesReservedCharactersInEpubPath() = runTest {
		// LocalNovelDirOutput deliberately keeps '#' and '%' in readable chapter filenames today.
		// Exercise those real filename characters through the same URI/archive boundary as the reader.
		assertRoundTrip("Chapter #1 100%.epub")
	}

	private suspend fun assertRoundTrip(epubName: String) {
		val dir = Files.createTempDirectory("miyorare-novel-roundtrip").toFile()
		try {
			val epub = File(dir, epubName)
			val htmlFile = File(dir, "source.html").apply {
				writeText("<article><h1>Chapter 1</h1><p>Round trip sentinel &amp; readable text.</p></article>")
			}
			val chapter = chapter()
			val output = LocalNovelEpubOutput(epub, manga(chapter))
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

			// Exercise the same Android Uri interpretation and archive lookup used by
			// EpubReaderFragment.HybridContentSource instead of manually splitting the URL.
			val uri = chapterUrl.toUri()
			assertTrue("reader must classify downloaded chapter as a ZIP URI", uri.isZipUri())
			val archive = File(uri.schemeSpecificPart)
			val entryName = uri.fragment.orEmpty()
			assertEquals("reader archive path must resolve to the generated EPUB", epub.canonicalFile, archive.canonicalFile)
			assertTrue("chapter URL must preserve the generated XHTML entry", entryName.endsWith(".xhtml"))
			val xhtml = ZipFile(archive).use { zip ->
				val entry = zip.getEntry(entryName) ?: zip.getEntry(entryName.removePrefix("/"))
				assertNotNull("reader ZIP lookup must resolve the generated XHTML entry", entry)
				zip.getInputStream(requireNotNull(entry)).bufferedReader().use { it.readText() }
			}
			assertTrue("reader must receive nonblank XHTML", xhtml.isNotBlank())

			val document = Jsoup.parse(xhtml)
			document.select("script,style,noscript").remove()
			val body = document.body()
			assertNotNull("reader parse must expose an HTML body", body)
			val parsedText = requireNotNull(body).text()
			assertFalse("Jsoup parse must not collapse the downloaded chapter to blank", parsedText.isBlank())
			assertEquals("Chapter 1 Round trip sentinel & readable text.", parsedText)

			// Exercise the Android renderer stage used by EpubReaderFragment.parseChapter().
			val rendered = HtmlCompat.fromHtml(document.body().html(), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
			assertFalse("HtmlCompat must not collapse valid downloaded XHTML to blank", rendered.isBlank())
			assertTrue("HtmlCompat output must retain the chapter heading", rendered.contains("Chapter 1"))
			assertTrue("HtmlCompat output must retain the chapter body", rendered.contains("Round trip sentinel & readable text."))
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
