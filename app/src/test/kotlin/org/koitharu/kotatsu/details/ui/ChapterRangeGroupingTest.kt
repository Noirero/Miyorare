package org.koitharu.kotatsu.details.ui

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.pager.ChapterGroupingMode
import org.koitharu.kotatsu.details.ui.pager.filterChapterSearch
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.MissingChapters
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ChapterRangeGroupingTest {
	private fun row(id: Long, number: Float = id.toFloat(), read: Boolean = false, current: Boolean = false) =
		ChapterListItem(
			chapter = MangaChapter(id = id, title = "Chapter $number", number = number, volume = 0,
				url = "/chapter/$id", scanlator = "Team $id", uploadDate = 0, branch = null,
				source = MissingMangaSource("MIHON_123")),
			flags = ((if (read) 0 else ChapterListItem.FLAG_UNREAD.toInt()) or
				(if (current) ChapterListItem.FLAG_CURRENT.toInt() else 0)).toByte(),
		)

	private fun rows(count: Int) = (1..count).map { row(it.toLong()) }

	@Test fun automaticBoundaryAndPredictableAdaptiveSize() {
		for (count in listOf(0, 1, 50)) assertTrue(groupChapterRanges(rows(count), ChapterGroupingMode.AUTOMATIC).isEmpty())
		for ((count, ranges) in listOf(51 to 2, 100 to 2, 101 to 3, 500 to 10, 501 to 6, 1000 to 10)) {
			val grouped = groupChapterRanges(rows(count), ChapterGroupingMode.AUTOMATIC)
			assertEquals(ranges, grouped.size)
			assertEquals(count, grouped.sumOf { it.totalCount })
		}
	}

	@Test fun manualModesAreDeterministicEvenForShortCollections() {
		for ((mode, size) in listOf(ChapterGroupingMode.CHAPTERS_25 to 25,
			ChapterGroupingMode.CHAPTERS_50 to 50, ChapterGroupingMode.CHAPTERS_100 to 100)) {
			assertTrue(groupChapterRanges(emptyList(), mode).isEmpty())
			assertEquals("1–1", groupChapterRanges(rows(1), mode).single().numberLabel)
			assertEquals(size, groupChapterRanges(rows(101), mode).first().totalCount)
			assertEquals(rows(101), groupChapterRanges(rows(101), mode).flatMap { it.chapters })
		}
		assertTrue(groupChapterRanges(rows(1000), ChapterGroupingMode.OFF).isEmpty())
	}

	@Test fun finalPartialRangeUsesActualChapterCountAndNumber() {
		val grouped = groupChapterRanges(rows(156), ChapterGroupingMode.CHAPTERS_50)
		assertEquals(listOf("1–50", "51–100", "101–150", "151–156"), grouped.map { it.numberLabel })
		assertEquals(listOf(50, 50, 50, 6), grouped.map { it.totalCount })
	}

	@Test fun numbersNotListPositionsDefineBucketsAndGapsDoNotCreateEmptyRanges() {
		val input = listOf(row(1, 10f), row(2, 73f), row(3, 156f))
		val grouped = groupChapterRanges(input, ChapterGroupingMode.CHAPTERS_50)
		assertEquals(listOf("1–50", "51–100", "151–156"), grouped.map { it.numberLabel })
		assertEquals(listOf(1, 1, 1), grouped.map { it.totalCount })
		assertEquals(input, grouped.flatMap { it.chapters })
	}

	@Test fun fractionalBoundariesStayWithTheirIntegerChapterAndRemainVisible() {
		val input = listOf(row(1, 10.5f), row(2, 50f), row(3, 50.5f), row(4, 51f), row(5, 100.5f))
		val grouped = groupChapterRanges(input, ChapterGroupingMode.CHAPTERS_50)
		assertEquals(listOf("1–50.5", "51–100.5"), grouped.map { it.numberLabel })
		assertEquals(input.take(3), grouped.first().chapters)
		assertEquals(input, grouped.flatMap { it.chapters })
	}

	@Test fun duplicateNumbersAndIdsRetainDistinctSourceRowsAndMetadata() {
		val first = row(5, 10f).copy(personalMetadata = ChapterPersonalMetadata(4, "Keep my note"))
		val second = first.copy(chapter = first.chapter.copy(url = "/other-team/10", scanlator = "Other team"))
		val grouped = groupChapterRanges(listOf(first, second), ChapterGroupingMode.CHAPTERS_25).single()
		assertEquals(2, grouped.totalCount)
		assertSame(first, grouped.chapters[0])
		assertSame(second, grouped.chapters[1])
		assertEquals(4, grouped.chapters[0].personalMetadata.rating)
		assertEquals("Keep my note", grouped.chapters[0].personalMetadata.note)
	}

	@Test fun zeroNegativeNonFiniteAndSubOneNumbersUseSpecialSectionsWithoutLoss() {
		val input = listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.5f, Float.MAX_VALUE)
			.mapIndexed { index, number -> row(index.toLong(), number) } + row(100, 1f)
		val grouped = groupChapterRanges(input, ChapterGroupingMode.CHAPTERS_50)
		assertNull(grouped.first().numberLabel)
		assertEquals(7, grouped.first().totalCount)
		assertEquals(input, grouped.flatMap { it.chapters })
	}

	@Test fun descendingAndNonNumberSortsPreserveTheEntireExistingOrder() {
		val descending = rows(156).asReversed()
		val grouped = groupChapterRanges(descending, ChapterGroupingMode.CHAPTERS_50)
		assertEquals(listOf("151–156", "101–150", "51–100", "1–50"), grouped.map { it.numberLabel })
		assertEquals(descending, grouped.flatMap { it.chapters })
		val interleaved = listOf(row(1, 73f), row(2, 10f), row(3, 74f), row(4, 11f))
		val runs = groupChapterRanges(interleaved, ChapterGroupingMode.CHAPTERS_50)
		assertEquals(interleaved, runs.flatMap { it.chapters })
		assertEquals(4, runs.map { it.key }.distinct().size)
	}

	@Test fun progressReflectsExistingReadFlagsIncludingTheFinalPartialRange() {
		for (readCount in listOf(0, 3, 6)) {
			val input = (151..156).map { row(it.toLong(), read = it < 151 + readCount) }
			val range = groupChapterRanges(input, ChapterGroupingMode.CHAPTERS_50).single()
			assertEquals(6, range.totalCount)
			assertEquals(readCount, range.readCount)
		}
	}

	@Test fun currentProgressInLaterRangeOpensItWithoutUsingHistoryTimestamps() {
		val input = rows(200).map { if (it.chapter.id == 167L) it.copy(flags = ChapterListItem.FLAG_CURRENT) else it }
		val ranges = groupChapterRanges(input, ChapterGroupingMode.AUTOMATIC).withRangeExpansion(emptyMap(), false)
		assertEquals(listOf(false, false, false, true), ranges.map { it.expanded })
	}

	@Test fun noCurrentProgressOpensFirstPresentedRangePredictably() {
		for (input in listOf(rows(156), rows(156).asReversed())) {
			val ranges = groupChapterRanges(input, ChapterGroupingMode.AUTOMATIC).withRangeExpansion(emptyMap(), false)
			assertTrue(ranges.first().expanded)
			assertTrue(ranges.drop(1).none { it.expanded })
		}
	}

	@Test fun absentReadingPositionDoesNotTreatTheMapperZeroIdSentinelAsProgress() {
		val input = rows(100) + row(0, 167f, current = true)
		val ranges = groupChapterRanges(input, ChapterGroupingMode.AUTOMATIC)
			.withRangeExpansion(emptyMap(), false, hasReadingPosition = false)
		assertEquals(listOf(true, false, false), ranges.map { it.expanded })
	}

	@Test fun independentTogglesAndExplicitCurrentCollapseSurviveOrdinaryReadUpdates() {
		val ranges = groupChapterRanges(rows(156), ChapterGroupingMode.AUTOMATIC)
		val overrides = mapOf(ranges[0].key to false, ranges[1].key to true, ranges[3].key to true)
		assertEquals(listOf(false, true, false, true), ranges.withRangeExpansion(overrides, false).map { it.expanded })
		val current = rows(156).map { if (it.chapter.id == 1L) it.copy(flags = ChapterListItem.FLAG_CURRENT) else it }
		assertFalse(groupChapterRanges(current, ChapterGroupingMode.AUTOMATIC).withRangeExpansion(overrides, false)[0].expanded)
	}

	@Test fun changingModesAndOffThenAutomaticDoesNotLoseOrReinterpretChapters() {
		val input = rows(156)
		val original = groupChapterRanges(input, ChapterGroupingMode.AUTOMATIC)
		val overrides = mapOf(original.last().key to true)
		for (mode in ChapterGroupingMode.entries) {
			val grouped = groupChapterRanges(input, mode)
			if (mode != ChapterGroupingMode.OFF) assertEquals(input, grouped.flatMap { it.chapters })
		}
		assertEquals(original, groupChapterRanges(input, ChapterGroupingMode.AUTOMATIC))
		assertTrue(groupChapterRanges(input, ChapterGroupingMode.AUTOMATIC).withRangeExpansion(overrides, false).last().expanded)
	}

	@Test fun statusFilteringGroupsOnlyTheEffectiveCollectionAndDerivesItsProgress() {
		val input = rows(156).map { if (it.chapter.id % 2L == 0L) it.copy(flags = ChapterListItem.FLAG_DOWNLOADED) else it }
		val filtered = input.filter { it.isDownloaded }
		val grouped = groupChapterRanges(filtered, ChapterGroupingMode.CHAPTERS_50)
		assertEquals(filtered, grouped.flatMap { it.chapters })
		assertEquals(3, grouped.last().totalCount)
		assertEquals(3, grouped.last().readCount)
	}

	@Test fun searchExposesMatchesAcrossCollapsedRangesAndKeepsOriginalBoundsAndKeys() {
		val input = rows(156)
		val original = groupChapterRanges(input, ChapterGroupingMode.AUTOMATIC)
		val closed = original.associate { it.key to false }
		val matches = input.filterChapterSearch("73", null)
		val searched = groupChapterRanges(input, ChapterGroupingMode.AUTOMATIC, matches).withRangeExpansion(closed, true)
		assertEquals(1, searched.size)
		assertEquals(original[1].key, searched.single().key)
		assertEquals("51–100", searched.single().numberLabel)
		assertTrue(searched.single().expanded)
		assertEquals(matches, searched.flatMap { it.chapters })
		assertTrue(original.withRangeExpansion(closed, false).none { it.expanded })
	}

	@Test fun noteSearchPreservesRatingAndExposesAllMatchingSections() {
		val input = rows(156).map { if (it.chapter.id in setOf(73L, 153L))
			it.copy(personalMetadata = ChapterPersonalMetadata(5, "My Hama note")) else it }
		val matches = input.filterChapterSearch("", "hama")
		val ranged = groupChapterRanges(input, ChapterGroupingMode.AUTOMATIC, matches).withRangeExpansion(emptyMap(), true)
		assertEquals(listOf("51–100", "151–156"), ranged.map { it.numberLabel })
		assertTrue(ranged.all { it.expanded })
		assertEquals(matches, ranged.flatMap { it.chapters })
		assertTrue(ranged.flatMap { it.chapters }.all { it.personalMetadata.rating == 5 })
	}

	@Test fun emptySearchResultsDoNotCreatePhantomHeaders() {
		assertTrue(groupChapterRanges(rows(156), ChapterGroupingMode.AUTOMATIC, emptyList()).isEmpty())
	}

	@Test fun manageProjectionPreservesDenseRowsAndExistingVolumeAndMissingDecorations() {
		val input = listOf(row(1, 1f), row(2, 3f), row(3, 51f))
		val volume = ListHeader("Volume 1", buttonTextRes = 1, payload = 1, badge = "3")
		val missing = MissingChapters("missing-1-3", 1)
		val decorated = listOf(volume, input[0], missing, input[1], input[2])
		val grouped = groupChapterRanges(input, ChapterGroupingMode.CHAPTERS_50)
		val allOpen = grouped.withRangeExpansion(grouped.associate { it.key to true }, false)
		assertEquals(listOf(allOpen[0], volume, input[0], missing, input[1], allOpen[1], input[2]), allOpen.withRangeHeaders(decorated))
		assertEquals(grouped, grouped.withRangeHeaders(decorated))
		assertSame(decorated, emptyList<org.koitharu.kotatsu.details.ui.model.ChapterRange>().withRangeHeaders(decorated))
	}
}
