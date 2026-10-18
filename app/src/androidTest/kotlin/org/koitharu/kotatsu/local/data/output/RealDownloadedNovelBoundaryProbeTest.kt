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
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Diagnostic regression shaped after the owner-provided failing EPUB:
 * a Mihon novel volume, one large XHTML entry, and a remote chapter list enriched by one local EPUB.
 */
class RealDownloadedNovelBoundaryProbeTest {

	@Test
	fun realShapeSurvivesProducerParserDetailsMergeAndReaderBoundary() = runTest {
		val dir = Files.createTempDirectory("miyorare-real-novel-probe").toFile()
		try {
			val epub = File(dir, "Volume 3 Following their victory at the debate.epub")
			val htmlFile = File(dir, "source.html").apply { writeText(realShapeHtml()) }
			val downloaded = chapter(DOWNLOADED_CHAPTER_ID, 3f, "/book/34150/alya-volume-3")
			val remote = manga((1..8).map { number ->
				if (number == 3) downloaded else chapter(900_000_000L + number, number.toFloat(), "/book/$number")
			})

			LocalNovelEpubOutput(epub, remote.copy(chapters = listOf(downloaded))).use { output ->
				output.addPage(IndexedValue(0, downloaded), htmlFile, 0, null)
				output.finish()
			}

			val parsedLocal = LocalMangaParser(epub).getManga(withDetails = true)
			val localChapter = parsedLocal.manga.chapters?.singleOrNull()
			assertNotNull("Local parser must recover the downloaded volume", localChapter)
			assertEquals(DOWNLOADED_CHAPTER_ID, requireNotNull(localChapter).id)

			val details = MangaDetails(
				manga = remote,
				localManga = LocalManga(parsedLocal.manga, epub),
				override = null,
				description = null,
				isLoaded = true,
			)
			val merged = details.toManga()
			assertEquals(8, merged.chapters?.size)
			val mergedDownloaded = requireNotNull(merged.chapters?.firstOrNull { it.id == DOWNLOADED_CHAPTER_ID })
			assertTrue("Downloaded chapter must replace the remote URL", mergedDownloaded.url.startsWith("file+zip://"))

			val uri = mergedDownloaded.url.toUri()
			assertTrue(uri.isZipUri())
			assertEquals(epub.canonicalFile, File(uri.schemeSpecificPart).canonicalFile)
			val xhtml = ZipFile(File(uri.schemeSpecificPart)).use { zip ->
				val entry = zip.getEntry(uri.fragment.orEmpty()) ?: zip.getEntry(uri.fragment.orEmpty().removePrefix("/"))
				assertNotNull("Reader ZIP lookup must resolve the real-shaped XHTML", entry)
				zip.getInputStream(requireNotNull(entry)).bufferedReader().use { it.readText() }
			}
			assertTrue(xhtml.length > 400_000)

			val document = Jsoup.parse(xhtml)
			document.select("script,style,noscript").remove()
			val rendered = HtmlCompat.fromHtml(document.body().html(), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
			assertFalse("Large real-shaped volume must retain text through HtmlCompat", rendered.isBlank())
			assertTrue(rendered.contains("Masachika"))
			assertTrue(rendered.length > 250_000)
		} finally {
			dir.deleteRecursively()
		}
	}

	private fun realShapeHtml(): String = buildString {
		append("<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Volume 3</title></head><body><article>")
		repeat(3308) { index ->
			append("<section class=\"chapter\"><p>Masachika and Alya volume text sentinel ")
			append(index)
			append(". This paragraph intentionally mirrors the size and repeated prose structure of the failing downloaded volume. Strategy, school, conversation, and character dialogue remain readable after local EPUB round trip.</p></section>")
		}
		append("</article></body></html>")
	}

	private fun manga(chapters: List<MangaChapter>) = Manga(
		id = 9223372034906949409L,
		title = "Alya Sometimes Hides Her Feelings in Russian",
		altTitles = setOf(""),
		state = null,
		rating = -1f,
		contentRating = null,
		url = "11978/alya-sometimes-hides-her-feelings-in-russian",
		publicUrl = "https://lnori.com/series/11978/alya-sometimes-hides-her-feelings-in-russian",
		coverUrl = "https://cdn.lnori.com/cover/31780.webp",
		largeCoverUrl = "https://cdn.lnori.com/cover/31780.webp",
		authors = setOf("Sunsunsun"),
		description = null,
		source = SOURCE,
		tags = emptySet(),
		chapters = chapters,
	)

	private fun chapter(id: Long, number: Float, url: String) = MangaChapter(
		id = id,
		title = "Volume ${number.toInt()}",
		number = number,
		volume = 0,
		url = url,
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = SOURCE,
	)

	private companion object {
		const val DOWNLOADED_CHAPTER_ID = 985467770L
		val SOURCE = MangaSource("MIHON_787226943935256051")
	}
}