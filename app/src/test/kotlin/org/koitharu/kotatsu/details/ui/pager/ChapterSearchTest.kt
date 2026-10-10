package org.koitharu.kotatsu.details.ui.pager

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ChapterSearchTest {
	private fun row(id: Long, note: String?, title: String = "Source title $id") = ChapterListItem(
		MangaChapter(id = id, title = title, number = id.toFloat(), volume = 0, url = "/chapter/$id",
			scanlator = "Source team", uploadDate = 0, branch = "Source branch", source = MissingMangaSource("MIHON_123")),
		flags = ChapterListItem.FLAG_UNREAD,
		personalMetadata = ChapterPersonalMetadata.normalized(if (id == 1L) 4 else null, note),
	)
	private val rows = listOf(
		row(1, "Hama vs manusia, pertarungan terbaik sejauh ini"),
		row(2, "Pertemuan dengan karakter baru"),
		row(3, "Manusia mulai menyerang balik para hama"),
		row(4, null, "Hama"),
		row(5, "  ", "Hama"),
	)

	@Test fun notesMatchCaseInsensitiveSubstringsOnlyAndNotChapterTitles() {
		for (query in listOf("hama", "HaMa", "AMA", " hama ")) {
			assertEquals(listOf(1L, 3L), rows.filterChapterSearch("", query).map { it.chapter.id })
		}
	}

	@Test fun missingAndBlankNotesCannotMatch() {
		assertTrue(rows.drop(3).filterChapterSearch("", "hama").isEmpty())
	}

	@Test fun emptyAndClearedQueryRestoreTheExactInputList() {
		for (query in listOf("", " \t ")) assertSame(rows, rows.filterChapterSearch("unrelated chapter query", query))
		assertSame(rows, rows.filterChapterSearch("", null))
	}

	@Test fun emptyResultsAndEmptyInputAreSafe() {
		assertTrue(rows.filterChapterSearch("", "never mentioned").isEmpty())
		val empty = emptyList<ChapterListItem>()
		assertSame(empty, empty.filterChapterSearch("", "hama"))
	}

	@Test fun noteSearchIsSeparateAndExitRestoresThePreviousNormalChapterSearch() {
		assertEquals(listOf(4L, 5L), rows.filterChapterSearch("Hama", null).map { it.chapter.id })
		assertEquals(listOf(1L, 3L), rows.filterChapterSearch("Hama", "hama").map { it.chapter.id })
		assertEquals(listOf(4L, 5L), rows.filterChapterSearch("Hama", null).map { it.chapter.id })
		assertEquals(listOf(2L), rows.filterChapterSearch("2", null).map { it.chapter.id })
	}

	@Test fun noteFilteringPreservesExistingOrderSubsetAndAllSourceAndPersonalState() {
		val subset = listOf(rows[2], rows[1], rows[0])
		val before = subset.map { it.copy() }
		val result = subset.filterChapterSearch("", "hama")
		assertEquals(listOf(3L, 1L), result.map { it.chapter.id })
		assertSame(rows[2], result[0])
		assertSame(rows[0], result[1])
		assertEquals(before, subset)
		assertEquals(4, result[1].personalMetadata.rating)
		assertEquals("Source title 1", result[1].chapter.title)
		assertEquals("Source branch", result[1].chapter.branch)
		assertTrue(result[1].isUnread)
	}
}
