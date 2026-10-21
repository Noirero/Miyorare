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
	private val chapters = chaptersFor(scanlator = null, latestDate = latest, cadence = 7L)
	private val manga = Manga(
		id = 1L, title = "Title", altTitles = emptySet(), state = MangaState.ONGOING,
		rating = 0f, contentRating = null, url = "/manga", publicUrl = "https://example.invalid/manga",
		coverUrl = null, largeCoverUrl = null, authors = emptySet(), description = null,
		source = source, tags = emptySet(), chapters = chapters,
	)
	private val details = MangaDetails(manga, null, null, null, true)

	private fun chaptersFor(
		scanlator: String?,
		latestDate: LocalDate,
		cadence: Long,
		count: Int = 4,
		idBase: Long = 0L,
		branch: String? = "English",
	): List<MangaChapter> = (0 until count).map { index ->
		MangaChapter(
			id = idBase + index + 1L, title = null, number = index + 1f, volume = 0,
			url = "/chapter-${idBase + index}", scanlator = scanlator, branch = branch, source = source,
			uploadDate = latestDate.minusDays((count - 1L - index) * cadence).atStartOfDay(zone).toInstant().toEpochMilli(),
		)
	}

	@Test
	fun `active source and branch own prediction`() {
		assertEquals(3L, predictSourceChapterRelease(details, "English", now, zone)?.daysUntilNext)
		assertNull(predictSourceChapterRelease(details, "Spanish", now, zone))
		val foreign = chapters.map { it.copy(source = MangaSource("MIHON_2")) }
		assertNull(predictSourceChapterRelease(MangaDetails(manga.copy(chapters = foreign), null, null, null, true), "English", now, zone))
	}

	@Test
	fun `single named scanlator keeps branch fallback including missing metadata`() {
		val split = chapters.take(2).map { it.copy(scanlator = "  Group A  ") } + chapters.drop(2)
		val splitDetails = MangaDetails(manga.copy(chapters = split), null, null, null, true)
		val item = checkNotNull(sourceReleasePredictionItem(splitDetails, "English", now, zone))
		assertEquals(3L, item.prediction.daysUntilNext)
		assertNull(item.scanlator)
	}

	@Test
	fun `multiple named scanlators never mix and selected scanlator owns Details prediction`() {
		val groupA = chaptersFor("Group A", latest, 7L, idBase = 100L)
		val groupB = chaptersFor("Group B", latest.minusDays(1), 14L, idBase = 200L)
		val mixed = MangaDetails(manga.copy(chapters = groupA + groupB), null, null, null, true)

		val automatic = checkNotNull(sourceReleasePredictionItem(mixed, "English", now, zone))
		assertEquals("Group A", automatic.scanlator)
		assertEquals(latest.plusDays(7), automatic.prediction.expectedDate)

		val selectedB = checkNotNull(sourceReleasePredictionItem(mixed, "English", " Group B ", now, zone))
		assertEquals("Group B", selectedB.scanlator)
		assertEquals(latest.minusDays(1).plusDays(14), selectedB.prediction.expectedDate)
		assertNull(sourceReleasePredictionItem(mixed, "English", "Missing", now, zone))
	}

	@Test
	fun `bulk reupload from another scanlator cannot corrupt regular lineage`() {
		val groupA = chaptersFor("Group A", latest, 7L, idBase = 300L)
		val burstMillis = latest.atStartOfDay(zone).toInstant().toEpochMilli()
		val groupB = (0 until 20).map { index ->
			chapters.first().copy(id = 400L + index, url = "/burst/$index", scanlator = "Group B", uploadDate = burstMillis + index * 1000L)
		}
		val mixed = MangaDetails(manga.copy(chapters = groupA + groupB), null, null, null, true)
		val item = checkNotNull(sourceReleasePredictionItem(mixed, "English", now, zone))
		assertEquals("Group A", item.scanlator)
		assertEquals(latest.plusDays(7), item.prediction.expectedDate)
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
