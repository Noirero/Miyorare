package org.koitharu.kotatsu.local.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File

class LegacyChapterDownloadCompatTest {

	@Test
	fun `sidecar free cbz is rekeyed to remote chapter id and keeps local url`() {
		val remoteSource = MangaSource("MIHON_1")
		val remoteChapter = MangaChapter(
			id = 100L,
			title = "Chapter 1",
			number = 1f,
			volume = 0,
			url = "/remote/chapter-1",
			scanlator = "Team",
			uploadDate = 0L,
			branch = null,
			source = remoteSource,
		)
		val remote = manga(
			id = 10L,
			source = remoteSource,
			chapters = listOf(remoteChapter),
		)
		val localUrl = "file:///tmp/Manga/Team_Chapter%201.cbz"
		val localChapter = remoteChapter.copy(
			id = 999L,
			url = localUrl,
			source = LocalMangaSource,
		)
		val local = LocalManga(
			manga = manga(
				id = 777L,
				source = LocalMangaSource,
				chapters = listOf(localChapter),
			),
			file = File("/tmp/Manga"),
		)

		val linked = LegacyChapterDownloadCompat.linkToRemote(remote, local)
		val linkedChapter = requireNotNull(linked.manga.chapters).single()

		assertEquals(remote.id, linked.manga.id)
		assertEquals(remoteChapter.id, linkedChapter.id)
		assertEquals(localUrl, linkedChapter.url)
		assertSame(LocalMangaSource, linkedChapter.source)
	}

	private fun manga(
		id: Long,
		source: org.koitharu.kotatsu.parsers.model.MangaSource,
		chapters: List<MangaChapter>,
	) = Manga(
		id = id,
		title = "Manga",
		altTitles = emptySet(),
		state = null,
		rating = 0f,
		contentRating = null,
		url = "/manga",
		publicUrl = "https://example.invalid/manga",
		coverUrl = null,
		largeCoverUrl = null,
		authors = emptySet(),
		description = null,
		source = source,
		tags = emptySet(),
		chapters = chapters,
	)
}
