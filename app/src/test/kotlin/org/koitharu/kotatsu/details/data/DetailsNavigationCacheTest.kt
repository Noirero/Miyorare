package org.koitharu.kotatsu.details.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.time.Instant

class DetailsNavigationCacheTest {

	@Test
	fun `remote history hint does not retain chapter-bearing manga`() {
		val cache = DetailsNavigationCache()
		val history = history(chapterId = 2L)

		cache.updateHistory(listOf(1L)) { history }

		assertSame(history, cache.getHistory(1L))
		assertNull(cache.getLocalManga(1L))
	}

	@Test
	fun `remote chapter-bearing manga is rejected from Local snapshot storage`() {
		val cache = DetailsNavigationCache()
		val remote = manga(
			id = 5L,
			source = MangaSource("MIHON_1"),
			chapters = listOf(chapter(id = 6L)),
		)

		cache.putLocalAll(listOf(remote))

		assertNull(cache.getLocalManga(remote.id))
	}

	@Test
	fun `local snapshot keeps chapter-bearing manga and history independently`() {
		val cache = DetailsNavigationCache()
		val local = manga(
			id = 10L,
			source = LocalMangaSource,
			chapters = listOf(chapter(id = 11L, source = LocalMangaSource)),
		)
		val history = history(chapterId = 11L)

		cache.putLocalAll(listOf(local))
		cache.updateHistory(listOf(local.id)) { history }

		assertSame(local, cache.getLocalManga(local.id))
		assertSame(history, cache.getHistory(local.id))

		cache.updateHistory(listOf(local.id)) { null }

		assertSame(local, cache.getLocalManga(local.id))
		assertNull(cache.getHistory(local.id))
	}

	@Test
	fun `chapterless local item is not retained as a heavy navigation snapshot`() {
		val cache = DetailsNavigationCache()
		val local = manga(
			id = 20L,
			source = LocalMangaSource,
			chapters = null,
		)

		cache.putLocalAll(listOf(local))

		assertNull(cache.getLocalManga(local.id))
	}

	private fun history(chapterId: Long) = MangaHistory(
		createdAt = Instant.EPOCH,
		updatedAt = Instant.EPOCH,
		chapterId = chapterId,
		page = 0,
		scroll = 0,
		percent = 0f,
		chaptersCount = 1,
	)

	private fun manga(
		id: Long,
		source: org.koitharu.kotatsu.parsers.model.MangaSource,
		chapters: List<MangaChapter>?,
	) = Manga(
		id = id,
		title = "Cache test",
		altTitles = emptySet(),
		state = null,
		rating = 0f,
		contentRating = null,
		url = "/manga-$id",
		publicUrl = "https://example.invalid/manga-$id",
		coverUrl = null,
		largeCoverUrl = null,
		authors = emptySet(),
		description = null,
		source = source,
		tags = emptySet(),
		chapters = chapters,
	)

	private fun chapter(
		id: Long,
		source: org.koitharu.kotatsu.parsers.model.MangaSource = MangaSource("MIHON_1"),
	) = MangaChapter(
		id = id,
		title = "Chapter 1",
		number = 1f,
		volume = 0,
		url = "/chapter-$id",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = source,
	)
}
