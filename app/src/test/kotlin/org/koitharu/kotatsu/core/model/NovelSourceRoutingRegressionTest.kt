package org.koitharu.kotatsu.core.model

import java.io.File
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
	fun `novel download streams chapter html instead of treating it as an image url`() {
		val repository = source("org/koitharu/kotatsu/mihon/MihonMangaRepository.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(repository.contains("if(source.isNovel)return@withContextpage.url"))
		assertTrue(repository.contains("if(source.isNovel){valhtml=fetchChapterText(page.url)"))
		assertTrue(repository.contains("Request.Builder().url(novelRequestUrl(page.url)).build()"))
		assertTrue(repository.contains("body(html.toResponseBody(\"application/xhtml+xml\".toMediaType()))"))
	}

	private fun source(path: String): String = sequenceOf(
		File("src/main/kotlin", path),
		File("app/src/main/kotlin", path),
	).first(File::isFile).readText()
}
