package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaState
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class SourceChapterReleasePredictionTest {

	private val source = MangaSource("MIHON_1")
	private val zone = ZoneId.of("UTC")
	private val latest = LocalDate.of(2026, 10, 3)
	private val now = latest.plusDays(4).atStartOfDay(zone).toInstant().toEpochMilli()
	private val chapters = (0L..3L).map { index ->
		MangaChapter(
			id = index + 1L, title = null, number = index + 1f, volume = 0,
			url = "/chapter-$index", scanlator = null, branch = "English", source = source,
			uploadDate = latest.minusDays((3L - index) * 7L).atStartOfDay(zone).toInstant().toEpochMilli(),
		)
	}
	private val manga = Manga(
		id = 1L, title = "Title", altTitles = emptySet(), state = MangaState.ONGOING,
		rating = 0f, contentRating = null, url = "/manga", publicUrl = "https://example.invalid/manga",
		coverUrl = null, largeCoverUrl = null, authors = emptySet(), description = null,
		source = source, tags = emptySet(), chapters = chapters,
	)
	private val details = MangaDetails(manga, null, null, null, true)

	@Test
	fun `active source and branch own prediction`() {
		assertEquals(3L, predictSourceChapterRelease(details, "English", now, zone)?.daysUntilNext)
		assertNull(predictSourceChapterRelease(details, "Spanish", now, zone))
		val foreign = chapters.map { it.copy(source = MangaSource("MIHON_2")) }
		assertNull(predictSourceChapterRelease(MangaDetails(manga.copy(chapters = foreign), null, null, null, true), "English", now, zone))
	}

	@Test
	fun `downloaded replacements cannot supply or corrupt source release evidence`() {
		val local = LocalManga(manga.copy(chapters = chapters.map { it.copy(uploadDate = 0L) }), File("/unused.cbz"))
		assertEquals(3L, predictSourceChapterRelease(details.copy(localManga = local), "English", now, zone)?.daysUntilNext)
		val noSourceDates = MangaDetails(manga.copy(chapters = chapters.map { it.copy(uploadDate = 0L) }), LocalManga(manga, File("/unused.cbz")), null, null, true)
		assertNull(predictSourceChapterRelease(noSourceDates, "English", now, zone))
	}

	@Test
	fun `local unloaded and known non ongoing titles have no estimate`() {
		assertNull(predictSourceChapterRelease(null, "English", now, zone))
		assertNull(predictSourceChapterRelease(details.copy(isLoaded = false), "English", now, zone))
		assertNull(predictSourceChapterRelease(MangaDetails(manga.copy(source = LocalMangaSource), null, null, null, true), "English", now, zone))
		for (state in listOf(MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED, MangaState.UPCOMING, MangaState.RESTRICTED)) {
			assertNull(predictSourceChapterRelease(MangaDetails(manga.copy(state = state), null, null, null, true), "English", now, zone))
		}
	}
}
