package org.koitharu.kotatsu.download.ui.list

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadChapterInvalidationsTest {

	@get:Rule val folder = TemporaryFolder()

	@Test
	fun globalInvalidationRefreshesDownloadedFlagOnTheSameCollector() = runTest {
		val root = folder.newFolder("root")
		val file = File(root, "chapter.cbz").apply { writeText("fixture") }
		val sibling = folder.newFolder("root-other")
		val manga = manga()
		val changes = MutableSharedFlow<LocalManga?>()
		val downloaded = mutableListOf<Boolean>()
		backgroundScope.launch {
			observeDownloadChapterInvalidations(changes, manga.id, listOf(root)).collect {
				downloaded += file.isFile
			}
		}
		runCurrent()
		assertEquals(listOf(true), downloaded)
		check(file.delete())
		changes.emit(LocalManga(manga, File(sibling, file.name)))
		changes.emit(LocalManga(manga.copy(id = manga.id + 1), file))
		runCurrent()
		assertEquals(listOf(true), downloaded)
		changes.emit(null)
		runCurrent()
		assertEquals(listOf(true, false), downloaded)
		file.writeText("restored")
		changes.emit(LocalManga(manga, file))
		runCurrent()
		assertEquals(listOf(true, false, true), downloaded)
	}

	@Test
	fun burstDuringInitialLookupKeepsOneTrailingRefresh() = runTest {
		val changes = MutableSharedFlow<LocalManga?>()
		val release = CompletableDeferred<Unit>()
		var lookups = 0
		backgroundScope.launch {
			observeDownloadChapterInvalidations(changes, manga().id, emptyList()).collect {
				if (++lookups == 1) release.await()
			}
		}
		runCurrent()
		assertEquals(1, lookups)
		repeat(100) { changes.emit(null) }
		runCurrent()
		assertEquals(1, lookups)
		release.complete(Unit)
		runCurrent()
		assertEquals(2, lookups)
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
