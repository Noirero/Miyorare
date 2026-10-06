package org.koitharu.kotatsu.details.domain

import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelStore
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.longHashCode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Uses the real interactor, persisted index, filesystem and ViewModel event/remapping flows. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DetailsLiveLocalStateTest {

	@get:Rule var hiltRule = HiltAndroidRule(this)

	@Inject lateinit var interactor: DetailsInteractor
	@Inject lateinit var localIndex: LocalMangaIndex
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var bookmarksRepository: BookmarksRepository
	@Inject lateinit var historyRepository: HistoryRepository
	@Inject lateinit var scheduler: DownloadWorker.Scheduler
	@Inject lateinit var destinations: DownloadDestinationStore
	@Inject lateinit var deleteLocal: DeleteLocalMangaUseCase
	@Inject lateinit var mangaDataRepository: MangaDataRepository
	@Inject lateinit var repositories: MangaRepository.Factory

	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val events = MutableSharedFlow<LocalManga?>()
	private val viewModels = ViewModelStore()
	private lateinit var root: File
	private lateinit var privateRoot: File
	private var previousPrivateRoot: String? = null
	private var previousNormalRoot: String? = null
	private var previousPrivateLegacyRoots: Set<String>? = null
	private var previousNormalLegacyRoots: Set<String>? = null
	private val indexIds = HashSet<Long>()

	@Before
	fun setUp() {
		// HiltTestApplication does not provide the production WorkManager configuration.
		runCatching { WorkManager.getInstance(context) }.getOrElse {
			WorkManager.initialize(context, Configuration.Builder().build())
			WorkManager.getInstance(context)
		}
		hiltRule.inject()
		root = temporaryDirectory(context.cacheDir)
		privateRoot = temporaryDirectory(context.cacheDir)
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		previousPrivateRoot = prefs.getString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, null)
		previousNormalRoot = prefs.getString(AppSettings.KEY_LOCAL_STORAGE, null)
		previousPrivateLegacyRoots = prefs.getStringSet("private_download_legacy_roots", null)?.toSet()
		previousNormalLegacyRoots = prefs.getStringSet("normal_download_legacy_roots", null)?.toSet()
		prefs.edit(commit = true) {
			putString(AppSettings.KEY_LOCAL_STORAGE, root.path)
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, privateRoot.path)
			remove("private_download_legacy_roots")
			remove("normal_download_legacy_roots")
		}
	}

	@After
	fun tearDown() = runBlocking {
		InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
		for (id in indexIds) localIndex.delete(id)
		PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
			if (previousPrivateRoot == null) remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT)
			else putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, previousPrivateRoot)
			if (previousNormalRoot == null) remove(AppSettings.KEY_LOCAL_STORAGE)
			else putString(AppSettings.KEY_LOCAL_STORAGE, previousNormalRoot)
			if (previousPrivateLegacyRoots == null) remove("private_download_legacy_roots")
			else putStringSet("private_download_legacy_roots", previousPrivateLegacyRoots)
			if (previousNormalLegacyRoots == null) remove("normal_download_legacy_roots")
			else putStringSet("normal_download_legacy_roots", previousNormalLegacyRoots)
		}
		root.deleteRecursively()
		privateRoot.deleteRecursively()
		Unit
	}

	@Test
	fun indexedLocalIdDifferentFromRemoteUpdatesLocalSnapshotAndChapterMapping() = runBlocking {
		cbz(root, 1)
		val remote = remote()
		val event = LocalMangaParser(root).getManga(withDetails = false)
		assertNotEquals(remote.id, event.manga.id)
		register(remote, event)
		val initial = MangaDetails(remote)
		val vm = viewModel(initial)

		emit(event)
		val updated = awaitLocal(vm)
		val items = awaitChapters(vm) { it.downloadedIds() == setOf(1L) }

		assertNotSame(initial, updated)
		assertEquals(root, updated.local?.file)
		assertTrue(items.single { it.chapter.id == 1L }.isDownloaded)
		assertEquals(0, vm.reloads.get())
	}

	@Test
	fun unrelatedLocalEventWithSameTitleLeavesDetailsUnchanged() = runBlocking {
		cbz(root, 1)
		val remote = remote()
		register(remote, LocalMangaParser(root).getManga(withDetails = false))
		val unrelatedRoot = File(root, "unrelated").apply { mkdir() }
		cbz(unrelatedRoot, 1)
		val unrelated = LocalMangaParser(unrelatedRoot).getManga(withDetails = false).let {
			it.copy(manga = it.manga.copy(title = remote.title))
		}
		val initial = MangaDetails(remote)
		val vm = viewModel(initial)

		assertSame(initial, interactor.updateLocal(initial, unrelated))
		emit(unrelated)
		emit(null) // Serial collection barrier; no local container exists in this Details snapshot.

		assertSame(initial, vm.mangaDetails.value)
		assertEquals(0, vm.reloads.get())
	}

	@Test
	fun unrelatedEventWithoutPersistedIdentityLeavesDetailsUnchanged() = runBlocking {
		cbz(root, 1)
		val remote = remote()
		val unrelated = LocalMangaParser(root).getManga(withDetails = false)
		val initial = MangaDetails(remote)
		val vm = viewModel(initial)
		assertFalse(remote.id in localIndex)

		assertSame(initial, interactor.updateLocal(initial, unrelated))
		emit(unrelated)
		emit(null)

		assertSame(initial, vm.mangaDetails.value)
		assertEquals(0, vm.reloads.get())
	}

	@Test
	fun partialDeletionReplacesConcreteSnapshotAndRemapsDownloadedFlagsWithoutRefresh() = runBlocking {
		val victim = cbz(root, 1)
		val survivor = cbz(root, 2)
		val remote = remote()
		val before = concrete(remote, listOf(victim, survivor))
		val initial = MangaDetails(remote).copy(localManga = before)
		val vm = viewModel(initial)
		awaitChapters(vm) { it.downloadedIds() == setOf(1L, 2L) }
		assertTrue(victim.delete())
		val event = concrete(remote, listOf(survivor))

		emit(event)
		val updated = withTimeout(5_000L) {
			vm.mangaDetails.first { it?.local?.manga?.chapters?.size == 1 }
		}
		val items = awaitChapters(vm) { it.downloadedIds() == setOf(2L) }

		assertNotSame(before.manga.chapters, updated?.local?.manga?.chapters)
		assertFalse(items.single { it.chapter.id == 1L }.isDownloaded)
		assertTrue(items.single { it.chapter.id == 2L }.isDownloaded)
		assertEquals(0, vm.reloads.get())
	}

	@Test
	fun firstDownloadIsAcceptedThroughAliasDespiteStaleNonEmptyRemoteIndexRow() = runBlocking {
		cbz(root, 1)
		val remote = remote()
		val oldRoot = File(root, "older-copy").apply { mkdir() }
		cbz(oldRoot, 1)
		val old = LocalMangaParser(oldRoot).getManga(withDetails = false)
		localIndex.put(old.copy(manga = old.manga.copy(id = remote.id)))
		val event = LocalMangaParser(root).getManga(withDetails = false)
		register(remote, event)
		val vm = viewModel(MangaDetails(remote))

		emit(event)
		assertEquals(root, awaitLocal(vm).local?.file)
		awaitChapters(vm) { it.downloadedIds() == setOf(1L) }
		assertEquals(0, vm.reloads.get())
	}

	@Test
	fun persistedRemoteIndexRowWithoutAliasRetainsLegacyIdentityFallback() = runBlocking {
		cbz(root, 1)
		val remote = remote()
		val event = LocalMangaParser(root).getManga(withDetails = false)
		localIndex.put(event.copy(manga = event.manga.copy(id = remote.id)))
		indexIds += remote.id
		val vm = viewModel(MangaDetails(remote))

		emit(event)
		assertEquals(root, awaitLocal(vm).local?.file)
		awaitChapters(vm) { it.downloadedIds() == setOf(1L) }
		assertEquals(0, vm.reloads.get())
	}

	@Test
	fun sameRemoteIdFromPrivateRootCannotChangeNormalDetails() = runBlocking {
		cbz(root, 1)
		val remote = remote()
		val initial = MangaDetails(remote).copy(localManga = concrete(remote, listOf(File(root, "Chapter 1.cbz"))))
		val privateFile = cbz(privateRoot, 2)
		val otherSpace = concrete(remote, listOf(privateFile), container = privateRoot)
		val vm = viewModel(initial)

		emit(otherSpace)
		emit(null) // Root still exists, so this barrier must leave the current snapshot untouched.

		assertSame(initial, vm.mangaDetails.value)
		assertEquals(0, vm.reloads.get())
	}

	@Test
	fun sameRemoteIdFromNormalRootCannotChangePrivateDetails() = runBlocking {
		val privateFile = cbz(privateRoot, 1)
		val remote = remote()
		val initial = MangaDetails(remote).copy(localManga = concrete(remote, listOf(privateFile), privateRoot))
		val normalFile = cbz(root, 2)
		val vm = viewModel(initial, FavouriteSpace.PRIVATE)

		emit(concrete(remote, listOf(normalFile)))
		emit(null)

		assertSame(initial, vm.mangaDetails.value)
		assertEquals(0, vm.reloads.get())
	}

	@Test
	fun privateDestinationNestedUnderNormalRootStillCannotChangeNormalDetails() = runBlocking {
		val normalFile = cbz(root, 1)
		val nestedPrivate = File(root, "private-destination").apply { mkdir() }
		PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, nestedPrivate.path)
		}
		val remote = remote()
		val initial = MangaDetails(remote).copy(localManga = concrete(remote, listOf(normalFile)))
		val privateFile = cbz(nestedPrivate, 2)
		val vm = viewModel(initial)

		emit(concrete(remote, listOf(privateFile), nestedPrivate))
		emit(null)

		assertSame(initial, vm.mangaDetails.value)
	}

	@Test
	fun normalDestinationNestedUnderPrivateRootStillCannotChangePrivateDetails() = runBlocking {
		val privateFile = cbz(privateRoot, 1)
		val nestedNormal = File(privateRoot, "normal-destination").apply { mkdir() }
		PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
			putString(AppSettings.KEY_LOCAL_STORAGE, nestedNormal.path)
		}
		val remote = remote()
		val initial = MangaDetails(remote).copy(localManga = concrete(remote, listOf(privateFile), privateRoot))
		val normalFile = cbz(nestedNormal, 2)
		val vm = viewModel(initial, FavouriteSpace.PRIVATE)

		emit(concrete(remote, listOf(normalFile), nestedNormal))
		emit(null)

		assertSame(initial, vm.mangaDetails.value)
	}

	@Test
	fun nullEventWithExistingRootDoesNotReloadOrClearLocalMarkers() = runBlocking {
		val file = cbz(root, 1)
		val remote = remote()
		val initial = MangaDetails(remote).copy(localManga = concrete(remote, listOf(file)))
		val vm = viewModel(initial)

		emit(null)
		emit(null)

		assertSame(initial, vm.mangaDetails.value)
		assertEquals(0, vm.reloads.get())
	}

	@Test
	fun nullEventWithRemovedRootStillRequestsDetailsReload() = runBlocking {
		val file = cbz(root, 1)
		val remote = remote()
		val initial = MangaDetails(remote).copy(localManga = concrete(remote, listOf(file)))
		val vm = viewModel(initial)
		assertTrue(root.deleteRecursively())
		val otherSpace = concrete(remote, listOf(cbz(privateRoot, 2)), privateRoot)

		emit(null)
		emit(otherSpace) // Serial barrier; this concrete event is rejected by the space boundary.

		assertEquals(1, vm.reloads.get())
	}

	private suspend fun register(remote: Manga, event: LocalManga) {
		indexIds += remote.id
		localIndex.registerDownloadAlias(remote.id, event.manga.id, event.file)
	}

	private fun remote(): Manga {
		val source = MangaSource("MIHON_424242")
		return SampleData.mangaDetails.copy(
			id = "remote:${root.path}".longHashCode(), title = "Details live-state regression", source = source,
			chapters = List(2) { index ->
				SampleData.chapter.copy(
					id = index + 1L, title = "Chapter ${index + 1}", number = index + 1f, volume = 0,
					url = "https://example.invalid/chapter/${index + 1}", source = source, branch = null, scanlator = null,
				)
			},
		)
	}

	private fun concrete(remote: Manga, files: List<File>, container: File = root): LocalManga = LocalManga(
		remote.copy(
			url = container.toUri().toString(), publicUrl = container.toUri().toString(), source = LocalMangaSource,
			chapters = remote.chapters.orEmpty().mapNotNull { chapter ->
				files.singleOrNull { it.name == "${chapter.title}.cbz" }?.let { file ->
					chapter.copy(url = file.toUri().toString(), source = LocalMangaSource)
				}
			},
		), container,
	)

	private fun temporaryDirectory(parent: File): File = File.createTempFile("details-live-", ".dir", parent).apply {
		check(delete() && mkdir())
	}

	private fun cbz(parent: File, chapter: Int): File = File(parent, "Chapter $chapter.cbz").apply {
		ZipOutputStream(outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("1.jpg"))
			zip.write(byteArrayOf(1, 2, 3))
			zip.closeEntry()
		}
	}

	private suspend fun emit(event: LocalManga?) = withTimeout(5_000L) {
		events.subscriptionCount.first { it > 0 }
		events.emit(event)
	}

	private suspend fun awaitLocal(vm: TestViewModel): MangaDetails = withTimeout(5_000L) {
		requireNotNull(vm.mangaDetails.first { it?.local != null })
	}

	private suspend fun awaitChapters(vm: TestViewModel, predicate: (List<ChapterListItem>) -> Boolean) =
		withTimeout(5_000L) { vm.chapters.first { it.size == 2 && predicate(it) } }

	private fun List<ChapterListItem>.downloadedIds(): Set<Long> = filter { it.isDownloaded }.mapTo(HashSet()) { it.chapter.id }

	private fun viewModel(initial: MangaDetails, space: FavouriteSpace = FavouriteSpace.NORMAL): TestViewModel =
		TestViewModel(space).also {
			viewModels.put("details", it)
			it.mangaDetails.value = initial
		}

	private inner class TestViewModel(space: FavouriteSpace) : ChaptersPagesViewModel(
		settings, interactor, bookmarksRepository, historyRepository, scheduler, destinations, space,
		deleteLocal, events, mangaDataRepository, repositories,
	) {
		val reloads = AtomicInteger()
		override fun reload() { reloads.incrementAndGet() }
	}
}
