package org.koitharu.kotatsu.download.ui.list

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

class DownloadChapterMetadataTest {

	@Test
	fun `cold manga and novel use matching DB snapshot without calling an offline source`() = runTest {
		for (source in listOf("MIHON_424242", "LN_OFFLINE")) {
			val stored = manga(source, listOf(1L, 2L, 3L))
			val seed = stored.copy(chapters = null)
			var reads = 0
			val chapters = resolveDownloadChapterMetadata(seed, setOf(1L, 3L), { id ->
				assertEquals(seed.id, id)
				reads++
				stored
			}, { error("Source must not be needed for offline expansion") })
			assertEquals(stored.chapters, chapters)
			assertEquals(listOf(1L, 3L), chapters.orEmpty().filter { it.id in setOf(1L, 3L) }.map { it.id })
			assertEquals(1, reads)
		}
	}

	@Test
	fun `whole manga request uses persisted snapshot without source extension`() = runTest {
		val stored = manga(chapters = listOf(1L, 2L))
		assertEquals(stored.chapters, resolveDownloadChapterMetadata(stored.copy(chapters = null), null,
			{ stored }, { error("Missing extension must not block persisted chapters") }))
	}

	@Test
	fun `usable in memory chapters avoid both DB and source lookups`() = runTest {
		val manga = manga(chapters = listOf(1L, 2L))
		assertEquals(manga.chapters, resolveDownloadChapterMetadata(manga, setOf(2L),
			{ error("DB not needed") }, { error("Source not needed") }))
	}

	@Test
	fun `missing or empty snapshot uses source fallback exactly once`() = runTest {
		val remote = manga(chapters = listOf(1L, 2L))
		for (stored in listOf(null, remote.copy(chapters = null), remote.copy(chapters = emptyList()))) {
			var sourceCalls = 0
			val chapters = resolveDownloadChapterMetadata(remote.copy(chapters = null), setOf(2L), { stored }, {
				sourceCalls++
				remote
			})
			assertEquals(remote.chapters, chapters)
			assertEquals(1, sourceCalls)
		}
	}

	@Test
	fun `snapshot of another manga or source is never accepted`() = runTest {
		val expected = manga(chapters = listOf(1L))
		val foreignSnapshots = listOf(expected.copy(id = 999L), manga("OTHER_SOURCE", listOf(1L)))
		for (foreign in foreignSnapshots) {
			var sourceCalls = 0
			val chapters = resolveDownloadChapterMetadata(expected.copy(chapters = null), setOf(1L), { foreign }, {
				sourceCalls++
				null
			})
			assertNull(chapters)
			assertEquals(1, sourceCalls)
		}
	}

	@Test
	fun `union of grouped task IDs missing from snapshot triggers source fallback`() = runTest {
		val remote = manga(chapters = listOf(1L, 2L, 3L))
		val stored = manga(chapters = listOf(1L))
		val groupedIds = setOf(1L) + setOf(3L)
		var sourceCalls = 0
		val chapters = resolveDownloadChapterMetadata(stored.copy(chapters = null), groupedIds, { stored }, {
			sourceCalls++
			remote
		})
		assertEquals(remote.chapters, chapters)
		assertEquals(1, sourceCalls)
	}

	@Test
	fun `offline fallback retains partial persisted metadata without inventing chapters`() = runTest {
		val stored = manga(chapters = listOf(1L))
		val chapters = resolveDownloadChapterMetadata(stored.copy(chapters = null), setOf(1L, 2L), { stored }, { null })
		assertEquals(listOf(1L), chapters.orEmpty().map { it.id })
	}

	@Test
	fun `cancellation during DB resolution propagates without a source request`() = runTest {
		var sourceCalled = false
		val failure = runCatching {
			resolveDownloadChapterMetadata(manga(), null, { throw CancellationException("cancelled") }, {
				sourceCalled = true
				null
			})
		}.exceptionOrNull()
		assertTrue(failure is CancellationException)
		assertEquals(false, sourceCalled)
	}

	private fun manga(source: String = "MIHON_424242", chapters: List<Long>? = null): Manga {
		val identity = MissingMangaSource(source)
		return Manga(
			id = 42L, title = "Offline metadata", altTitles = emptySet(), state = null, rating = 0f,
			contentRating = null, url = "/manga", publicUrl = "https://example.invalid/manga",
			coverUrl = null, largeCoverUrl = null, authors = emptySet(), description = null,
			source = identity, tags = emptySet(), chapters = chapters?.map { id ->
				MangaChapter(id = id, title = "Chapter $id", number = id.toFloat(), volume = 0,
					url = "/chapter/$id", scanlator = null, uploadDate = 0L, branch = null, source = identity)
			},
		)
	}
}
