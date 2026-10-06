package org.koitharu.kotatsu.local.data

import android.net.Uri
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LocalChapterDeleteStateTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject lateinit var repository: LocalMangaRepository
	@Inject lateinit var localIndex: LocalMangaIndex
	@Inject lateinit var database: MangaDatabase
	@Inject @LocalStorageChanges lateinit var changes: MutableSharedFlow<LocalManga?>

	private lateinit var root: File

	@Before
	fun setUp() {
		hiltRule.inject()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		root = File.createTempFile("chapter-delete-state-", ".dir", context.cacheDir)
		check(root.delete() && root.mkdir())
	}

	@After
	fun tearDown() = runBlocking {
		localIndex.delete(mangaId())
		database.getFavouriteDownloadIndexDao().deleteByPath(root.canonicalPath)
		root.deleteRecursively()
		Unit
	}

	@Test
	fun snapshotDropsEveryMappingToMissingArtifactsWithoutOpeningSurvivingCbz() = runBlocking {
		val victim = artifact("Chapter 1.cbz")
		// Not a readable zip: the candidate fast path must stat this file, never parse its contents.
		val survivor = artifact("Chapter 2.cbz")
		val chapters = listOf(
			chapter(1, victim), chapter(2, survivor),
			chapter(3, victim, fragment = true), chapter(4, File(root, "already-missing.cbz")),
		)

		val state = requireNotNull(deleteAndObserve(local(chapters), setOf(1L)))

		assertFalse(victim.exists())
		assertTrue(survivor.exists())
		assertEquals(listOf(2L), state.manga.chapters.orEmpty().map { it.id })
	}

	@Test
	fun novelDirectoryAlsoDropsAliasesToDeletedEpubWithoutParsingRemainingBooks() = runBlocking {
		val victim = artifact("Chapter 1.epub")
		val survivor = artifact("Chapter 2.epub")
		val chapters = listOf(chapter(1, victim), chapter(2, survivor), chapter(3, victim))

		val state = requireNotNull(deleteAndObserve(local(chapters), setOf(1L)))

		assertFalse(victim.exists())
		assertTrue(survivor.exists())
		assertEquals(listOf(2L), state.manga.chapters.orEmpty().map { it.id })
	}

	@Test
	fun sharedArchiveUsesUpdatedContentsInsteadOfKeepingEveryCandidateWhoseRootExists() = runBlocking {
		val archive = File(root, "book.cbz")
		val source = MangaSource("MIHON_424242")
		val chapters = listOf(chapter(1, archive), chapter(2, archive))
		val remote = local(chapters).copy(source = source, chapters = chapters.map { it.copy(source = source, branch = null) })
		val index = MangaIndex(null).apply {
			setMangaInfo(remote)
			remote.chapters.orEmpty().withIndex().forEach { addChapter(it, null) }
		}
		ZipOutputStream(archive.outputStream()).use { zip ->
			for ((name, bytes) in listOf(
				LocalMangaOutput.ENTRY_NAME_INDEX to index.toString().toByteArray(),
				"00000000_00010000.jpg" to byteArrayOf(1),
				"00000000_00020000.jpg" to byteArrayOf(2),
			)) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(bytes)
				zip.closeEntry()
			}
		}
		val subject = local(chapters + chapter(3, archive)).copy(
			url = archive.toUri().toString(), publicUrl = archive.toUri().toString(),
		)

		val state = requireNotNull(deleteAndObserve(subject, setOf(1L)))

		assertTrue(archive.exists())
		assertEquals(listOf(2L), state.manga.chapters.orEmpty().map { it.id })
	}

	@Test
	fun refreshIgnoresStaleSidecarEntriesForRemoteAndOfflineOnlyChapters() = runBlocking {
		val victim = artifact("Chapter 1.cbz")
		val survivor = artifact("Chapter 2.cbz")
		val chapters = listOf(chapter(1, victim), chapter(2, survivor), chapter(3, File(root, "missing.cbz")))
		val remote = writeIndex(chapters)
		val state = requireNotNull(deleteAndObserve(local(chapters), setOf(1L)))

		val refreshed = requireNotNull(repository.findSavedMangaAtPath(
			remote.copy(chapters = remote.chapters.orEmpty().take(2)), root, rememberIdentity = false,
		))

		assertEquals(listOf(2L), state.manga.chapters.orEmpty().map { it.id })
		assertEquals(listOf(2L), refreshed.manga.chapters.orEmpty().map { it.id })
	}

	@Test
	fun completeLegacySplitChapterSurvivesDeletionOfAnotherChapter() = runBlocking {
		val victim = artifact("victim.cbz")
		val parts = listOf(artifact("Chapter 1.cbz"), artifact("Chapter 2.cbz"))
		val split = composite(2, parts)

		val state = requireNotNull(deleteAndObserve(local(listOf(chapter(1, victim), split)), setOf(1L)))

		assertEquals(listOf(split), state.manga.chapters)
		assertTrue(parts.all { it.exists() })
	}

	@Test
	fun deletingLegacySplitChapterRemovesAllPartsAndTheirStaleAliases() = runBlocking {
		val parts = listOf(artifact("Chapter 1.cbz"), artifact("Chapter 2.cbz"))
		val survivor = artifact("other.cbz")
		val chapters = listOf(composite(1, parts), chapter(2, survivor), chapter(3, parts.first()))

		val state = requireNotNull(deleteAndObserve(local(chapters), setOf(1L)))

		assertTrue(parts.none { it.exists() })
		assertEquals(listOf(2L), state.manga.chapters.orEmpty().map { it.id })
	}

	@Test
	fun incompleteSplitChapterFallsBackToPhysicalSurvivingParts() = runBlocking {
		val victim = artifact("victim.cbz")
		val survivingPart = cbz("Chapter 1.cbz")
		val missingPart = File(root, "Chapter 2.cbz")
		val chapters = listOf(chapter(1, victim), composite(2, listOf(survivingPart, missingPart)))

		val state = requireNotNull(deleteAndObserve(local(chapters), setOf(1L)))
		val remaining = requireNotNull(state.manga.chapters).single()

		assertNull(LegacySplitChapterCompat.componentUrls(remaining.url))
		assertEquals(survivingPart.name, remaining.url.toUri().fragment)
		assertTrue(survivingPart.exists())
	}

	@Test
	fun finalChapterClearsContainerIndexAndOwnershipOnlyAfterDeletion() = runBlocking {
		val victim = artifact("Chapter 1.cbz")
		val manga = local(listOf(chapter(1, victim, fragment = true)))
		seedState(manga)

		assertNull(deleteAndObserve(manga, setOf(1L)))

		assertFalse(root.exists())
		assertStateCleared(manga)
	}

	@Test
	fun alreadyRemovedFinalContainerStillClearsIndexAndOwnership() = runBlocking {
		val manga = local(listOf(composite(1, listOf(
			artifact("Chapter 1.cbz"), artifact("Chapter 2.cbz"),
		))))
		seedState(manga)
		assertTrue(root.deleteRecursively())

		// Reproduce a completed lower-layer deletion: both components and the root are already gone.
		assertNull(deleteAndObserve(manga, setOf(1L)))

		assertStateCleared(manga)
	}

	@Test
	fun emptySidecarSnapshotCannotDeleteUnrepresentedPhysicalChapterOrClearState() = runBlocking {
		val victim = artifact("Chapter 1.cbz")
		val chapters = listOf(chapter(1, victim))
		writeIndex(chapters)
		val orphan = cbz("offline-only.cbz")
		val manga = local(chapters)
		seedState(manga)

		val failure = runCatching { repository.deleteChapters(manga, setOf(1L)) }.exceptionOrNull()

		assertTrue(failure is IllegalStateException)
		assertFalse(victim.exists())
		assertTrue(orphan.exists())
		assertEquals(root.path, database.getLocalMangaIndexDao().findPath(manga.id))
		assertTrue(database.getFavouriteDownloadIndexDao().findEntries(listOf(manga.id)).isNotEmpty())
	}

	private suspend fun deleteAndObserve(manga: Manga, ids: Set<Long>): LocalManga? = coroutineScope {
		val events = Channel<LocalManga?>(Channel.UNLIMITED)
		val collector = launch(start = CoroutineStart.UNDISPATCHED) {
			changes.collect { events.send(it) }
		}
		try {
			repository.deleteChapters(manga, ids)
			withTimeout(5_000L) { events.receive() }
		} finally {
			collector.cancelAndJoin()
			events.close()
		}
	}

	private suspend fun seedState(manga: Manga) {
		localIndex.put(LocalManga(manga, root))
		database.getFavouriteDownloadIndexDao().upsert(listOf(FavouriteDownloadIndexEntity(
			mangaId = manga.id, space = FavouriteSpace.NORMAL.dbValue, path = root.canonicalPath,
		)))
	}

	private suspend fun assertStateCleared(manga: Manga) {
		assertNull(database.getLocalMangaIndexDao().findPath(manga.id))
		assertTrue(database.getFavouriteDownloadIndexDao().findEntries(listOf(manga.id)).isEmpty())
	}

	private fun artifact(name: String): File = File(root, name).apply { writeText("artifact") }

	private fun cbz(name: String): File = File(root, name).apply {
		ZipOutputStream(outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("1.jpg"))
			zip.write(byteArrayOf(1, 2, 3))
			zip.closeEntry()
		}
	}

	private fun chapter(id: Long, file: File, fragment: Boolean = false): MangaChapter = SampleData.chapter.copy(
		id = id,
		url = if (fragment) root.toUri().buildUpon().fragment(file.name).build().toString() else file.toUri().toString(),
		source = LocalMangaSource,
	)

	private fun composite(id: Long, files: List<File>): MangaChapter = chapter(id, files.first()).copy(
		url = Uri.Builder().scheme("miyorare-local-composite").authority("chapter")
			.apply { files.forEach { appendQueryParameter("part", it.toUri().toString()) } }
			.build().toString(),
	)

	private fun mangaId(): Long = root.path.hashCode().toLong()

	private fun local(chapters: List<MangaChapter>): Manga = SampleData.manga.copy(
		id = mangaId(), url = root.toUri().toString(), publicUrl = root.toUri().toString(),
		source = LocalMangaSource, chapters = chapters,
	)

	private fun writeIndex(chapters: List<MangaChapter>): Manga {
		val source = MangaSource("MIHON_424242")
		val remote = local(chapters).copy(source = source, chapters = chapters.map { it.copy(source = source) })
		val index = MangaIndex(null).apply {
			setMangaInfo(remote)
			remote.chapters.orEmpty().withIndex().forEach { addChapter(it, chapters[it.index].url.toUri().lastPathSegment) }
		}
		File(root, LocalMangaOutput.ENTRY_NAME_INDEX).writeText(index.toString())
		return remote
	}
}
