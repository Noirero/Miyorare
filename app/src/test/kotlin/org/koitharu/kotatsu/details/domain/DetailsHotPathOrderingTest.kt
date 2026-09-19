package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

class DetailsHotPathOrderingTest {

	@Test
	fun `cached chapters emit before local lookup`() = runTest {
		var localLookupStarted = false

		val first = flow {
			emitRemoteInitialSnapshot(
				manga = manga(listOf(chapter())),
				override = null,
				description = null,
				cachedIsFresh = false,
			) {
				localLookupStarted = true
				error("Local lookup must not gate the first cached chapter emission")
			}
		}.first()

		assertEquals(1, first.allChapters.size)
		assertFalse(localLookupStarted)
	}

	@Test
	fun `without cached chapters local lookup remains first`() = runTest {
		var localLookupStarted = false

		val first = flow {
			emitRemoteInitialSnapshot(
				manga = manga(chapters = null),
				override = null,
				description = null,
				cachedIsFresh = false,
			) {
				localLookupStarted = true
				null
			}
		}.first()

		assertTrue(localLookupStarted)
		assertTrue(first.allChapters.isEmpty())
	}

	private fun manga(chapters: List<MangaChapter>?) = Manga(
		id = 1L,
		title = "Cached title",
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
		source = MangaSource("MIHON_1"),
		tags = emptySet(),
		chapters = chapters,
	)

	private fun chapter() = MangaChapter(
		id = 2L,
		title = "Chapter 1",
		number = 1f,
		volume = 0,
		url = "/chapter-1",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MangaSource("MIHON_1"),
	)
}
