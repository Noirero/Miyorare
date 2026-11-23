package org.koitharu.kotatsu.download.domain.maintenance

import android.content.SharedPreferences
import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.ui.list.DownloadSizeResolver
import org.koitharu.kotatsu.download.ui.list.observeDownloadChapterInvalidations
import org.koitharu.kotatsu.download.ui.list.DownloadItemModel
import org.koitharu.kotatsu.download.ui.list.DownloadRemovalFeedback
import org.koitharu.kotatsu.download.ui.list.DownloadsViewModel
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.download.ui.worker.EXHENTAI_COMMITTED_PATH
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteDownloadOwnershipIndex
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.local.data.LegacyChapterDownloadCompat
import org.koitharu.kotatsu.local.data.LocalDownloadScanner
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.findSavedMangaInRoot
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.domain.DownloadedMangaResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DownloadMaintenanceRegressionTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var schedulerProvider: Provider<DownloadWorker.Scheduler>
	@Inject lateinit var mangaRepositories: MangaRepository.Factory
	@Inject lateinit var sizeResolver: DownloadSizeResolver
	@Inject lateinit var maintenance: DownloadMaintenanceUseCase
	@Inject lateinit var db: MangaDatabase
	@Inject lateinit var data: MangaDataRepository
	@Inject lateinit var index: LocalMangaIndex
	@Inject lateinit var local: LocalMangaRepository
	@Inject lateinit var resolver: DownloadedMangaResolver
	@Inject lateinit var deleteLocal: DeleteLocalMangaUseCase
	@Inject lateinit var favourites: FavouritesRepository
	@Inject lateinit var ownershipIndex: FavouriteDownloadOwnershipIndex
	@Inject @org.koitharu.kotatsu.local.data.LocalStorageChanges lateinit var events: kotlinx.coroutines.flow.MutableSharedFlow<LocalManga?>
	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var root: File
	private lateinit var prefs: SharedPreferences
	private lateinit var savedPrefs: Map<String, *>
	private val ids = HashSet<Long>()

	@Before fun setUp() = runBlocking {
		hilt.inject()
		db.clearAllTables()
		root = File(context.cacheDir, "download-maintenance-${System.nanoTime()}").canonicalFile.apply { check(mkdirs()) }
		prefs = PreferenceManager.getDefaultSharedPreferences(context)
		savedPrefs = prefs.all.toMap()
		prefs.edit(commit = true) {
			putString(AppSettings.KEY_LOCAL_STORAGE, root.path)
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, File(root, "private").path)
			remove("normal_download_legacy_roots")
			remove("private_download_legacy_roots")
		}
		File(root, "private").mkdirs()
		Unit
	}

	@After fun tearDown() = runBlocking {
		if (!::savedPrefs.isInitialized) {
			if (::root.isInitialized) root.deleteRecursively()
			return@runBlocking
		}
		for (id in ids) index.delete(id)
		prefs.edit(commit = true) {
			clear()
			for ((key, value) in savedPrefs) when (value) {
				is String -> putString(key, value)
				is Int -> putInt(key, value)
				is Long -> putLong(key, value)
				is Boolean -> putBoolean(key, value)
				is Float -> putFloat(key, value)
				is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
			}
		}
		root.deleteRecursively()
		Unit
	}

	@Test fun bulkListOnlyKeepsFilesOwnershipAndActiveWork() = runBlocking {
		verifyBulkRemoval(deleteFiles = false)
	}

	@Test fun bulkWithFilesRemovesOnlyTheSelectedReceiptOnASharedRoot() = runBlocking {
		verifyBulkRemoval(deleteFiles = true)
	}

	@Test fun bulkWithFilesKeepsAmbiguousDuplicateExhentaiJobs() = runBlocking {
		verifyBulkRemoval(deleteFiles = true, withDuplicate = true)
	}

	@Test fun bulkWithFilesKeepsFirstGroupCommittedWhenSecondCannotBeDeleted() = runBlocking {
		if (!WorkManager.isInitialized()) {
			WorkManager.initialize(context, Configuration.Builder().build())
		}
		val scheduler = schedulerProvider.get()
		val wm = WorkManager.getInstance(context)
		val owner = db.getFavouriteDownloadIndexDao()
		val vm = DownloadsViewModel(SavedStateHandle(), scheduler, data, mangaRepositories,
			events, local, deleteLocal, favourites, sizeResolver)
		val fixtures = (812401L..812403L).associate { gid ->
			val manga = galleryFixture(gid)
			val destination = folder("removal-$gid")
			val request = newRemovalWork(manga, FavouriteSpace.NORMAL, destination)
			gid to Triple(manga, destination, request)
		}
		val workIds = fixtures.values.mapTo(HashSet()) { it.third.id }
		try {
			val files = fixtures.mapValues { (_, fixture) ->
				val (manga, destination, request) = fixture
				data.storeManga(manga, replaceExisting = true, detailsFetched = true)
				val file = writeGalleryFixture(manga, false, byteArrayOf(41, 42),
					receipt = ExhentaiChapterOwnership.Receipt(FavouriteSpace.NORMAL, request.id),
					destination = destination)
				owner.upsert(FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, file.canonicalPath))
				val work = OneTimeWorkRequest.Builder(RemovalHistoryFixtureWorker::class.java)
					.setId(request.id)
					.addTag("download")
					.setInputData(Data.Builder().putAll(request.input)
						.putString(EXHENTAI_COMMITTED_PATH, file.canonicalPath).build())
					.build()
				wm.enqueue(work).await()
				file
			}
			val before = files.mapValues { (_, file) -> file.readBytes() }
			withTimeout(30_000) {
				val rows = vm.items.first { items ->
					items.filterIsInstance<DownloadItemModel>().count {
						it.selectionId in fixtures.keys && it.workState == WorkInfo.State.SUCCEEDED
					} == 3
				}.filterIsInstance<DownloadItemModel>().filter { it.selectionId in fixtures.keys }
				// All three are terminal and in one date bucket. Select the second in
				// the same display order that supplies the batch, not enqueue order.
				val (first, second, third) = rows.map { it.selectionId }
				val blockedDir = checkNotNull(files.getValue(second).parentFile)
				assertTrue(blockedDir.setWritable(false, false))
				try {
					assertFalse("Fixture must deny unlink in the second folder", blockedDir.canWrite())
					val feedback = awaitRemovalFeedback(vm, fixtures.keys, deleteFiles = true)
					assertEquals(DownloadRemovalFeedback(1, 0, 0, true, failed = 1, notAttempted = 1), feedback)
				} finally {
					check(blockedDir.setWritable(true, true))
				}
				val history = scheduler.observeWorks().first()
					.filter { it.id in workIds }.associateBy { it.id }
				assertFalse(files.getValue(first).exists())
				assertNull(owner.findEntry(FavouriteSpace.NORMAL.dbValue, first))
				assertFalse(history.containsKey(fixtures.getValue(first).third.id))
				for (kept in listOf(second, third)) {
					val file = files.getValue(kept)
					assertArrayEquals(before.getValue(kept), file.readBytes())
					assertEquals(file.canonicalPath, owner.findEntry(FavouriteSpace.NORMAL.dbValue, kept)?.path)
					assertEquals(WorkInfo.State.SUCCEEDED, history[fixtures.getValue(kept).third.id]?.state)
				}
			}
		} finally {
			vm.viewModelScope.cancel()
			scheduler.delete(workIds)
		}
	}

	private suspend fun awaitRemovalFeedback(
		vm: DownloadsViewModel,
		selectedIds: Set<Long>,
		deleteFiles: Boolean,
	): DownloadRemovalFeedback = kotlinx.coroutines.coroutineScope {
		val observed = CompletableDeferred<DownloadRemovalFeedback>()
		val collector = launch(start = CoroutineStart.UNDISPATCHED) {
			checkNotNull(vm.onRemovalDone.first { it != null }).consume { observed.complete(it) }
		}
		try {
			vm.remove(selectedIds, deleteFiles = deleteFiles)
			observed.await()
		} finally {
			collector.cancel()
		}
	}

	private suspend fun verifyBulkRemoval(deleteFiles: Boolean, withDuplicate: Boolean = false) {
		// Only the bulk-removal fixture needs real WorkManager under HiltTestApplication.
		if (!WorkManager.isInitialized()) {
			WorkManager.initialize(context, Configuration.Builder().build())
		}
		val scheduler = schedulerProvider.get()
		prefs.edit(commit = true) { putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path) }
		val manga = galleryFixture(812391)
		val activeManga = galleryFixture(812392)
		data.storeManga(manga, replaceExisting = true, detailsFetched = true)
		data.storeManga(activeManga, replaceExisting = true, detailsFetched = true)
		val normal = newRemovalWork(manga, FavouriteSpace.NORMAL)
		val privateCopy = newRemovalWork(manga, FavouriteSpace.PRIVATE)
		val active = newRemovalWork(activeManga, FavouriteSpace.NORMAL)
		val duplicateManga = galleryFixture(812393)
		val duplicate = if (withDuplicate) List(2) {
			newRemovalWork(duplicateManga, FavouriteSpace.NORMAL)
		} else emptyList()
		if (withDuplicate) data.storeManga(duplicateManga, replaceExisting = true, detailsFetched = true)
		val duplicateIds = duplicate.mapTo(HashSet()) { it.id }
		val ids = setOf(normal.id, privateCopy.id, active.id) + duplicateIds
		val owner = db.getFavouriteDownloadIndexDao()
		val vm = DownloadsViewModel(SavedStateHandle(), scheduler, data, mangaRepositories,
			events, local, deleteLocal, favourites, sizeResolver)
		try {
			val selected = writeGalleryFixture(manga, false, byteArrayOf(11, 12),
				receipt = ExhentaiChapterOwnership.Receipt(FavouriteSpace.NORMAL, normal.id))
			val retained = writeGalleryFixture(manga, true, byteArrayOf(21, 22),
				receipt = ExhentaiChapterOwnership.Receipt(FavouriteSpace.PRIVATE, privateCopy.id))
			assertNotEquals(selected.canonicalPath, retained.canonicalPath)
			val selectedBefore = selected.readBytes()
			val retainedBefore = retained.readBytes()
			owner.upsert(FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, selected.canonicalPath))
			owner.upsert(FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.PRIVATE.dbValue, retained.canonicalPath))
			val duplicateFile = duplicate.firstOrNull()?.let { request ->
				writeGalleryFixture(duplicateManga, false, byteArrayOf(31, 32),
					receipt = ExhentaiChapterOwnership.Receipt(FavouriteSpace.NORMAL, request.id))
					.also {
						owner.upsert(FavouriteDownloadIndexEntity(duplicateManga.id, FavouriteSpace.NORMAL.dbValue, it.canonicalPath))
					}
			}
			val duplicateBefore = duplicateFile?.readBytes()
			val wm = WorkManager.getInstance(context)
			for ((request, file) in listOf(normal to selected, privateCopy to retained, active to null) +
				duplicate.map { it to duplicateFile }) {
				// The synthetic worker returns the persisted input plus the real committed path.
				// It performs no download and has no network or filesystem side effects.
				val work = OneTimeWorkRequest.Builder(RemovalHistoryFixtureWorker::class.java)
					.setId(request.id)
					.addTag("download") // Same queue tag used by Scheduler.observeWorks().
					.setInputData(Data.Builder().putAll(request.input)
						.putString(EXHENTAI_COMMITTED_PATH, file?.canonicalPath).build())
					.apply { if (file == null) setInitialDelay(1, TimeUnit.DAYS) }
					.build()
				wm.enqueue(work).await()
			}
			fun List<WorkInfo>.state(id: UUID) = firstOrNull { it.id == id }?.state
			withTimeout(30_000) {
				scheduler.observeWorks().first {
					it.state(normal.id) == WorkInfo.State.SUCCEEDED &&
						it.state(privateCopy.id) == WorkInfo.State.SUCCEEDED &&
						it.state(active.id) == WorkInfo.State.ENQUEUED &&
						duplicateIds.all { id -> it.state(id) == WorkInfo.State.SUCCEEDED }
				}
				vm.items.first { items ->
					val rows = items.filterIsInstance<DownloadItemModel>()
					rows.any { it.workIds == setOf(normal.id) && it.workState == WorkInfo.State.SUCCEEDED } &&
						rows.any { it.workIds == setOf(active.id) && !it.workState.isFinished } &&
						(!withDuplicate || rows.any { it.workIds == duplicateIds && it.workState.isFinished })
				}
				val selectedIds = setOf(manga.id, activeManga.id) +
					(if (withDuplicate) setOf(duplicateManga.id) else emptySet())
				val feedback = awaitRemovalFeedback(vm, selectedIds, deleteFiles)
				assertEquals(1, feedback.removed)
				assertEquals(1, feedback.active)
				assertEquals(if (withDuplicate) 1 else 0, feedback.ambiguous)
				val after = scheduler.observeWorks().first { it.state(normal.id) == null }
				assertEquals(setOf(privateCopy.id, active.id) + duplicateIds, after.map { it.id }.toSet().intersect(ids))
				assertEquals(WorkInfo.State.SUCCEEDED, after.state(privateCopy.id))
				assertEquals(WorkInfo.State.ENQUEUED, after.state(active.id))
			}
			if (deleteFiles) {
				assertFalse(selected.exists())
				assertNull(owner.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
			} else {
				assertArrayEquals(selectedBefore, selected.readBytes())
				assertEquals(selected.canonicalPath, owner.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id)?.path)
			}
			if (duplicateFile != null) {
				assertArrayEquals(checkNotNull(duplicateBefore), duplicateFile.readBytes())
				assertEquals(duplicateFile.canonicalPath, owner.findEntry(FavouriteSpace.NORMAL.dbValue, duplicateManga.id)?.path)
			}
			assertArrayEquals(retainedBefore, retained.readBytes())
			assertEquals(retained.canonicalPath, owner.findEntry(FavouriteSpace.PRIVATE.dbValue, manga.id)?.path)
			val opened = local.findExhentaiAtPath(manga, retained, withDetails = true,
				space = FavouriteSpace.PRIVATE, request = privateCopy.id)
			assertEquals(retained.canonicalFile, opened?.local?.file?.canonicalFile)
			assertTrue(opened?.local?.manga?.chapters.orEmpty().any { it.id == manga.chapters.orEmpty().single().id })
		} finally {
			vm.viewModelScope.cancel()
			scheduler.delete(ids)
		}
	}

	private fun newRemovalWork(manga: Manga, space: FavouriteSpace, destination: File = root): RemovalWork =
		RemovalWork(UUID.randomUUID(), DownloadTask(
			mangaId = manga.id, isPaused = false, isSilent = true,
			chaptersIds = manga.chapters.orEmpty().map { it.id }.toLongArray(),
			destination = destination, format = DownloadFormat.MULTIPLE_CBZ,
			allowMeteredNetwork = true, favouriteSpace = space,
		).toData())

	private data class RemovalWork(val id: UUID, val input: Data)

	@Test fun globalDeletionRefreshesChapterStatusOnTheSameCollector() = runBlocking {
		val manga = galleryFixture(812381)
		val file = writeGalleryFixture(manga, false, byteArrayOf(81, 82))
		val chapterId = manga.chapters.orEmpty().single().id
		val initial = CompletableDeferred<Boolean>()
		val observed = async {
			val flags = ArrayList<Boolean>()
			observeDownloadChapterInvalidations(events, manga.id, listOf(root)).take(2).collect {
				val downloaded = local.findSavedMangaInRoot(manga, root)?.manga?.chapters.orEmpty()
					.any { it.id == chapterId }
				flags += downloaded
				initial.complete(downloaded)
			}
			flags
		}
		try {
			kotlinx.coroutines.withTimeout(10_000) {
				assertTrue(initial.await())
				assertTrue(local.deleteExhentaiDestinationCopy(LocalManga(manga, file)))
				assertEquals(listOf(true, false), observed.await())
			}
		} finally {
			observed.cancel()
		}
	}

	@Test fun sizeResolverDoesNotCrossSpacesInASharedRoot() = runBlocking {
		prefs.edit(commit = true) { putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path) }
		val manga = remote()
		data.storeManga(manga, replaceExisting = true, detailsFetched = true)
		val normalFile = chapterFile(folder("downloads/normal-copy"), "Chapter.cbz")
		val privateFile = chapterFile(folder("downloads/private-copy"), "Chapter.cbz")
		val dao = db.getFavouriteDownloadIndexDao()
		val normal = FavouriteSpace.NORMAL
		val privateSpace = FavouriteSpace.PRIVATE
		val sizeResolver = DownloadSizeResolver(db, local)
		val request = UUID.randomUUID()
		val normalTask = sizeTask(manga, normal)
		val privateTask = sizeTask(manga, privateSpace)
		dao.upsert(FavouriteDownloadIndexEntity(manga.id, normal.dbValue, normalFile.canonicalPath))
		assertNull(sizeResolver.findSizeArtifact(request, manga, privateTask, null, privateSpace))
		dao.upsert(FavouriteDownloadIndexEntity(manga.id, privateSpace.dbValue, privateFile.canonicalPath))
		assertEquals(normalFile.canonicalFile, sizeResolver.findSizeArtifact(request, manga, normalTask, null, normal))
		assertEquals(privateFile.canonicalFile, sizeResolver.findSizeArtifact(request, manga, privateTask, null, privateSpace))
		assertNull(sizeResolver.findSizeArtifact(request, manga, normalTask, null, privateSpace))
		dao.upsert(FavouriteDownloadIndexEntity(manga.id, privateSpace.dbValue, normalFile.canonicalPath))
		assertNull(sizeResolver.findSizeArtifact(request, manga, normalTask, null, normal))
		assertNull(sizeResolver.findSizeArtifact(request, manga, privateTask, null, privateSpace))
	}

	@Test fun sizeResolverRejectsAnotherReceiptForTheSameGallery() = runBlocking {
		val galleryId = 812380L
		val manga = offlineExhentaiGallery(galleryId)
		data.storeManga(manga, replaceExisting = true, detailsFetched = true)
		val space = FavouriteSpace.NORMAL
		val requestA = UUID.randomUUID()
		val requestB = UUID.randomUUID()
		val fileA = writeGalleryFixture(manga, false, byteArrayOf(1), receipt = ExhentaiChapterOwnership.Receipt(space, requestA))
		val receiptB = ExhentaiChapterOwnership.Receipt(space, requestB)
		val fileB = writeGalleryFixture(manga, true, byteArrayOf(2, 3), receipt = receiptB)
		assertNotEquals(fileA.canonicalFile, fileB.canonicalFile)
		assertEquals(receiptB, (ExhentaiChapterOwnership.inspect(fileB) as? ExhentaiChapterOwnership.Evidence.Owned)?.receipt)
		// The space index can point to the newer copy; the older job still owns its exact receipt.
		db.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(manga.id, space.dbValue, fileB.canonicalPath),
		)
		val sizeResolver = DownloadSizeResolver(db, local)
		val task = sizeTask(manga, space, separateCopy = true)
		assertEquals(fileA.canonicalFile, sizeResolver.findSizeArtifact(requestA, manga, task, fileA.path, space))
		assertEquals(fileB.canonicalFile, sizeResolver.findSizeArtifact(requestB, manga, task, fileB.path, space))
		assertNull(sizeResolver.findSizeArtifact(requestA, manga, task, fileB.path, space))
		assertNull(sizeResolver.findSizeArtifact(requestA, manga, task, null, space))
		assertTrue(fileA.delete())
		assertNull(sizeResolver.findSizeArtifact(requestA, manga, task, fileA.path, space))
	}

	private fun sizeTask(manga: Manga, space: FavouriteSpace, separateCopy: Boolean = false) = DownloadTask(
		mangaId = manga.id, isPaused = false, isSilent = false, chaptersIds = null,
		destination = root, format = null, allowMeteredNetwork = true, favouriteSpace = space,
		downloadSeparateCopy = separateCopy,
	)

	@Test fun currentSourceLayoutRemainsSidecarFreeAndSecondRunDoesNotMutate() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Source (EN)/Maintenance")
		val cbz = chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val before = cbz.readBytes()
		maintenance.run()
		assertFalse(File(folder, "index.json").exists())
		assertArrayEquals(before, cbz.readBytes())
		assertEquals(folder.canonicalPath, db.getFavouriteDownloadIndexDao().findEntry(0, remote.id)?.path)
		maintenance.run()
		assertEquals(DownloadMaintenanceStatus.HEALTHY, result(folder).status)
		assertFalse(File(folder, "index.json").exists())
		assertArrayEquals(before, cbz.readBytes())
		assertEquals(remote.source.name, data.findMangaById(remote.id, true)?.source?.name)
	}

	@Test fun canonicalDiscoveryCoversCurrentAndHistoricalContainersAndReportsMissingRoots() {
		val paths = listOf("downloads/Source (EN)/Current", "downloads/00.Novel/Source (EN)/Novel", "Source/Legacy", "Source (en)/Previous", "Direct")
		val folders = paths.map { folder(it).also { f -> chapterFile(f, "Chapter 1.cbz") } }
		val unavailable = ArrayList<File>()
		val missing = File(root, "missing")
		val scanned = LocalDownloadScanner.scan(listOf(root, missing), unavailable::add)
		assertTrue(scanned.map { it.canonicalPath }.containsAll(folders.map { it.canonicalPath }))
		assertEquals(listOf(missing), unavailable)
	}

	@Test fun mandatoryPartialMatchCannotInstallAnIndexHidingPhysicalChapters() = runBlocking {
		val remote = remote()
		val folder = folder("Direct")
		chapterFile(folder, "Chapter 1.cbz")
		val unmatched = chapterFile(folder, "Unrecognized.cbz")
		val old = MangaIndex(null).apply { setMangaInfo(remote); addChapter(IndexedValue(0, remote.chapters!!.single()), "Chapter 1.cbz") }
		val indexFile = File(folder, "index.json").apply { writeText(old.toString()) }
		val before = indexFile.readBytes()
		val physical = LocalMangaParser(folder, true).getManga(true)
		val linked = LegacyChapterDownloadCompat.linkToRemote(remote, physical)
		val plan = DownloadIndexRepair.plan(folder, indexFile.readText(), remote, physical, linked)
		assertFalse(plan.complete)
		assertNull(plan.content)
		maintenance.run()
		assertArrayEquals(before, indexFile.readBytes())
		assertTrue(unmatched.isFile)
		assertEquals(DownloadMaintenanceStatus.UNRESOLVED, result(folder).status)
	}

	@Test fun staleIndexRetainsOfflineOnlyChapterAndCoverWhileDroppingMissingMapping() = runBlocking {
		val remote = remote()
		val offline = remote.chapters!!.single().copy(id = 107, title = "Removed chapter")
		val missing = offline.copy(id = 108)
		val folder = folder("Legacy")
		chapterFile(folder, "Chapter 1.cbz")
		chapterFile(folder, "Removed chapter.cbz")
		File(folder, "cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
		val old = MangaIndex(null).apply {
			setMangaInfo(remote)
			addChapter(IndexedValue(0, remote.chapters!!.single()), "Chapter 1.cbz")
			addChapter(IndexedValue(1, offline), "Removed chapter.cbz")
			addChapter(IndexedValue(2, missing), "Missing.cbz")
			setCoverEntry("cover.jpg")
		}
		val indexFile = File(folder, "index.json").apply { writeText(old.toString()) }
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		maintenance.run()
		val repaired = MangaIndex(indexFile.readText())
		assertEquals(setOf(101L, 107L), repaired.getMangaInfo()!!.chapters!!.map { it.id }.toSet())
		assertEquals("cover.jpg", repaired.getCoverEntry())
		val first = indexFile.readBytes()
		maintenance.run()
		assertArrayEquals(first, indexFile.readBytes())
	}

	@Test fun corruptIndexRequiresCompleteIndependentEvidence() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val indexFile = File(folder, "index.json").apply { writeText("{broken") }
		maintenance.run()
		assertEquals(remote.id, MangaIndex(indexFile.readText()).getMangaInfo()?.id)
		assertEquals(1, LocalMangaParser(folder).getManga(true).manga.chapters?.size)
	}

	@Test fun corruptPartialIndexRemainsByteIdentical() = runBlocking {
		val folder = folder("Unresolved")
		chapterFile(folder, "unknown.cbz")
		val indexFile = File(folder, "index.json").apply { writeText("{broken") }
		maintenance.run()
		assertEquals("{broken", indexFile.readText())
		assertEquals(DownloadMaintenanceReason.CORRUPT_INDEX, result(folder).reason)
	}

	@Test fun offlineProviderWithPersistedMetadataNeedsNoNetworkAndRetainsRemovedArtifact() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		chapterFile(folder, "Removed chapter.cbz")
		register(remote, folder)
		maintenance.run()
		assertFalse(File(folder, "index.json").exists())
		assertEquals(2, LocalMangaParser(folder).getManga(true).manga.chapters?.size)
		assertEquals(2, local.findSavedMangaAtPath(remote, folder, rememberIdentity = false)?.manga?.chapters?.size)
	}

	@Test fun ambiguousBranchBasenameDoesNotSelectFirstRemoteChapter() = runBlocking {
		val one = remote().chapters!!.single().copy(branch = "A")
		val two = one.copy(id = 102, branch = "B")
		val remote = remote().copy(chapters = listOf(one, two))
		val folder = folder("Branches")
		chapterFile(folder, "Chapter 1.cbz")
		val physical = LocalMangaParser(folder).getManga(true)
		val linked = LegacyChapterDownloadCompat.linkToRemote(remote, physical)
		assertFalse(linked.manga.chapters!!.any { it.id == one.id || it.id == two.id })
	}

	@Test fun legacyHashedAndScanlatorNamesUseTheSharedMatcher() = runBlocking {
		val remote = remote().let { it.copy(chapters = listOf(it.chapters!!.single().copy(scanlator = "Group"))) }
		val chapter = remote.chapters!!.single()
		val hash = MessageDigest.getInstance("MD5").digest(chapter.url.toByteArray()).take(3).joinToString("") { "%02x".format(it.toInt() and 255) }
		for ((i, name) in listOf("Group_Chapter 1_$hash.cbz", "Group_Chapter 1 ($hash).cbz", "Chapter 1.cbz", "Group _ Chapter 1.cbz").withIndex()) {
			val folder = folder("Naming$i")
			chapterFile(folder, name)
			val linked = LegacyChapterDownloadCompat.linkToRemote(remote, LocalMangaParser(folder).getManga(true))
			assertEquals(chapter.id, linked.manga.chapters!!.single().id)
		}
	}

	@Test fun unavailablePrivateStorageKeepsItsOwnershipRow() = runBlocking {
		val remote = remote()
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		val missing = File(root, "disconnected")
		prefs.edit(commit = true) { putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, missing.path) }
		val oldPath = File(missing, "downloads/Maintenance").path
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(remote.id, 1, oldPath))
		maintenance.run()
		assertEquals(oldPath, db.getFavouriteDownloadIndexDao().findEntry(1, remote.id)?.path)
		assertEquals(DownloadMaintenanceStatus.UNAVAILABLE_STORAGE, result(missing).status)
	}

	@Test fun sharedRootWithoutOwnershipDoesNotCreateNormalOrPrivateRows() = runBlocking {
		val remote = remote()
		prefs.edit(commit = true) { remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT) }
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		maintenance.run()
		assertTrue(db.getFavouriteDownloadIndexDao().findEntries(listOf(remote.id)).isEmpty())
		assertEquals(DownloadMaintenanceReason.OWNERSHIP_UNKNOWN, result(folder).reason)
	}

	@Test fun concurrentTriggersShareOneRunAndDoNotPublishDuplicateRows() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val first = async(start = CoroutineStart.UNDISPATCHED) { maintenance.run() }
		val second = async(start = CoroutineStart.UNDISPATCHED) { maintenance.run() }
		first.await(); second.await()
		assertFalse(maintenance.state.value.running)
		assertEquals(1, db.getFavouriteDownloadIndexDao().findEntries(listOf(remote.id)).size)
		assertEquals(1, maintenance.state.value.result!!.items.count { it.path == folder.path })
	}

	@Test fun novelPerChapterEpubKeepsEmbeddedIdentityWithoutDirectorySidecar() = runBlocking {
		val source = MissingMangaSource("LN_MAINTENANCE_TEST")
		val remote = remote().let { it.copy(source = source, chapters = it.chapters!!.map { ch -> ch.copy(source = source) }) }
		data.storeManga(remote.copy(chapters = null), replaceExisting = true, detailsFetched = false)
		val folder = folder("downloads/00.Novel/Source (EN)/Maintenance")
		val epub = File(folder, "Chapter 1.epub")
		val embedded = MangaIndex(null).apply { setMangaInfo(remote); addChapter(IndexedValue(0, remote.chapters!!.single()), "OEBPS/chapter.xhtml") }
		ZipOutputStream(epub.outputStream()).use { zip ->
			for ((name, text) in listOf("index.json" to embedded.toString(), "OEBPS/chapter.xhtml" to "<html><body>Offline</body></html>")) {
				zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
			}
		}
		val before = epub.readBytes()
		maintenance.run()
		assertTrue(result(folder).status in setOf(DownloadMaintenanceStatus.HEALTHY, DownloadMaintenanceStatus.REPAIRED))
		assertFalse(File(folder, "index.json").exists())
		assertArrayEquals(before, epub.readBytes())
		val chapter = LocalMangaParser(folder).getManga(true).manga.chapters!!.single()
		assertEquals(101L, chapter.id)
		assertEquals("OEBPS/chapter.xhtml", chapter.url.toUri().fragment)
	}

	@Test fun unavailableProviderReportsMissingMetadataInsteadOfFalseSuccess() = runBlocking {
		val remote = remote().copy(chapters = null)
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		maintenance.run()
		assertEquals(DownloadMaintenanceStatus.UNRESOLVED, result(folder).status)
		assertEquals(DownloadMaintenanceReason.PROVIDER_UNAVAILABLE, result(folder).reason)
		assertFalse(File(folder, "index.json").exists())
	}

	@Test fun cancellationBeforeAtomicCommitRestoresPreviousIndex() {
		val file = File(root, "index.json").apply { writeText("previous") }
		val result = runCatching { DownloadIndexRepair.write(file, "replacement") { throw kotlinx.coroutines.CancellationException("cancel") } }
		assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
		assertEquals("previous", file.readText())
		assertFalse(File(root, "index.json.new").exists())
	}

	@Test fun normalAndPrivateCopiesKeepIndependentOwnership() = runBlocking {
		val remote = remote()
		val normal = folder("downloads/Maintenance")
		val privateFolder = folder("private/downloads/Maintenance")
		chapterFile(normal, "Chapter 1.cbz")
		chapterFile(privateFolder, "Chapter 1.cbz")
		register(remote, normal)
		// Both explicit ownership rows are legitimate even though the global alias has one path.
		db.getFavouriteDownloadIndexDao().upsert(listOf(
			FavouriteDownloadIndexEntity(remote.id, 0, normal.path),
			FavouriteDownloadIndexEntity(remote.id, 1, privateFolder.path),
		))
		val collector = launch(start = CoroutineStart.UNDISPATCHED) {
			events.take(2).collect { value -> if (value != null) index.emit(value) }
		}
		maintenance.run()
		kotlinx.coroutines.withTimeout(30_000) { collector.join() }
		assertEquals(normal.path, index.getDownloadAliasPaths(listOf(remote.id))[remote.id])
		assertEquals(normal.path, db.getFavouriteDownloadIndexDao().findEntry(0, remote.id)?.path)
		assertEquals(privateFolder.path, db.getFavouriteDownloadIndexDao().findEntry(1, remote.id)?.path)
		assertFalse(File(normal, "index.json").exists())
		assertFalse(File(privateFolder, "index.json").exists())
	}

	@Test fun conflictingSourceIdentityLeavesIndexAndOwnershipUntouched() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val other = remote.copy(id = remote.id + 1, source = MissingMangaSource("MAINTENANCE_TEST_PLUS"))
		val indexFile = File(folder, "index.json").apply { writeText(MangaIndex(null).apply {
			setMangaInfo(other); addChapter(IndexedValue(0, other.chapters!!.single()), "Chapter 1.cbz")
		}.toString()) }
		val before = indexFile.readBytes()
		maintenance.run()
		assertArrayEquals(before, indexFile.readBytes())
		assertEquals(DownloadMaintenanceReason.SOURCE_MISMATCH, result(folder).reason)
		assertTrue(db.getFavouriteDownloadIndexDao().findEntries(listOf(remote.id)).isEmpty())
	}

	@Test fun duplicateTitleFoldersWithoutDurableIdentityRemainAmbiguous() = runBlocking {
		val remote = remote()
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		val one = folder("downloads/Maintenance")
		val two = folder("Maintenance")
		chapterFile(one, "Chapter 1.cbz")
		chapterFile(two, "Chapter 1.cbz")
		maintenance.run()
		assertEquals(DownloadMaintenanceStatus.AMBIGUOUS, result(one).status)
		assertEquals(DownloadMaintenanceStatus.AMBIGUOUS, result(two).status)
		assertTrue(db.getFavouriteDownloadIndexDao().findEntries(listOf(remote.id)).isEmpty())
	}

	@Test fun metadataRecoveryUsesOfflineEvidenceAndValidatesProviderResponses() = runBlocking {
		val remote = remote()
		var calls = 0
		val cached = DownloadMaintenanceMetadata.resolve(remote, null) { calls++; error("network must not run") }
		assertEquals(0, calls)
		assertEquals(remote, cached.manga)
		val fromIndex = DownloadMaintenanceMetadata.resolve(remote.copy(chapters = null), remote) { calls++; error("network must not run") }
		assertEquals(remote, fromIndex.manga)
		assertEquals(0, calls)
		val fetched = DownloadMaintenanceMetadata.resolve(remote.copy(chapters = null), null) { calls++; remote }
		assertTrue(fetched.fetched)
		assertEquals(1, calls)
		val failed = DownloadMaintenanceMetadata.resolve(remote.copy(chapters = null), null) { throw java.io.IOException("offline") }
		assertEquals(DownloadMaintenanceReason.PROVIDER_UNAVAILABLE, failed.failure)
		val mismatch = DownloadMaintenanceMetadata.resolve(remote.copy(chapters = null), null) { remote.copy(id = remote.id + 1) }
		assertEquals(DownloadMaintenanceReason.METADATA_UNAVAILABLE, mismatch.failure)
	}

	@Test fun recreatedViewModelOwnerKeepsProgressAndResult() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val store = androidx.lifecycle.ViewModelStore()
		val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
			@Suppress("UNCHECKED_CAST")
			override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = org.koitharu.kotatsu.settings.DownloadMaintenanceViewModel(maintenance) as T
		}
		val first = androidx.lifecycle.ViewModelProvider(store, factory)[org.koitharu.kotatsu.settings.DownloadMaintenanceViewModel::class.java]
		val recreated = androidx.lifecycle.ViewModelProvider(store, factory)[org.koitharu.kotatsu.settings.DownloadMaintenanceViewModel::class.java]
		assertSame(first, recreated)
		InstrumentationRegistry.getInstrumentation().runOnMainSync { first.start(); recreated.start() }
		kotlinx.coroutines.withTimeout(30_000) { maintenance.state.first { !it.running && it.result != null } }
		assertSame(first.state, recreated.state)
		assertEquals(1, recreated.state.value.result!!.items.count { it.path == folder.path })
		InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
	}

	@Test fun providerRemapCannotRewriteAValidLegacyChapterIdentity() = runBlocking {
		val oldRemote = remote()
		val folder = folder("LegacyStable")
		chapterFile(folder, "Chapter 1.cbz")
		val old = MangaIndex(null).apply { setMangaInfo(oldRemote); addChapter(IndexedValue(0, oldRemote.chapters!!.single()), "Chapter 1.cbz") }
		val physical = DownloadIndexRepair.restoreValidMappings(folder, old, LocalMangaParser(folder, true).getManga(true))
		val changedRemote = oldRemote.copy(chapters = oldRemote.chapters!!.map { it.copy(id = 999) })
		val linked = LegacyChapterDownloadCompat.linkToRemote(changedRemote, physical)
		val plan = DownloadIndexRepair.plan(folder, old.toString(), changedRemote, physical, linked)
		assertTrue(plan.complete)
		assertNull(plan.content)
	}

	@Test fun atomicFailureLeavesPreviousValidIndexUntouched() {
		val index = File(root, "index.json").apply { writeText("previous") }
		// AtomicFile cannot create its .new file when a directory occupies that name.
		File(root, "index.json.new").mkdir()
		assertTrue(runCatching { DownloadIndexRepair.write(index, "replacement") }.isFailure)
		assertEquals("previous", index.readText())
	}


	/**
	 * A global local_index row/alias represents only one copy. Space-owned rows must still let
	 * Details resolve both physical copies and present their chapters as downloaded.
	 */
	@Test fun duplicateMangaIdCopiesUseSpaceOwnershipBeforeGlobalLocalIndex() = runBlocking {
		val remote = remote()
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		val normal = folder("downloads/Source (EN)/Maintenance")
		val privateCopy = folder("private/downloads/Source (EN)/Maintenance")
		chapterFile(normal, "Chapter 1.cbz")
		chapterFile(privateCopy, "Chapter 1.cbz")

		// Deliberately keep only the Private path in the global index and alias.
		index.rememberVerifiedPath(remote.id, privateCopy)
		val localPrivate = LocalMangaParser(privateCopy, true).getManga(true)
		index.registerDownloadAlias(remote.id, localPrivate.manga.id, privateCopy)
		db.getFavouriteDownloadIndexDao().upsert(listOf(
			FavouriteDownloadIndexEntity(remote.id, FavouriteSpace.NORMAL.dbValue, normal.path),
			FavouriteDownloadIndexEntity(remote.id, FavouriteSpace.PRIVATE.dbValue, privateCopy.path),
		))
		assertEquals(privateCopy.path, db.getLocalMangaIndexDao().findPath(remote.id))
		for ((space, expectedPath) in listOf(FavouriteSpace.NORMAL to normal, FavouriteSpace.PRIVATE to privateCopy)) {
			val resolved = requireNotNull(resolver.findSavedManga(remote, space)) { "Missing $space copy" }
			assertEquals(expectedPath, resolved.file.canonicalFile)
			assertTrue(resolved.manga.chapters.orEmpty().any { it.id == 101L && it.source == LocalMangaSource })
			val rows = MangaDetails(remote).copy(localManga = resolved).mapChapters(
				currentChapterId = -1L,
				newCount = 0,
				branch = null,
				bookmarks = emptyList(),
				isGrid = false,
				isDownloadedOnly = false,
			)
			assertTrue("Downloaded icon missing in $space: $rows", rows.any { it.chapter.id == 101L && it.isDownloaded })
		}
	}

	/** A shared physical root cannot transfer one space's download to the other. */
	@Test fun sharedRootRequiresExplicitOwnershipEvenWhenGlobalIndexAndAliasExist() = runBlocking {
		val remote = remote()
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		prefs.edit(commit = true) { remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT) }
		val copy = folder("downloads/Source (EN)/Maintenance")
		chapterFile(copy, "Chapter 1.cbz")
		index.rememberVerifiedPath(remote.id, copy)
		val parsed = LocalMangaParser(copy, true).getManga(true)
		index.registerDownloadAlias(remote.id, parsed.manga.id, copy)
		db.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(remote.id, FavouriteSpace.NORMAL.dbValue, copy.path),
		)
		assertNotNull(resolver.findSavedManga(remote, FavouriteSpace.NORMAL))
		assertNull("Shared-root Local index/alias must not leak into Private", resolver.findSavedManga(remote, FavouriteSpace.PRIVATE))

		// If both spaces have independently explicit ownership, they may reference the same artifact.
		db.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(remote.id, FavouriteSpace.PRIVATE.dbValue, copy.path),
		)
		val privateResolved = requireNotNull(resolver.findSavedManga(remote, FavouriteSpace.PRIVATE))
		assertEquals(copy, privateResolved.file.canonicalFile)
		assertTrue(MangaDetails(remote).copy(localManga = privateResolved).mapChapters(
			-1L, 0, null, emptyList(), false, false,
		).any { it.chapter.id == 101L && it.isDownloaded })
	}

	/**
	 * The configured root is readable while a nested source directory cannot be traversed.
	 * Such a scan is incomplete and must not remove an otherwise owned download.
	 */
	@Test fun unreadableSubtreeNeverProvesDownloadMissing() = runBlocking {
		val remote = remote()
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		val category = favourites.createCategory(
			"Partial scan ${remote.id}", org.koitharu.kotatsu.list.domain.ListSortOrder.NEWEST,
			false, false, true, FavouriteSpace.NORMAL,
		)
		favourites.updateCategoryMemberships(listOf(remote), mapOf(category.id to true), FavouriteSpace.NORMAL)

		val source = folder("downloads/Source (EN)")
		val owned = folder("downloads/Source (EN)/Maintenance")
		chapterFile(owned, "Chapter 1.cbz")
		// This unenumerated candidate must not be pruned merely because the root itself is readable.
		// A complete successful scan would prove it missing; a failed subtree traversal cannot.
		val uncertainPath = File(source, "Unenumerated")
		assertFalse(uncertainPath.exists())
		db.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(remote.id, FavouriteSpace.NORMAL.dbValue, uncertainPath.path),
		)
		assertTrue(root.canRead())
		try {
			assertTrue("Cannot build unreadable-subtree fixture", source.setReadable(false, false))
			assertFalse("Fixture still readable, cannot test an incomplete scan", source.canRead())
			assertFalse("Missing candidate must be absent for a meaningful absence-policy test", uncertainPath.exists())
			val unavailable = ArrayList<File>()
			LocalDownloadScanner.scan(listOf(root), unavailable::add)
			assertTrue("Nested subtree failure must make root scan incomplete", root in unavailable)
			maintenance.run()
			assertEquals(uncertainPath.path, db.getFavouriteDownloadIndexDao().findEntry(
				FavouriteSpace.NORMAL.dbValue, remote.id,
			)?.path)
		} finally {
			assertTrue("Cannot restore test directory permissions", source.setReadable(true, false))
		}
	}


	/**
	 * Reproduces the reported physical-file-present / chapter-icon-false divergence:
	 * a legacy index knows chapter 1, while chapter 2's valid CBZ exists beside it.
	 * Details must not let an incomplete sidecar hide a verified physical chapter.
	 */
	@Test fun legacyIndexMustNotHideAdditionalDownloadedChapterInDetails() = runBlocking {
		val base = remote()
		val second = requireNotNull(base.chapters).single().copy(
			id = 102L, title = "Chapter 2", number = 2f, url = "https://example.invalid/chapter/2",
		)
		val remote = base.copy(chapters = requireNotNull(base.chapters) + second)
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		val folder = folder("downloads/Source (EN)/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		val chapter2 = chapterFile(folder, "Chapter 2.cbz")
		val legacyIndex = MangaIndex(null).apply {
			setMangaInfo(remote.copy(chapters = listOf(requireNotNull(remote.chapters).first())))
			addChapter(IndexedValue(0, requireNotNull(remote.chapters).first()), "Chapter 1.cbz")
		}
		val indexFile = File(folder, "index.json").apply { writeText(legacyIndex.toString()) }
		val originalIndex = indexFile.readBytes()
		db.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(remote.id, FavouriteSpace.NORMAL.dbValue, folder.path),
		)
		val physical = LocalMangaParser(folder, ignoreDirectoryIndex = true).getManga(withDetails = true)
		assertTrue("Index-free parse omitted Chapter 2: ${physical.manga.chapters}",
			physical.manga.chapters.orEmpty().any { it.url.contains("Chapter%202") || it.title?.contains("Chapter 2") == true })
		val linked = LegacyChapterDownloadCompat.linkToRemote(remote, physical)
		assertTrue("Legacy matcher did not link the physical Chapter 2: ${linked.manga.chapters}",
			linked.manga.chapters.orEmpty().any { it.id == second.id && it.source == LocalMangaSource })

		val resolved = requireNotNull(resolver.findSavedManga(remote, FavouriteSpace.NORMAL))
		val rows = MangaDetails(remote).copy(localManga = resolved).mapChapters(
			-1L, 0, null, emptyList(), false, false,
		)
		assertTrue("Physical chapter 2 exists but Details presents not-downloaded: $rows",
			rows.any { it.chapter.id == second.id && it.isDownloaded })
		assertTrue(chapter2.isFile)
		assertArrayEquals("A read-only Details lookup must not rewrite the user's index", originalIndex, indexFile.readBytes())
	}

	@Test fun fullTitleExhentaiDownloadMapsToLocalPages() = runBlocking {
		val gallery = offlineExhentaiGallery(812301L)
		// The ownership row references the persisted manga, as it does for a real download.
		data.storeManga(gallery, replaceExisting = true, detailsFetched = true)
		val receipt = org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership.Receipt(
			FavouriteSpace.NORMAL, java.util.UUID.randomUUID(),
		)
		val cbz = writeGalleryFixture(gallery, appendId = false, bytes = byteArrayOf(1, 2, 3), receipt = receipt)
		assertEquals(gallery.title, cbz.parentFile?.name)
		assertEquals("Chapter.cbz", cbz.name)
		db.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(gallery.id, FavouriteSpace.NORMAL.dbValue, cbz.canonicalPath),
		)
		assertExhentaiLocalRead(gallery, cbz, null)
		assertExhentaiLocalRead(gallery, cbz, FavouriteSpace.NORMAL)
	}

	@Test fun compactOnlyExhentaiDownloadWithoutIdentityRemainsLocalOnly() = runBlocking {
		val gallery = offlineExhentaiGallery(812302L)
		// A legacy compact title and Chapter.cbz alone never prove an online gallery identity.
		val cbz = chapterFile(folder("downloads/Offline gallery × Fixture"), "Chapter.cbz")
		assertLocalCbzReadable(cbz)
		assertNull(resolver.findSavedManga(gallery, null))
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.NORMAL))
		assertFalse(deleteLocal.deleteExhentaiCopy(gallery, FavouriteSpace.NORMAL))
	}

	@Test fun compactExhentaiUnderOfficialSourceCanBeReadByTitleButNotDeleted() = runBlocking {
		val gallery = offlineExhentaiGallery(812305L)
		// Owner-approved title fallback enables reading without inventing gallery ownership.
		val cbz = chapterFile(folder("downloads/ExHentai (OTHER)/Offline gallery × Fixture"), "Chapter.cbz")
		assertLocalCbzReadable(cbz)
		assertExhentaiLocalRead(gallery, cbz, null)
		assertExhentaiLocalRead(gallery, cbz, FavouriteSpace.NORMAL)
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))
		assertTrue(runCatching { deleteLocal.deleteExhentaiCopy(gallery, FavouriteSpace.NORMAL, expectedFile = cbz) }
			.exceptionOrNull() is java.io.IOException)
		assertTrue(cbz.exists())
	}

	@Test fun sameTitleCannotBorrowOrDeleteAnOwnedExhentaiArchive() = runBlocking {
		val owner = offlineExhentaiGallery(812309L)
		val other = offlineExhentaiGallery(812310L)
		val space = FavouriteSpace.NORMAL
		val receipt = ExhentaiChapterOwnership.Receipt(space, java.util.UUID.randomUUID())
		val cbz = writeGalleryFixture(owner, false, byteArrayOf(1, 2, 3), receipt = receipt)
		data.storeManga(owner, replaceExisting = true, detailsFetched = true)
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(owner.id, space.dbValue, cbz.canonicalPath))
		val bytes = cbz.readBytes()
		val sibling = File(cbz.parentFile, "keep.txt").apply { writeText("keep") }

		assertNull(resolver.findSavedManga(other, space))
		assertFalse(deleteLocal.deleteExhentaiCopy(other, space, expectedFile = cbz))
		assertArrayEquals(bytes, cbz.readBytes())
		assertExhentaiLocalRead(owner, cbz, space)
		assertTrue(deleteLocal.deleteExhentaiCopy(owner, space, expectedFile = cbz))
		assertFalse(cbz.exists())
		assertTrue(sibling.exists())
	}

	@Test fun sharedDestinationLegacyCopyCanBeReadButCannotBeDeletedWithoutIdentity() = runBlocking {
		prefs.edit(commit = true) { remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT) }
		val gallery = offlineExhentaiGallery(812313L)
		val space = FavouriteSpace.NORMAL
		data.storeManga(gallery, replaceExisting = true, detailsFetched = true)
		val cbz = chapterFile(folder("downloads/ExHentai (OTHER)/Offline gallery × Fixture"), "Chapter.cbz")
		assertNull(resolver.findSavedManga(gallery, space))
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(gallery.id, space.dbValue, cbz.canonicalPath))
		assertExhentaiLocalRead(gallery, cbz, space)
		assertTrue(runCatching { deleteLocal.deleteExhentaiCopy(gallery, space, expectedFile = cbz) }.exceptionOrNull() is java.io.IOException)
		assertTrue(cbz.exists())
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))
	}

	@Test fun exactReceiptFindsExhentaiArchiveOutsideConfiguredRoots() = runBlocking {
		val gallery = offlineExhentaiGallery(812314L)
		val space = FavouriteSpace.NORMAL
		val selected = folder("alternate")
		prefs.edit(commit = true) {
			putString(AppSettings.KEY_LOCAL_STORAGE, folder("configured").path)
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, folder("private").path)
		}
		val receipt = ExhentaiChapterOwnership.Receipt(space, java.util.UUID.randomUUID())
		val cbz = writeGalleryFixture(gallery, false, byteArrayOf(1, 2, 3), receipt = receipt, destination = selected)
		data.storeManga(gallery, replaceExisting = true, detailsFetched = true)
		assertNull(resolver.findSavedManga(gallery, space))
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(gallery.id, space.dbValue, cbz.canonicalPath))
		// A matching receipt and persisted row do not make files outside the selected
		// active destination visible. Selecting that destination restores the exact copy.
		assertNull(resolver.findSavedManga(gallery, space))
		assertLocalCbzReadable(cbz)
		destinationStore.setRoot(space, selected)
		assertExhentaiLocalRead(gallery, cbz, space)
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))
	}

	@Test fun singleCbzPublicationDoesNotReplaceNormalOwnershipForAPrivateTask() = runBlocking {
		val gallery = offlineExhentaiGallery(812315L)
		val normal = FavouriteSpace.NORMAL
		val privateSpace = FavouriteSpace.PRIVATE
		val normalReceipt = ExhentaiChapterOwnership.Receipt(normal, UUID.randomUUID())
		val privateReceipt = ExhentaiChapterOwnership.Receipt(privateSpace, UUID.randomUUID())
		val normalFile = writeGalleryFixture(gallery, false, byteArrayOf(1), DownloadFormat.SINGLE_CBZ, normalReceipt)
		val privateFile = writeGalleryFixture(gallery, false, byteArrayOf(2), DownloadFormat.SINGLE_CBZ,
			privateReceipt, destination = File(root, "private"))
		data.storeManga(gallery, replaceExisting = true, detailsFetched = true)
		val dao = db.getFavouriteDownloadIndexDao()
		val normalRow = FavouriteDownloadIndexEntity(gallery.id, normal.dbValue, normalFile.canonicalPath)
		val privateRow = FavouriteDownloadIndexEntity(gallery.id, privateSpace.dbValue, privateFile.canonicalPath)
		dao.upsert(listOf(normalRow, privateRow))
		val publication = checkNotNull(local.findExhentaiAtPath(
			gallery, privateFile, withDetails = true, space = privateSpace, request = privateReceipt.request,
		)).local
		assertEquals(gallery.id, publication.manga.id)
		ownershipIndex.emit(publication)

		assertEquals(normalRow, dao.findEntry(normal.dbValue, gallery.id))
		assertEquals(privateRow, dao.findEntry(privateSpace.dbValue, gallery.id))
		assertExhentaiLocalRead(gallery, normalFile, normal)
		assertExhentaiLocalRead(gallery, privateFile, privateSpace)
	}

	@Test fun singleCbzInAHistoricalSharedRootUsesTheReceiptAfterPrivateMoves() = runBlocking {
		val gallery = offlineExhentaiGallery(812316L)
		val normal = FavouriteSpace.NORMAL
		val privateSpace = FavouriteSpace.PRIVATE
		prefs.edit(commit = true) { putStringSet("private_download_legacy_roots", setOf(root.path)) }
		val receipt = ExhentaiChapterOwnership.Receipt(normal, UUID.randomUUID())
		val cbz = writeGalleryFixture(gallery, false, byteArrayOf(1), DownloadFormat.SINGLE_CBZ, receipt)
		data.storeManga(gallery, replaceExisting = true, detailsFetched = true)
		val dao = db.getFavouriteDownloadIndexDao()
		val normalRow = FavouriteDownloadIndexEntity(gallery.id, normal.dbValue, cbz.canonicalPath)
		dao.upsert(normalRow)
		val publication = checkNotNull(local.findExhentaiAtPath(
			gallery, cbz, withDetails = true, space = normal, request = receipt.request,
		)).local
		ownershipIndex.emit(publication)
		assertNull(dao.findEntry(privateSpace.dbValue, gallery.id))

		// Earlier builds could already have inferred this stale row. The scoped receipt
		// proves which space may read the existing file without reassigning it.
		val staleRow = normalRow.copy(space = privateSpace.dbValue)
		dao.upsert(staleRow)
		assertExhentaiLocalRead(gallery, cbz, normal)
		assertNull(resolver.findSavedManga(gallery, privateSpace))
		assertEquals(staleRow, dao.findEntry(privateSpace.dbValue, gallery.id))

		val foreign = offlineExhentaiGallery(812317L)
		data.storeManga(foreign, replaceExisting = true, detailsFetched = true)
		dao.upsert(staleRow.copy(mangaId = foreign.id))
		assertNull(resolver.findSavedManga(gallery, normal))
	}

	@Test fun unownedFullTitleIsReadOnlyAndCompactOutsideExhentaiNeedsExactOwnership() = runBlocking {
		val gallery = offlineExhentaiGallery(812312L)
		val compact = chapterFile(folder("downloads/Offline gallery × Fixture"), "Chapter.cbz")
		val full = chapterFile(folder("downloads/ExHentai (OTHER)/${gallery.title}"), "Chapter.cbz")
		assertLocalCbzReadable(full)
		assertLocalCbzReadable(compact)
		assertExhentaiLocalRead(gallery, full, FavouriteSpace.NORMAL)
		assertTrue(runCatching { deleteLocal.deleteExhentaiCopy(gallery, FavouriteSpace.NORMAL, expectedFile = full) }
			.exceptionOrNull() is java.io.IOException)
		assertTrue(full.exists())
		assertTrue(full.delete())
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.NORMAL))
		// A persisted exact gallery+space ownership row restores read access, not delete authority.
		data.storeManga(gallery, replaceExisting = true, detailsFetched = true)
		db.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(gallery.id, FavouriteSpace.NORMAL.dbValue, compact.canonicalPath),
		)
		assertExhentaiLocalRead(gallery, compact, FavouriteSpace.NORMAL)
		assertTrue(runCatching { deleteLocal.deleteExhentaiCopy(gallery, FavouriteSpace.NORMAL, expectedFile = compact) }
			.exceptionOrNull() is java.io.IOException)
		assertTrue(compact.exists())
	}

	@Test fun replacingExhentaiArchiveAtSamePathRechecksCurrentArtifact() = runBlocking {
		val gallery = offlineExhentaiGallery(812303L)
		val space = FavouriteSpace.NORMAL
		data.storeManga(gallery, replaceExisting = true, detailsFetched = true)
		// The same physical destination can be shared by Normal and Private.
		prefs.edit(commit = true) { putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path) }
		val receipt = org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership.Receipt(
			space, java.util.UUID.randomUUID(),
		)
		val cbz = writeGalleryFixture(gallery, appendId = false, bytes = byteArrayOf(1, 2, 3), receipt = receipt)
		val ownership = FavouriteDownloadIndexEntity(gallery.id, space.dbValue, cbz.canonicalPath)
		val dao = db.getFavouriteDownloadIndexDao()
		dao.upsert(ownership)
		assertExhentaiLocalRead(gallery, cbz, null)
		assertExhentaiLocalRead(gallery, cbz, space)

		// Keep the folder, filename and recorded path. Change only the physical ZIP.
		val oldBytes = cbz.readBytes()
		chapterFile(checkNotNull(cbz.parentFile), cbz.name)
		assertFalse(oldBytes.contentEquals(cbz.readBytes()))
		assertExhentaiLocalRead(gallery, cbz, null)
		assertExhentaiLocalRead(gallery, cbz, space)
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))
		assertEquals(ownership, dao.findEntry(space.dbValue, gallery.id))

		// A replacement with a different gallery marker must invalidate the recorded path.
		ZipOutputStream(cbz.outputStream()).use { zip ->
			zip.setComment(org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership.comment(812304L))
			zip.putNextEntry(ZipEntry("other-gallery.jpg"))
			zip.write(byteArrayOf(4, 5, 6))
			zip.closeEntry()
		}
		val replacement = cbz.readBytes()
		assertNull(resolver.findSavedManga(gallery, null))
		assertNull(resolver.findSavedManga(gallery, space))
		assertFalse(deleteLocal.deleteExhentaiCopy(gallery, space, expectedFile = cbz))
		assertArrayEquals(replacement, cbz.readBytes())
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))
		assertEquals(ownership, dao.findEntry(space.dbValue, gallery.id))
	}

	@Test fun unmarkedExhentaiWithSharedActiveRootNeedsSpaceOwnership() = runBlocking {
		// Private follows Normal, so both lookups can see the same candidate path.
		prefs.edit(commit = true) { remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT) }
		assertOverlappingExhentaiRequiresOwnership(812307L)
	}

	@Test fun unmarkedExhentaiWithOverlappingHistoricalRootNeedsSpaceOwnership() = runBlocking {
		// Active destinations are distinct, but this copy is inside a Private historical root.
		prefs.edit(commit = true) {
			putStringSet("private_download_legacy_roots", setOf(root.path))
		}
		assertOverlappingExhentaiRequiresOwnership(812308L)
	}

	private suspend fun assertOverlappingExhentaiRequiresOwnership(gid: Long) {
		val gallery = offlineExhentaiGallery(gid)
		val directory = folder("downloads/ExHentai (OTHER)/Offline gallery × Fixture")
		val cbz = chapterFile(directory, "Chapter.cbz")
		register(gallery, directory)
		val dao = db.getFavouriteDownloadIndexDao()
		// Path/title and a persisted Local alias are only discovery hints, never gallery proof.
		assertNull(local.findExhentaiAtPath(gallery, directory, withDetails = true))
		assertLocalCbzReadable(cbz)

		// Browse must not recover the same unowned file via the global Local index.
		assertNull(resolver.findSavedManga(gallery, null))
		// A matching Local alias and a readable archive do not establish space ownership.
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.NORMAL))
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))
		assertTrue(dao.findEntries(listOf(gallery.id)).isEmpty())

		dao.upsert(FavouriteDownloadIndexEntity(gallery.id, FavouriteSpace.PRIVATE.dbValue, cbz.canonicalPath))
		// Persisted ownership of a historical directory cannot override the currently
		// selected Private destination. Re-selecting the shared destination restores reads.
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))
		assertNull(resolver.findSavedManga(gallery, null))
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.NORMAL))
		destinationStore.setRoot(FavouriteSpace.PRIVATE, root)
		assertExhentaiLocalRead(gallery, cbz, FavouriteSpace.PRIVATE)
		destinationStore.setRoot(FavouriteSpace.PRIVATE, File(root, "private"))
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))

		dao.delete(FavouriteSpace.PRIVATE.dbValue, gallery.id)
		dao.upsert(FavouriteDownloadIndexEntity(gallery.id, FavouriteSpace.NORMAL.dbValue, cbz.canonicalPath))
		assertExhentaiLocalRead(gallery, cbz, FavouriteSpace.NORMAL)
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))
	}

	@Test fun compareV1424IndexedFallbackWithCurrentExhentaiResolver() = runBlocking {
		val gallery = offlineExhentaiGallery(812306L)
		val chapter = requireNotNull(gallery.chapters).single()
		// Neither deterministic source paths nor legacy language buckets find this copy.
		// Both routes get the same bytes and the same persisted remote-to-local alias.
		val directory = folder("downloads/Archived/Offline gallery × Fixture")
		val cbz = chapterFile(directory, "Chapter.cbz")
		val bytes = cbz.readBytes()
		register(gallery, directory)
		assertEquals(directory, index.findIndexedPath(gallery.id)?.canonicalFile)
		assertTrue(db.getFavouriteDownloadIndexDao().findEntries(listOf(gallery.id)).isEmpty())
		assertEquals(org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership.Evidence.Unmarked,
			org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership.inspect(cbz))

		// Exercise the v1.4.24 index-hit branch using its unchanged chapter compatibility helpers.
		// This is not execution of the v1.4.24 APK or its complete resolver.
		val indexed = checkNotNull(index.get(gallery.id, withDetails = true))
		val migrated = index.linkMigratedDownloadChapters(gallery.id, indexed)
		val old = org.koitharu.kotatsu.local.data.LegacySplitChapterCompat.linkToRemote(gallery, migrated)
			?: LegacyChapterDownloadCompat.linkToRemote(gallery, migrated)
		val details = MangaDetails(gallery).copy(localManga = old)
		assertTrue(details.mapChapters(-1L, 0, chapter.branch, emptyList(), false, false)
			.any { it.chapter.id == chapter.id && it.isDownloaded })
		val offline = details.allChapters.single { it.id == chapter.id }
		assertEquals(LocalMangaSource, offline.source)
		assertTrue(local.getPages(offline).isNotEmpty())

		// The old local parser can read the archive, but the online resolver must not
		// turn an unverified indexed alias into evidence of gallery ownership.
		assertLocalCbzReadable(cbz)
		assertNull(local.findExhentaiAtPath(gallery, directory, withDetails = true))
		assertNull(resolver.findSavedManga(gallery, null))
		assertNull(resolver.findSavedManga(gallery, FavouriteSpace.PRIVATE))
		assertArrayEquals(bytes, cbz.readBytes())
	}

	private fun offlineExhentaiGallery(gid: Long): Manga = galleryFixture(gid).let { gallery ->
		gallery.copy(
			title = "[Circle (Artist)] Offline gallery × Fixture [Chinese] [Translation Group] [Digital]",
			chapters = listOf(requireNotNull(gallery.chapters).single().copy(branch = "Chinese", scanlator = null)),
		)
	}

	private suspend fun assertLocalCbzReadable(cbz: File) {
		val before = cbz.readBytes()
		val parsed = LocalMangaParser(cbz).getManga(withDetails = true)
		val chapter = checkNotNull(parsed.manga.chapters).single()
		assertEquals(LocalMangaSource, chapter.source)
		assertTrue("An XML-free imported CBZ must remain readable in Local", local.getPages(chapter).isNotEmpty())
		assertArrayEquals("Read-only discovery must not rewrite a CBZ", before, cbz.readBytes())
	}

	private suspend fun assertExhentaiLocalRead(gallery: Manga, cbz: File, space: FavouriteSpace?) {
		val bytes = cbz.readBytes()
		val directory = checkNotNull(cbz.parentFile)
		val entries = directory.list().orEmpty().toSet()
		val chapter = requireNotNull(gallery.chapters).single()
		val saved = checkNotNull(resolver.findSavedManga(gallery, space)) {
			"Resolver returned no local artifact for ${cbz.name} in $space"
		}
		assertEquals(cbz.canonicalFile, saved.file.canonicalFile)
		val details = MangaDetails(gallery).copy(localManga = saved)
		val rows = details.mapChapters(-1L, 0, chapter.branch, emptyList(), false, false)
		assertTrue("Details did not mark the gallery chapter as downloaded in $space",
			rows.any { it.chapter.id == chapter.id && it.isDownloaded })
		val readerChapter = details.allChapters.single { it.id == chapter.id }
		assertEquals(LocalMangaSource, readerChapter.source)
		assertEquals(cbz.canonicalFile, File(java.net.URI(readerChapter.url.substringBefore('#'))).canonicalFile)
		assertTrue("Local CBZ must provide pages without calling the extension", local.getPages(readerChapter).isNotEmpty())
		assertArrayEquals(bytes, cbz.readBytes())
		assertEquals(entries, directory.list().orEmpty().toSet())
	}

	@Test fun fullExhentaiTitleFindsLegacyFileOnlyWithinRequestedRoot() = runBlocking {
		val seed = remote()
		val source = MissingMangaSource(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		val gallery = "/g/210/token/"
		val chapter = seed.chapters!!.single().copy(title = "Chapter", source = source, url = gallery)
		val remote = seed.copy(
			title = "[Circle] Aaaa (29) [English]",
			source = source,
			url = gallery,
			publicUrl = "https://e-hentai.org$gallery",
			chapters = listOf(chapter),
		)
		// A missing installed parser has no Tsuki source directory metadata. Use the historical
		// direct layout for this offline fixture, without installing a plugin or fetching metadata.
		val legacyFolder = folder("downloads/Aaaa")
		val cbz = chapterFile(legacyFolder, "Chapter.cbz")
		val original = cbz.readBytes()
		// Legacy title cleanup provides candidate discovery, not gallery verification.
		assertLocalCbzReadable(cbz)
		assertNull(local.findSavedMangaInRoot(remote, root))
		assertNull(local.findSavedMangaInRoot(remote, File(root, "private")))
		assertArrayEquals(original, cbz.readBytes())
		assertEquals(setOf("Chapter.cbz"), legacyFolder.list().orEmpty().toSet())
	}

	@Test fun sameTitleExhentaiCopiesResolveByArtifactAndDeleteIndependently() = runBlocking {
		val a = galleryFixture(812310)
		val b = galleryFixture(812311)
		val first = writeGalleryFixture(a, false, byteArrayOf(11, 12))
		assertNull(local.findSavedMangaInRoot(b, root))
		val second = writeGalleryFixture(b, true, byteArrayOf(21, 22))
		assertEquals(first.parentFile, second.parentFile)
		assertEquals("Chapter.cbz", first.name)
		assertTrue(second.name.contains("812311"))
		val owner = db.getFavouriteDownloadIndexDao()
		val space = FavouriteSpace.NORMAL
		for ((manga, file) in listOf(a to first, b to second)) {
			data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			owner.upsert(FavouriteDownloadIndexEntity(mangaId = manga.id, space = space.dbValue, path = file.canonicalPath))
			assertEquals(file.canonicalFile, local.findSavedMangaInRoot(manga, root)?.file?.canonicalFile)
			assertEquals(file.canonicalFile, resolver.findSavedManga(manga, space)?.file?.canonicalFile)
		}
		fun contents(file: File) = ZipFile(file).use { zip ->
			zip.getInputStream(zip.entries().nextElement()).use { it.readBytes() }
		}
		assertArrayEquals(byteArrayOf(21, 22), contents(second))
		assertTrue(deleteLocal.deleteExhentaiCopy(b, space, second))
		assertFalse(second.exists())
		assertArrayEquals(byteArrayOf(11, 12), contents(first))
		assertEquals(first.canonicalFile, resolver.findSavedManga(a, space)?.file?.canonicalFile)
		assertNull(local.findSavedMangaInRoot(b, root))
	}

	@Test fun recordedLegacyOwnershipBlocksAnotherGalleryFromBorrowingTheFile() = runBlocking {
		val a = galleryFixture(812320)
		val b = galleryFixture(812321)
		val directory = folder("downloads/Same title")
		val cbz = chapterFile(directory, "Chapter.cbz")
		val before = cbz.readBytes()
		// Neither gallery can borrow a title-only archive before ownership is recorded.
		assertLocalCbzReadable(cbz)
		assertNull(local.findSavedMangaInRoot(b, root))
		assertNull(local.findSavedMangaInRoot(a, root))
		data.storeManga(a, replaceExisting = true, detailsFetched = true)
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(
			mangaId = a.id, space = FavouriteSpace.NORMAL.dbValue, path = directory.canonicalPath,
		))
		// An exact gallery+space directory row allows read-only recovery of the unique CBZ.
		assertEquals(cbz.canonicalFile, resolver.findSavedManga(a, FavouriteSpace.NORMAL)?.file?.canonicalFile)
		assertNull(local.findSavedMangaInRoot(b, root))
		assertNull(resolver.findSavedManga(b, FavouriteSpace.NORMAL))
		assertTrue(runCatching { deleteLocal.deleteExhentaiCopy(a, FavouriteSpace.NORMAL, expectedFile = cbz) }
			.exceptionOrNull() is java.io.IOException)
		assertArrayEquals(before, cbz.readBytes())
	}

	@Test fun plainExhentaiCopiesKeepIndependentPathsOnASharedRoot() = runBlocking {
		prefs.edit(commit = true) { putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path) }
		val a = galleryFixture(812330)
		val b = galleryFixture(812331)
		val first = writeGalleryFixture(a, false, byteArrayOf(31, 32))
		val second = writeGalleryFixture(b, false, byteArrayOf(41, 42))
		assertEquals("Chapter.cbz", first.name)
		assertEquals(first.name, second.name)
		assertNotEquals(first.parentFile, second.parentFile)
		val owners = db.getFavouriteDownloadIndexDao()
		for ((manga, space, file) in listOf(
			Triple(a, FavouriteSpace.NORMAL, first), Triple(b, FavouriteSpace.PRIVATE, second),
		)) {
			data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			owners.upsert(FavouriteDownloadIndexEntity(mangaId = manga.id, space = space.dbValue, path = file.canonicalPath))
			val saved = resolver.findSavedManga(manga, space)
			assertEquals(file.canonicalFile, saved?.file?.canonicalFile)
			assertEquals(file.toUri().toString(), saved?.manga?.chapters?.single()?.url)
		}
		assertNull(resolver.findSavedManga(a, FavouriteSpace.PRIVATE))
		assertNull(resolver.findSavedManga(b, FavouriteSpace.NORMAL))
		val firstBytes = first.readBytes()
		assertTrue(deleteLocal.deleteExhentaiCopy(b, FavouriteSpace.PRIVATE, second))
		assertFalse(second.exists())
		assertArrayEquals(firstBytes, first.readBytes())
		assertEquals(first.canonicalPath, owners.findEntry(FavouriteSpace.NORMAL.dbValue, a.id)?.path)
	}

	@Test fun embeddedLegacyArchiveIdentityOverridesTheChapterBasename() = runBlocking {
		val a = galleryFixture(812350)
		val b = galleryFixture(812351)
		val single = writeGalleryFixture(a, false, byteArrayOf(71, 72), DownloadFormat.SINGLE_CBZ)
		val directory = folder("downloads/Same title")
		val legacy = File(directory, "Chapter.cbz")
		check(single.renameTo(legacy))
		val before = legacy.readBytes()
		assertNull(local.findSavedMangaInRoot(b, root))
		assertEquals(legacy.canonicalFile, local.findSavedMangaInRoot(a, root)?.file?.canonicalFile)
		assertArrayEquals(before, legacy.readBytes())
	}

	@Test fun publishedPlainExhentaiArchiveIsRecoveredWithoutAnAlias() = runBlocking {
		val a = galleryFixture(812340)
		val b = galleryFixture(812341)
		val first = writeGalleryFixture(a, false, byteArrayOf(51, 52))
		val second = writeGalleryFixture(b, false, byteArrayOf(61, 62))
		assertNotEquals(first.parentFile, second.parentFile)
		val recovered = local.findSavedMangaInRoot(b, root)
		assertEquals(second.canonicalFile, recovered?.file?.canonicalFile)
		java.util.zip.ZipFile(checkNotNull(recovered).file).use { zip ->
			assertArrayEquals(byteArrayOf(61, 62), zip.getInputStream(zip.entries().nextElement()).use { it.readBytes() })
		}
		assertNull(local.findSavedMangaInRoot(galleryFixture(812342), root))
	}

	@Test fun sharedRootRecoveryUsesTheArchiveReceiptNotThePath() = runBlocking {
		prefs.edit(commit = true) { putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, root.path) }
		val manga = galleryFixture(812360)
		val request = java.util.UUID.randomUUID()
		val receipt = org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership.Receipt(FavouriteSpace.PRIVATE, request)
		val bytes = byteArrayOf(81, 82)
		val file = writeGalleryFixture(manga, false, bytes, receipt = receipt)
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.PRIVATE.dbValue, manga.id))
		assertNull(resolver.findSavedManga(manga, FavouriteSpace.NORMAL))
		assertEquals(file.canonicalFile, resolver.findSavedManga(manga, FavouriteSpace.PRIVATE)?.file?.canonicalFile)
		assertNull(local.findExhentaiInRoots(manga, listOf(root), false, FavouriteSpace.PRIVATE, java.util.UUID.randomUUID()))
		val recovered = local.findExhentaiInRoots(manga, listOf(root), false, FavouriteSpace.PRIVATE, request)
		assertEquals(file.canonicalFile, recovered?.file?.canonicalFile)
		ZipFile(checkNotNull(recovered).file).use { zip ->
			assertArrayEquals(bytes, zip.getInputStream(zip.entries().nextElement()).use { it.readBytes() })
		}
	}

	@Test fun staleExhentaiTaskCannotDeleteAnArchiveAtAReusedPath() = runBlocking {
		val manga = galleryFixture(812370)
		val space = FavouriteSpace.NORMAL
		val oldRequest = java.util.UUID.randomUUID()
		val newRequest = java.util.UUID.randomUUID()
		fun receipt(id: java.util.UUID) =
			org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership.Receipt(space, id)
		val old = writeGalleryFixture(manga, false, byteArrayOf(91), receipt = receipt(oldRequest))
		check(old.delete())
		check(old.parentFile?.delete() == true)
		val replacement = writeGalleryFixture(manga, false, byteArrayOf(92), receipt = receipt(newRequest))
		assertEquals(old.canonicalFile, replacement.canonicalFile)
		data.storeManga(manga, replaceExisting = true, detailsFetched = true)
		val dao = db.getFavouriteDownloadIndexDao()
		dao.upsert(FavouriteDownloadIndexEntity(mangaId = manga.id, space = space.dbValue, path = replacement.canonicalPath))
		val before = replacement.readBytes()
		val failure = runCatching {
			deleteLocal.deleteOwnedDownloadAtRoot(manga, root, space, old, oldRequest)
		}.exceptionOrNull()
		assertTrue(failure is java.io.IOException)
		assertArrayEquals(before, replacement.readBytes())
		assertEquals(replacement.canonicalPath, dao.findEntry(space.dbValue, manga.id)?.path)
		deleteLocal.deleteOwnedDownloadAtRoot(manga, root, space, replacement, newRequest)
		assertFalse(replacement.exists())
	}

	@Test fun exhentaiReservationCannotReplaceAFileCreatedAfterReservation() {
		val directory = folder("reservations")
		val victim = byteArrayOf(3, 4, 5)
		org.koitharu.kotatsu.local.data.output.ExhentaiNewArchive.reserve(directory, "Gallery", "Chapter.cbz", false).use {
			it.staging.writeBytes(byteArrayOf(1, 2))
			it.target.writeBytes(victim)
			assertTrue(runCatching { it.commit() }.isFailure)
			assertArrayEquals(victim, it.target.readBytes())
		}
		assertEquals(setOf("Chapter.cbz"), File(directory, "Gallery").list().orEmpty().toSet())
	}

	private fun galleryFixture(gid: Long): Manga {
		val url = "https://exhentai.org/g/$gid/token/"
		val source = MissingMangaSource(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
		return SampleData.manga.copy(id = gid, title = "Same title", url = url, publicUrl = url, source = source,
			chapters = listOf(SampleData.chapter.copy(id = gid + 1000000, title = "Chapter", url = url, source = source)))
	}

	private suspend fun writeGalleryFixture(
		manga: Manga,
		appendId: Boolean,
		bytes: ByteArray,
		format: DownloadFormat = DownloadFormat.MULTIPLE_CBZ,
		receipt: org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership.Receipt? = null,
			destination: File = root,
	): File {
		val page = File(root, "fixture.jpg").apply { writeBytes(bytes) }
		val output = LocalMangaOutput.createExhentaiArchive(destination, manga, format, appendId, receipt)
		try {
			val chapter = requireNotNull(manga.chapters).single()
			output.addPage(IndexedValue(0, chapter), page, 0, org.koitharu.kotatsu.core.util.MimeTypes.getMimeTypeFromExtension(page.name))
			output.flushChapter(chapter)
			output.finish()
			return checkNotNull(output.committedFile)
		} finally {
			output.close()
			page.delete()
		}
	}

	private fun remote(): Manga {
		val source = MissingMangaSource("MAINTENANCE_TEST")
		ids += 950_010L
		return SampleData.manga.copy(id = 950_010L, title = "Maintenance", source = source,
			chapters = listOf(SampleData.chapter.copy(id = 101, title = "Chapter 1", number = 1f, scanlator = null, branch = null, source = source, url = "https://example.invalid/chapter/1")))
	}
	private fun folder(path: String) = File(root, path).canonicalFile.apply { check(mkdirs() || isDirectory) }
	private fun chapterFile(folder: File, name: String): File = File(folder, name).apply {
		ZipOutputStream(outputStream()).use { zip -> zip.putNextEntry(ZipEntry("1.jpg")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry() }
	}
	private suspend fun register(remote: Manga, folder: File) {
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		val physical = LocalMangaParser(folder, true).getManga(true)
		ids += physical.manga.id
		index.registerDownloadAlias(remote.id, physical.manga.id, folder)
	}
	private fun result(file: File) = maintenance.state.value.result!!.items.single { it.path == file.canonicalPath }
}

/** Only populates WorkManager terminal history; never runs a real download. */
class RemovalHistoryFixtureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result = Result.success(inputData)
}
