package org.koitharu.kotatsu.core.model

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelSourceRoutingRegressionTest {

	@Test
	fun `novel source classification feeds the text reader route`() {
		val epubClassifier = source("org/koitharu/kotatsu/local/data/CbzFilter.kt")
			.replace(Regex("\\s+"), "")
		val readerActivity = source("org/koitharu/kotatsu/reader/ui/ReaderActivity.kt")
			.replace(Regex("\\s+"), "")
		val readerManager = source("org/koitharu/kotatsu/reader/ui/ReaderManager.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(epubClassifier.contains("get()=source.isNovelContentSource||"))
		assertTrue(readerActivity.contains("readerManager.isEpub=viewModel.getMangaOrNull()?.isEpub==true"))
		assertTrue(readerManager.contains("valreaderClass=if(isEpub){"))
		assertTrue(readerManager.contains("EpubReaderFragment::class.java"))
	}

	@Test
	fun `novel reader preserves source page contract before fetching text`() {
		val repository = source("org/koitharu/kotatsu/mihon/MihonMangaRepository.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(repository.contains("mihonSource.getPageList(chapter.toSourceChapter())"))
		assertTrue(repository.contains("getSourcePages(chapter).joinToString(separator=\"\\n\"){page->fetchPageText(page)}"))
		assertTrue(repository.contains("mihonSource.fetchPageText(page)"))
		assertFalse(repository.contains("fetchPageText(Page(index=0,url=chapterUrl))"))
	}

	@Test
	fun `novel download preserves source page metadata while streaming xhtml`() {
		val repository = source("org/koitharu/kotatsu/mihon/MihonMangaRepository.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(repository.contains("host=MIHON_TEXT_HOST"))
		assertTrue(repository.contains("index=page.index"))
		assertTrue(repository.contains("imageUrl=page.imageUrl"))
		assertTrue(repository.contains("valsourcePage=page.toNovelPage(pageUrl)"))
		assertTrue(repository.contains("Request.Builder().url(novelRequestUrl(sourcePage.url)).build()"))
		assertTrue(repository.contains("body(html.toResponseBody(\"application/xhtml+xml\".toMediaType()))"))
	}

	private fun source(path: String): String = sequenceOf(
		File("src/main/kotlin", path),
		File("app/src/main/kotlin", path),
	).first(File::isFile).readText()
}
