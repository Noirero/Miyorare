package org.koitharu.kotatsu.details.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.details.data.ChapterPersonalKey
import org.koitharu.kotatsu.test.SampleData

class ChapterPersonalIdentityRegressionTest {

	@Test
	fun `downloaded local chapter keeps authoritative source personal key when id changes`() {
		val source = SampleData.chapter
		val downloaded = source.copy(
			id = source.id + 1000L,
			url = "file:///private/download/chapter.cbz",
			source = LocalMangaSource,
		)

		assertEquals(
			ChapterPersonalKey.of(source),
			resolveChapterPersonalKey(downloaded, listOf(source)),
		)
	}

	@Test
	fun `source chapter keeps the same personal key before and after local ownership changes`() {
		val source = SampleData.chapter
		val downloaded = source.copy(
			id = source.id + 1000L,
			url = "file:///private/download/chapter.cbz",
			source = LocalMangaSource,
		)

		val beforeDownload = resolveChapterPersonalKey(source, listOf(source))
		val whileDownloaded = resolveChapterPersonalKey(downloaded, listOf(source))
		val afterDelete = resolveChapterPersonalKey(source, listOf(source))

		assertEquals(beforeDownload, whileDownloaded)
		assertEquals(beforeDownload, afterDelete)
	}

	@Test
	fun `ambiguous reconciliation never invents a source personal key`() {
		val source = SampleData.chapter
		val secondSource = source.copy(id = source.id + 1L, url = source.url + "?alternate=1")
		val downloaded = source.copy(
			id = source.id + 1000L,
			url = "file:///private/download/chapter.cbz",
			source = LocalMangaSource,
		)

		assertEquals(
			ChapterPersonalKey.of(downloaded),
			resolveChapterPersonalKey(downloaded, listOf(source, secondSource)),
		)
	}
}
