package org.koitharu.kotatsu.details.domain

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

class DetailsRoomChapterEarlyReconcileRegressionTest {

	@Test
	fun `non empty Room chapters fill null and empty source slots before load completes`() = runTest {
		for (sourceChapters in listOf(null, emptyList<MangaChapter>())) {
			val initial = details(sourceChapters)
			val state = MutableStateFlow<MangaDetails?>(initial)
			val load = Job()
			val room = listOf(chapter(1))
			val reconcile = launch { state.reconcileRoomChapters(room, { load }, { false }) }

			runCurrent()

			assertTrue(load.isActive)
			assertTrue(reconcile.isCompleted)
			assertEquals(room, state.value?.allChapters)
			assertEquals(initial.sourceManga.copy(chapters = room), state.value?.sourceManga)
			assertEquals(initial.description, state.value?.description)
			assertFalse(requireNotNull(state.value).isLoaded)
			load.complete()
		}
	}

	@Test
	fun `empty Room emission cannot clear chapters during or after a load`() = runTest {
		val initial = details(listOf(chapter(1)))
		val state = MutableStateFlow<MangaDetails?>(initial)
		val load = Job()
		val reconcile = launch { state.reconcileRoomChapters(emptyList(), { load }, { false }) }
		runCurrent()
		assertFalse(reconcile.isCompleted)
		assertSame(initial, state.value)

		load.complete()
		runCurrent()

		assertTrue(reconcile.isCompleted)
		assertSame(initial, state.value)
	}

	@Test
	fun `Local authority is retained during and after a load`() = runTest {
		// Even an empty Local title must never receive chapters from its historical Room identity.
		val initial = MangaDetails(manga().copy(source = LocalMangaSource))
		val state = MutableStateFlow<MangaDetails?>(initial)
		val load = Job()
		val reconcile = launch {
			state.reconcileRoomChapters(listOf(chapter(1)), { load }, { error("Local must not query scanlators") })
		}
		runCurrent()
		assertSame(initial, state.value)
		load.complete()
		runCurrent()
		assertTrue(reconcile.isCompleted)
		assertSame(initial, state.value)
	}

	@Test
	fun `chapter only promotion preserves metadata overrides and download overlays`() = runTest {
		val downloaded = chapter(1).copy(source = LocalMangaSource, url = "file:///download/1.cbz")
		val local = LocalManga(manga().copy(chapters = listOf(downloaded)), File("/download"))
		val initial = details().copy(localManga = local).withOverride(
			MangaOverride("override-cover", "Owner title", null, author = "Owner", description = "Owner note"),
		)
		val state = MutableStateFlow<MangaDetails?>(initial)
		val load = Job()
		val room = listOf(chapter(1), chapter(2))

		state.reconcileRoomChapters(room, { load }, { false })

		val updated = requireNotNull(state.value)
		assertTrue(load.isActive)
		assertSame(local, updated.local)
		assertSame(downloaded, updated.allChapters.first())
		assertEquals(listOf(downloaded, chapter(2)), updated.allChapters)
		assertEquals(initial.copy(manga = initial.sourceManga.copy(chapters = room)), updated)
		assertEquals("Owner title", updated.toManga().title)
		assertEquals("Owner note", updated.displayDescription)
		load.complete()
	}

	@Test
	fun `state changed during preference lookup is reread instead of overwritten`() = runTest {
		val state = MutableStateFlow<MangaDetails?>(details())
		val load = Job()
		val gate = CompletableDeferred<Unit>()
		var lookups = 0
		val reconcile = launch {
			state.reconcileRoomChapters(listOf(chapter(1)), { load }) {
				if (++lookups == 1) gate.await()
				false
			}
		}
		runCurrent()
		val newer = details().copy(description = "New description").withOverride(
			MangaOverride(null, "Edited while suspended", null),
		)
		state.value = newer
		gate.complete(Unit)
		runCurrent()

		assertTrue(reconcile.isCompleted)
		assertEquals(2, lookups)
		assertEquals(newer.copy(manga = newer.sourceManga.copy(chapters = listOf(chapter(1)))), state.value)
		load.complete()
	}

	@Test
	fun `source chapters arriving during lookup restore the active load barrier`() = runTest {
		val state = MutableStateFlow<MangaDetails?>(details())
		val load = Job()
		val gate = CompletableDeferred<Unit>()
		val room = listOf(chapter(2))
		val reconcile = launch { state.reconcileRoomChapters(room, { load }, { gate.await(); false }) }
		runCurrent()
		val finalSource = details(listOf(chapter(1))).copy(description = "Final source", isLoaded = true)
		state.value = finalSource
		gate.complete(Unit)
		runCurrent()

		assertFalse(reconcile.isCompleted)
		assertSame(finalSource, state.value)
		load.complete()
		runCurrent()
		assertTrue(reconcile.isCompleted)
		assertEquals(finalSource.copy(manga = finalSource.sourceManga.copy(chapters = room)), state.value)
	}

	@Test
	fun `Local state arriving during lookup cannot be overwritten`() = runTest {
		val state = MutableStateFlow<MangaDetails?>(details())
		val load = Job()
		val gate = CompletableDeferred<Unit>()
		val reconcile = launch { state.reconcileRoomChapters(listOf(chapter(1)), { load }, { gate.await(); false }) }
		runCurrent()
		val local = MangaDetails(manga().copy(source = LocalMangaSource))
		state.value = local
		gate.complete(Unit)
		runCurrent()
		assertSame(local, state.value)
		load.complete()
		runCurrent()
		assertTrue(reconcile.isCompleted)
		assertSame(local, state.value)
	}

	@Test
	fun `replacement load during lookup is observed before publication`() = runTest {
		val initial = details()
		val state = MutableStateFlow<MangaDetails?>(initial)
		val oldLoad = Job().apply { complete() }
		var load: Job = oldLoad
		val replacement = Job()
		var lookups = 0
		val reconcile = launch {
			state.reconcileRoomChapters(emptyList(), { load }) {
				if (++lookups == 1) load = replacement
				false
			}
		}
		runCurrent()
		assertFalse(reconcile.isCompleted)
		assertSame(initial, state.value)
		replacement.complete()
		runCurrent()
		assertTrue(reconcile.isCompleted)
		assertEquals(2, lookups)
	}

	@Test
	fun `waiter follows a replacement load and reconciles against its final state`() = runTest {
		val state = MutableStateFlow<MangaDetails?>(details(listOf(chapter(1))))
		val oldLoad = Job()
		var load: Job = oldLoad
		val room = listOf(chapter(2))
		val reconcile = launch { state.reconcileRoomChapters(room, { load }, { false }) }
		runCurrent()
		val replacement = Job()
		load = replacement
		oldLoad.complete()
		runCurrent()
		assertFalse(reconcile.isCompleted)
		val finalSource = details(room).copy(description = "Replacement source", isLoaded = true)
		state.value = finalSource
		replacement.complete()
		runCurrent()
		assertTrue(reconcile.isCompleted)
		assertSame(finalSource, state.value)
	}

	@Test
	fun `final source emission retains authority after early chapter promotion`() = runTest {
		val state = MutableStateFlow<MangaDetails?>(details())
		val load = Job()
		state.reconcileRoomChapters(listOf(chapter(1)), { load }, { false })
		assertTrue(load.isActive)
		assertEquals(listOf(chapter(1)), state.value?.allChapters)

		val finalSource = details(listOf(chapter(2))).copy(description = "Final source", isLoaded = true)
		state.value = finalSource
		load.complete()
		runCurrent()
		assertSame(finalSource, state.value)
		// Subsequent Room reconciliation keeps final metadata and follows the normal post-load path.
		state.reconcileRoomChapters(listOf(chapter(2), chapter(3)), { load }, { false })
		assertEquals(finalSource.copy(manga = finalSource.sourceManga.copy(chapters = listOf(chapter(2), chapter(3)))), state.value)
	}

	@Test
	fun `merged scanlator branches are applied to promoted chapters and local overlays`() = runTest {
		val room = listOf(chapter(2).copy(branch = "B"), chapter(1).copy(branch = "A"))
		val downloaded = chapter(1).copy(branch = "A", source = LocalMangaSource)
		val local = LocalManga(manga().copy(chapters = listOf(downloaded)), File("/download"))
		val state = MutableStateFlow<MangaDetails?>(details().copy(localManga = local))
		val load = Job()
		state.reconcileRoomChapters(room, { load }, { true })
		assertTrue(load.isActive)
		assertEquals(setOf<String?>(null), state.value?.chapters?.keys)
		assertEquals(listOf(1L, 2L), state.value?.allChapters?.map { it.id })
		assertEquals(LocalMangaSource, state.value?.allChapters?.first()?.source)
		load.complete()
	}

	@Test
	fun `newest Room emission cancels a suspended reconciliation before it can publish`() = runTest {
		val state = MutableStateFlow<MangaDetails?>(details())
		val load = Job()
		val room = MutableSharedFlow<List<MangaChapter>>()
		val gate = CompletableDeferred<Unit>()
		val presented = mutableListOf<List<MangaChapter>?>()
		var lookups = 0
		val observations = state.onEach { presented += it?.sourceManga?.chapters }.launchIn(backgroundScope)
		val observer = room.mapLatest { chapters ->
			state.reconcileRoomChapters(chapters, { load }) {
				// Exercise a lookup that finishes despite cancellation, so publication must check it.
				if (++lookups == 1) withContext(NonCancellable) { gate.await() }
				false
			}
		}.launchIn(backgroundScope)
		runCurrent()
		room.emit(listOf(chapter(1)))
		runCurrent()
		room.emit(listOf(chapter(2)))
		runCurrent()
		gate.complete(Unit)
		runCurrent()
		assertEquals(listOf(chapter(2)), state.value?.allChapters)
		assertFalse(presented.contains(listOf(chapter(1))))
		assertTrue(load.isActive)
		observer.cancel()
		observations.cancel()
		load.complete()
	}

	@Test
	fun `Details observer uses the tested chapter reconciliation and keeps branch selection`() {
		val source = sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/details/ui/DetailsViewModel.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/details/ui/DetailsViewModel.kt"),
		).first(File::isFile).readText().replace(Regex("""\s+"""), "")
		assertTrue(source.contains(".mapLatest{chapters->syncCachedChaptersWhenLoadIdle(chapters)}"))
		assertTrue(source.contains("mangaDetails.reconcileRoomChapters("))
		assertTrue(source.contains("loadingJob={loadingJob}"))
		assertTrue(source.contains("isScanlatorsMerged={mangaDataRepository.isScanlatorsMerged(mangaId)}"))
		assertTrue(source.contains("selectedBranch.value!inavailableBranches"))
	}

	private fun details(chapters: List<MangaChapter>? = null) =
		MangaDetails(manga().copy(chapters = chapters)).copy(description = "Current description")

	private fun manga() = Manga(
		id = 42L, title = "Current title", altTitles = emptySet(), state = null, rating = 0f,
		contentRating = null, url = "/manga-42", publicUrl = "https://example.invalid/manga-42",
		coverUrl = "current-cover", largeCoverUrl = null, authors = emptySet(), description = "Source description",
		source = MangaSource("MIHON_1"), tags = emptySet(), chapters = null,
	)

	private fun chapter(id: Long) = MangaChapter(
		id = id, title = "Chapter $id", number = id.toFloat(), volume = 0,
		url = "/chapter-$id", scanlator = null, uploadDate = 0L, branch = null, source = MangaSource("MIHON_1"),
	)
}
