package org.koitharu.kotatsu.details.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.data.ReleasePredictionDate
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaState
import java.time.LocalDate
import java.time.ZoneId

class FavouriteReleasePredictionEvidenceTest {
	private val zone = ZoneId.of("UTC")
	private val latest = LocalDate.of(2026, 10, 3)
	private val now = latest.plusDays(4).atStartOfDay(zone).toInstant().toEpochMilli()
	private val manga = Manga(
		id = 1L, title = "Original", altTitles = emptySet(), state = MangaState.ONGOING, rating = 0f,
		contentRating = null, url = "/manga", publicUrl = "https://example.invalid/manga", coverUrl = null,
		largeCoverUrl = null, authors = emptySet(), description = null, source = MangaSource("MIHON_1"), tags = emptySet(), chapters = null,
	)
	private fun evidence(
		branch: String? = "English",
		latestDate: LocalDate = latest,
		count: Int = 4,
		scanlator: String? = null,
		cadence: Long = 7L,
	) = (0 until count).map {
		ReleasePredictionDate(
			1L,
			branch,
			latestDate.minusDays(it * cadence).atStartOfDay(zone).toInstant().toEpochMilli(),
			scanlator,
		)
	}

	@Test fun `valid persisted history produces the existing estimate and exact history`() {
		val dates = evidence()
		val item = checkNotNull(persistedReleasePredictionItem(manga, dates, now, zone))
		assertEquals(NextChapterReleaseEstimator.predict(dates.map { it.uploadDate }, now, zone), item.prediction)
		assertEquals(latest.plusDays(7), item.prediction.expectedDate)
		assertEquals(3L, item.prediction.daysUntilNext)
		assertEquals("English", item.branch)
		assertNull(item.scanlator)
		assertEquals((0L..3L).map { latest.minusDays((3L - it) * 7L) }, item.releaseDates)
	}

	@Test fun `insufficient missed irregular and ineligible evidence cannot populate picker`() {
		assertNull(persistedReleasePredictionItem(manga, emptyList(), now, zone))
		assertNull(persistedReleasePredictionItem(manga, evidence(count = 3), now, zone))
		assertNull(persistedReleasePredictionItem(manga, evidence(latestDate = latest.minusDays(30)), now, zone))
		val irregular = listOf(0L, 3L, 20L, 50L).map { ReleasePredictionDate(1, "English", latest.minusDays(it).atStartOfDay(zone).toInstant().toEpochMilli()) }
		assertNull(persistedReleasePredictionItem(manga, irregular, now, zone))
		assertNull(persistedReleasePredictionItem(manga.copy(source = LocalMangaSource), evidence(), now, zone))
		for (state in listOf(MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED, MangaState.UPCOMING, MangaState.RESTRICTED)) {
			assertNull(persistedReleasePredictionItem(manga.copy(state = state), evidence(), now, zone))
		}
	}

	@Test fun `branches manga ids and named scanlators cannot fill each others missing evidence`() {
		// Each group is insufficient even though combining them would manufacture a valid cadence.
		assertNull(persistedReleasePredictionItem(manga, evidence("English", count = 2) + evidence("Spanish", latest.minusDays(14), 2), now, zone))
		assertNull(persistedReleasePredictionItem(manga, evidence().map { it.copy(mangaId = 2) }, now, zone))
		val groupA = evidence(count = 3, scanlator = "Group A")
		val groupB = evidence(latestDate = latest.minusDays(21), count = 3, scanlator = "Group B")
		assertNull(persistedReleasePredictionItem(manga, groupA + groupB, now, zone))
	}

	@Test fun `single named scanlator and missing rows keep branch fallback`() {
		val dates = evidence().mapIndexed { index, item ->
			item.copy(scanlator = if (index < 2) "  Group A  " else if (index == 2) "   " else null)
		}
		val item = checkNotNull(persistedReleasePredictionItem(manga, dates, now, zone))
		assertNull(item.scanlator)
		assertEquals(latest.plusDays(7), item.prediction.expectedDate)
	}

	@Test fun `bulk reupload scanlator does not corrupt regular Favorite lineage`() {
		val regular = evidence(scanlator = "Group A")
		val burstTime = latest.atStartOfDay(zone).toInstant().toEpochMilli()
		val burst = (0 until 20).map { index -> ReleasePredictionDate(1L, "English", burstTime + index * 1000L, "Group B") }
		val item = checkNotNull(persistedReleasePredictionItem(manga, regular + burst, now, zone))
		assertEquals("Group A", item.scanlator)
		assertEquals(latest.plusDays(7), item.prediction.expectedDate)
	}

	@Test fun `Favorite lineage prefers recency before predicted date`() {
		val english = evidence("English")
		val spanish = evidence("Spanish", latest.minusDays(1))
		val item = checkNotNull(persistedReleasePredictionItem(manga, english + spanish, now, zone))
		assertEquals("English", item.branch)
		assertEquals(latest.plusDays(7), item.prediction.expectedDate)
		assertTrue(item.releaseDates.contains(latest))
	}

	@Test fun `Favorite lineage prefers stronger reliability when recency ties`() {
		val regular = evidence("English", count = 5)
		val noisyDates = listOf(
			latest.minusDays(41), latest.minusDays(34), latest.minusDays(27), latest.minusDays(20), latest,
		).map { ReleasePredictionDate(1L, "Spanish", it.atStartOfDay(zone).toInstant().toEpochMilli()) }
		val item = checkNotNull(persistedReleasePredictionItem(manga, noisyDates + regular, now, zone))
		assertEquals("English", item.branch)
	}

	@Test fun `Favorite lineage tie break is deterministic regardless of input order`() {
		val english = evidence("English")
		val spanish = evidence("Spanish")
		val forward = checkNotNull(persistedReleasePredictionItem(manga, english + spanish, now, zone))
		val reversed = checkNotNull(persistedReleasePredictionItem(manga, (english + spanish).reversed(), now, zone))
		assertEquals("English", forward.branch)
		assertEquals(forward.branch, reversed.branch)
		assertEquals(forward.prediction, reversed.prediction)
	}

	@Test fun `original Details marker context uses source branch rather than merged display chapters`() {
		val chapters = evidence().mapIndexed { index, date -> MangaChapter(
			id = index.toLong(), title = null, number = index.toFloat(), volume = 0, url = "/$index",
			scanlator = null, branch = date.branch, source = manga.source, uploadDate = date.uploadDate,
		) }
		val foreign = chapters.map { it.copy(id = it.id + 10, source = MangaSource("MIHON_2"), uploadDate = now) }
		val otherBranch = chapters.map { it.copy(id = it.id + 20, branch = "Spanish", uploadDate = now) }
		val details = MangaDetails(manga.copy(chapters = chapters + foreign + otherBranch), null, null, null, true)
		val original = checkNotNull(sourceReleasePredictionItem(details, "English", now, zone))
		assertEquals(latest, original.releaseDates.last())
		assertFalse(original.releaseDates.contains(latest.plusDays(4)))
		assertNull(original.manga.chapters)
		assertEquals(predictSourceChapterRelease(details, "English", now, zone), original.prediction)
	}

	@Test fun `upcoming ordering uses expected dates with deterministic ties instead of countdowns`() {
		val original = checkNotNull(persistedReleasePredictionItem(manga, evidence(), now, zone))
		val sooner = original.copy(manga = manga.copy(id = 2, title = "Z"), prediction = NextChapterReleasePrediction(latest.plusDays(6), 99))
		val tie = original.copy(manga = manga.copy(id = 3, title = "Original"))
		assertEquals(listOf(2L, 1L, 3L), listOf(tie, original, sooner).sortedWith(releasePredictionOrder).map { it.manga.id })
	}
}
