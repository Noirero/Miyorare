package org.koitharu.kotatsu.details.ui

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.details.data.ChapterPersonalKey
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ChapterGridNumberTest {
	@Test fun structuredNumbersWinOverGenericSourceTitles() {
		assertEquals("1", chapter(1f, "CH").gridNumberLabel())
		assertEquals("12.5", chapter(12.5f, "CH").gridNumberLabel())
		assertEquals("0.5", chapter(0.5f, "Prologue").gridNumberLabel())
	}

	@Test fun missingSpecialOrInvalidNumbersUseTheSourceTitleFallback() {
		for (title in listOf("Extra", "One-shot", "Special 2026", null)) {
			assertNull(chapter(0f, title).gridNumberLabel())
			assertNull(chapter(-1f, title).gridNumberLabel())
		}
		assertNull(chapter(Float.NaN, "Extra").gridNumberLabel())
		assertNull(chapter(Float.POSITIVE_INFINITY, "Extra").gridNumberLabel())
	}

	@Test fun locatorIdentityIgnoresOrderGeneratedIdsAndPresentationChanges() {
		val a = chapter(1f, "CH")
		assertEquals(ChapterPersonalKey.of(a), ChapterPersonalKey.of(a.copy(id = 999, title = "Renamed", number = 9f)))
		assertNotEquals(ChapterPersonalKey.of(a), ChapterPersonalKey.of(a.copy(url = "/chapter/b")))
		assertNotEquals(ChapterPersonalKey.of(a), ChapterPersonalKey.of(a.copy(source = MissingMangaSource("MIHON_456"))))
	}

	private fun chapter(number: Float, title: String?) = MangaChapter(
		id = 1, title = title, number = number, volume = 0, url = "/chapter/a",
		scanlator = null, uploadDate = 0, branch = null, source = MissingMangaSource("MIHON_123"),
	)
}
