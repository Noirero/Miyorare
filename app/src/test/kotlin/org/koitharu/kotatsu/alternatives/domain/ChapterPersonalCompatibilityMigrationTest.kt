package org.koitharu.kotatsu.alternatives.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ChapterPersonalCompatibilityMigrationTest {

	@Test
	fun `legacy migration moves chapter note and rating with bookmark chapter mapping`() {
		val oldChapter = chapter(101L, "OLD", "/old/7")
		val newChapter = chapter(201L, "NEW", "/new/7")
		val result = mapChapterPersonalCompatibility(
			oldMangaId = 1L,
			newMangaId = 2L,
			oldChapters = listOf(oldChapter),
			newChapters = listOf(newChapter),
			chapterIds = mapOf(oldChapter.id to newChapter.id),
			sourceEntries = listOf(
				ChapterPersonalEntity(1L, oldChapter.source.name, oldChapter.url, 5, "Keep this note"),
			),
			targetEntries = emptyList(),
		)

		assertEquals(
			listOf(ChapterPersonalEntity(2L, newChapter.source.name, newChapter.url, 5, "Keep this note")),
			result,
		)
	}

	@Test
	fun `destination annotation wins while source fills missing fields`() {
		val oldChapter = chapter(301L, "OLD", "/old/9")
		val newChapter = chapter(401L, "NEW", "/new/9")
		val result = mapChapterPersonalCompatibility(
			oldMangaId = 3L,
			newMangaId = 4L,
			oldChapters = listOf(oldChapter),
			newChapters = listOf(newChapter),
			chapterIds = mapOf(oldChapter.id to newChapter.id),
			sourceEntries = listOf(
				ChapterPersonalEntity(3L, oldChapter.source.name, oldChapter.url, 4, "Source note"),
			),
			targetEntries = listOf(
				ChapterPersonalEntity(4L, newChapter.source.name, newChapter.url, null, "Destination note"),
			),
		).single()

		assertEquals(4, result.rating)
		assertEquals("Destination note", result.note)
	}

	@Test
	fun `annotation without bookmark-equivalent chapter mapping is not reassigned`() {
		val oldChapter = chapter(501L, "OLD", "/old/11")
		val newChapter = chapter(601L, "NEW", "/new/12")
		val result = mapChapterPersonalCompatibility(
			oldMangaId = 5L,
			newMangaId = 6L,
			oldChapters = listOf(oldChapter),
			newChapters = listOf(newChapter),
			chapterIds = emptyMap(),
			sourceEntries = listOf(
				ChapterPersonalEntity(5L, oldChapter.source.name, oldChapter.url, 3, "Do not guess"),
			),
			targetEntries = emptyList(),
		)

		assertEquals(emptyList<ChapterPersonalEntity>(), result)
	}

	private fun chapter(id: Long, sourceName: String, url: String) = MangaChapter(
		id = id,
		title = "Chapter 7",
		number = 7f,
		volume = 0,
		url = url,
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MissingMangaSource(sourceName),
	)
}
