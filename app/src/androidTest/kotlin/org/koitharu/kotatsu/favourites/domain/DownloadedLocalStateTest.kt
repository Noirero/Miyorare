package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.test.core.app.ActivityScenario
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.details.ui.DetailsExpressiveActivity
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet
import org.koitharu.kotatsu.details.ui.pager.ChapterGroupingMode
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.list.ui.adapter.MangaListAdapter
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.settings.SettingsActivity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageView
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runComposeUiTest
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareThemeColors
import org.koitharu.kotatsu.details.ui.InlineChapterCard
import org.koitharu.kotatsu.list.ui.FavouriteCoverOverlayView
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext
import org.koitharu.kotatsu.list.ui.model.ModernCoverState
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.widgets.IconsView
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.details.data.DetailsNavigationCache
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.data.ChapterPersonalRepository
import org.koitharu.kotatsu.details.data.ChapterProgressRepository
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.adapter.ChaptersAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import kotlinx.coroutines.withContext
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.details.data.ChapterPersonalKey
import org.koitharu.kotatsu.details.data.ChapterProgressEntity
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.domain.NormalDownloadTaskAdmission
import org.koitharu.kotatsu.download.domain.NormalDownloadMutationFence
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.NormalDownloadObservationDao
import org.koitharu.kotatsu.favourites.data.NormalDownloadObservationEntity
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListViewModel
import org.koitharu.kotatsu.history.domain.MarkAsReadUseCase
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.DownloadVerification
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LegacyChapterDownloadCompat
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.NormalDownloadDirectoryReconciler
import org.koitharu.kotatsu.local.data.NormalDownloadDirectoryDiscovery
import org.koitharu.kotatsu.local.data.NormalDownloadPriorIdentityReader
import org.koitharu.kotatsu.local.data.NormalScannerParentListing
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.data.output.LocalMangaDirOutput
import org.koitharu.kotatsu.local.data.output.RootFileCommitOps
import org.koitharu.kotatsu.local.data.output.RealRootFileCommitOps
import org.koitharu.kotatsu.local.data.output.commitRootReplacement
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.DownloadedMangaResolver
import org.koitharu.kotatsu.local.domain.NormalDownloadReadResolver
import org.koitharu.kotatsu.local.domain.NormalDownloadChapterAction
import org.koitharu.kotatsu.local.domain.NormalDownloadDeleteAction
import org.koitharu.kotatsu.local.domain.NormalDeleteApproval
import org.koitharu.kotatsu.local.domain.MangaLock
import org.koitharu.kotatsu.local.library.LocalContentReader
import org.koitharu.kotatsu.reader.domain.ChaptersLoader
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import javax.inject.Inject

/** Uses the native list/filter and storage pipeline; no Details or source/network request. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DownloadedLocalStateTest {
	@get:Rule val hiltRule = HiltAndroidRule(this)
	@Inject lateinit var db: MangaDatabase
	@Inject lateinit var detailsInteractor: DetailsInteractor
	@Inject lateinit var detailsBookmarks: BookmarksRepository
	@Inject lateinit var detailsHistory: HistoryRepository
	@Inject lateinit var detailsScheduler: DownloadWorker.Scheduler
	@Inject lateinit var detailsPersonal: ChapterPersonalRepository
	@Inject lateinit var detailsProgress: ChapterProgressRepository
	@Inject lateinit var detailsNormalResolver: NormalDownloadReadResolver
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var data: MangaDataRepository
	@Inject lateinit var index: LocalMangaIndex
	@Inject lateinit var local: LocalMangaRepository
	@Inject lateinit var resolver: DownloadedMangaResolver
	@Inject lateinit var normalDownloadScope: NormalDownloadScope
	@Inject lateinit var readerRepositoryFactory: MangaRepository.Factory
	@Inject lateinit var readerContent: LocalContentReader
	@Inject internal lateinit var normalDirectoryDiscovery: NormalDownloadDirectoryDiscovery
	@Inject lateinit var deleteLocal: DeleteLocalMangaUseCase
	@Inject lateinit var ownership: FavouriteDownloadOwnershipIndex
	@Inject lateinit var destinations: DownloadDestinationStore
	@Inject lateinit var classifier: DownloadedContentClassifier
	@Inject internal lateinit var liveObservations: NormalDownloadObservationStore
	@Inject internal lateinit var scannerUiCoordinatorProvider: javax.inject.Provider<NormalDownloadScanCoordinator>
	@Inject lateinit var reconciler: LegacyFavouriteDownloadReconciler
	@Inject lateinit var repository: FavouritesRepository
	@Inject lateinit var mapper: MangaListMapper
	@Inject lateinit var markAsRead: MarkAsReadUseCase
	@Inject lateinit var filters: FavoritesListQuickFilter.Factory
	@Inject lateinit var quickFilters: FavouriteQuickFilterStore
	@Inject lateinit var search: FavouritesSearchMatcher
	@Inject lateinit var contentType: FavouriteContentTypeStore
	@Inject lateinit var display: FavouriteDisplayPreferences
	@Inject lateinit var unread: FavouriteUnreadCounter
	@Inject lateinit var sources: FavouriteSourceFilterStore
	@Inject lateinit var detailsCache: DetailsNavigationCache
	@Inject lateinit var downloadedSort: DownloadedFavouritesSortPreferences
	@Inject lateinit var groups: LibraryGroupsRepository
	@Inject lateinit var setSelection: FavouriteSetSelectionStore
	@Inject lateinit var duplicates: LibraryDuplicateScanUseCase
	@Inject @LocalStorageChanges lateinit var events: MutableSharedFlow<LocalManga?>

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext
	private val viewModels = ViewModelStore()
	private lateinit var root: File
	private lateinit var privateRoot: File
	private lateinit var savedPrefs: Map<String, *>
	private lateinit var savedRepairPrefs: Map<String, *>
	private val ids = HashSet<Long>()

	@Before fun setUp() = runBlocking {
		runCatching { WorkManager.getInstance(context) }.getOrElse {
			WorkManager.initialize(context, Configuration.Builder().build())
		}
		hiltRule.inject()
		db.clearAllTables()
		// The cache directory can use Android's /data/user/0 alias, while ownership persists canonical
		// physical paths. Use one physical root for the fixture and its configured destination.
		root = File(context.cacheDir, "batch11-${System.nanoTime()}").canonicalFile.apply { check(mkdirs()) }
		privateRoot = File(root, "private").apply { check(mkdirs()) }
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		savedPrefs = prefs.all.toMap()
		val repairPrefs = context.getSharedPreferences("legacy_favourite_download_reconcile", Context.MODE_PRIVATE)
		savedRepairPrefs = repairPrefs.all.toMap()
		repairPrefs.edit(commit = true) { clear(); putBoolean("v1_complete", true) }
		prefs.edit(commit = true) {
			putString(AppSettings.KEY_LOCAL_STORAGE, root.path)
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, privateRoot.path)
			remove("normal_download_legacy_roots")
			remove("private_download_legacy_roots")
			putStringSet(AppSettings.KEY_MANGA_LIST_BADGES, setOf("1"))
		}
		contentType.activateSpace(FavouriteSpace.NORMAL)
		contentType.setSelectedType(FavouriteContentType.MANGA, FavouriteSpace.NORMAL)
		display.setListMode(FavouriteContentType.MANGA, ListMode.GRID)
		display.setShowDownloaded(FavouriteContentType.MANGA, true)
		quickFilters.clear(FavouriteContentType.MANGA)
	}

	@After fun tearDown() = runBlocking {
		instrumentation.runOnMainSync { viewModels.clear() }
		for (id in ids) index.delete(id)
		PreferenceManager.getDefaultSharedPreferences(context).restore(savedPrefs)
		context.getSharedPreferences("legacy_favourite_download_reconcile", Context.MODE_PRIVATE).restore(savedRepairPrefs)
		root.deleteRecursively()
		Unit
	}

	/**
	 * Real filesystem + Room ownership regression for the destructive Downloads action.
	 * An identical remote manga in the other favourites space must retain its own file.
	 */
	@Test fun deletingNormalDownloadPreservesPrivateCopy() = runBlocking {
		val manga = remote(810_041L)
		favourite(manga, FavouriteSpace.NORMAL)
		favourite(manga, FavouriteSpace.PRIVATE)
		val normal = download(root, manga)
		val private = download(privateRoot, manga)
		assertNotEquals(normal.file.canonicalPath, private.file.canonicalPath)
		db.getFavouriteDownloadIndexDao().upsert(
			listOf(
				FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, normal.file.canonicalPath),
				FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.PRIVATE.dbValue, private.file.canonicalPath),
			),
		)
		deleteLocal.deleteOwnedDownloadAtRoot(manga, root, FavouriteSpace.NORMAL)
		assertFalse("Selected Normal download should be deleted", normal.file.exists())
		assertTrue("Private copy must survive", private.file.exists())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertEquals(
			private.file.canonicalPath,
			db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.PRIVATE.dbValue, manga.id)?.path,
		)
	}

	@Test fun mismatchedSelectedDestinationCannotDeleteAnOwnedFile() = runBlocking {
		val manga = remote(810_042L)
		data.storeManga(manga, replaceExisting = true)
		val normal = download(root, manga)
		db.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, normal.file.canonicalPath),
		)
		val rejected = runCatching {
			deleteLocal.deleteOwnedDownloadAtRoot(manga, privateRoot, FavouriteSpace.NORMAL)
		}.exceptionOrNull()
		assertTrue("An unrelated task destination must be rejected: $rejected", rejected is java.io.IOException)
		assertTrue("A rejected deletion must not touch the real file", normal.file.exists())
		assertEquals(
			normal.file.canonicalPath,
			db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id)?.path,
		)
	}

	@Test fun sharedPhysicalNormalPrivateOwnershipPreventsDeletion() = runBlocking {
		val manga = remote(810_043L)
		data.storeManga(manga, replaceExisting = true)
		val normal = download(root, manga)
		val path = normal.file.canonicalPath
		db.getFavouriteDownloadIndexDao().upsert(
			listOf(
				FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, path),
				FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.PRIVATE.dbValue, path),
			),
		)
		val rejected = runCatching {
			deleteLocal.deleteOwnedDownloadAtRoot(manga, root, FavouriteSpace.NORMAL)
		}.exceptionOrNull()
		assertTrue("Shared physical ownership must block deletion: $rejected", rejected is java.io.IOException)
		assertTrue("A shared file must remain available", normal.file.exists())
		assertEquals(2, db.getFavouriteDownloadIndexDao().findEntriesByPath(path).size)
	}

	@Test fun completionUpdatesBadgeAndBothNativeFiltersWithoutDetails() = runBlocking {
		val remote = remote(810_011L)
		val notDownloaded = remote(810_016L)
		favourite(remote)
		favourite(notDownloaded)
		assertEquals(setOf(remote.id, notDownloaded.id), repository.getAllManga().map { it.id }.toSet())
		val vm = favouritesViewModel()
		vm.awaitContent("initial non-downloaded favourites") { it.grid(remote.id)?.isSaved == false }
		val indexJob = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { events.collect(index) }
		val ownershipJob = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { events.collect(ownership) }
		try {
			val copy = download(root, remote)
			// Match the already-authoritative DownloadWorker: durable ownership and alias precede completion.
			db.getFavouriteDownloadIndexDao().upsert(listOf(FavouriteDownloadIndexEntity(remote.id, 0, copy.file.canonicalPath)))
			local.rememberDownloadedIdentity(remote, copy)
			assertTrue(liveObservations.scan(remote, FavouriteSpace.NORMAL))
			assertEquals(setOf(remote.id), classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(remote.id)))
			events.emit(copy)
			vm.awaitContent("completed download badge") { it.grid(remote.id)?.isSaved == true }
			vm.setFilterOption(ListFilterOption.Downloaded, true)
			vm.awaitContent("Downloaded filter") { it.grid(remote.id)?.isSaved == true && it.grid(notDownloaded.id) == null }
			vm.setFilterOption(ListFilterOption.NOT_DOWNLOADED, true)
			vm.awaitContent("Not Downloaded filter") { it.grid(remote.id) == null && it.grid(notDownloaded.id)?.isSaved == false }
			vm.setFilterOption(ListFilterOption.NOT_DOWNLOADED, false)
			vm.awaitContent("inactive download filter") { it.grid(remote.id)?.isSaved == true && it.grid(notDownloaded.id)?.isSaved == false }

			// Repeated concrete events need a fresh lookup without publishing a transient false badge.
			var missingBadge = false
			val observer = launch(start = CoroutineStart.UNDISPATCHED) {
				vm.content.collect { if (it.grid(remote.id)?.isSaved == false) missingBadge = true }
			}
			repeat(3) { events.emit(copy); delay(100) }
			observer.cancel()
			assertFalse("An unchanged download must not blink during card metadata refresh", missingBadge)
		} finally {
			indexJob.cancel(); ownershipJob.cancel()
		}
	}

	/**
	 * Reproduces entry into Favourites with durable ownership already committed.
	 * Unlike completionUpdatesBadgeAndBothNativeFiltersWithoutDetails, no completion event
	 * or Details navigation can repair the state after the ViewModel starts.
	 *
	 * A false card emission is a real UI regression even if the asynchronous lookup
	 * eventually corrects it. The test records emissions instead of inspecting source.
	 */
	@Test fun preexistingDownloadNeverPublishesFalseBadgeBeforeEnrichmentCompletes() = runBlocking {
		val remote = remote(810_019L)
		favourite(remote)
		val copy = download(root, remote)
		db.getFavouriteDownloadIndexDao().upsert(
			listOf(FavouriteDownloadIndexEntity(remote.id, FavouriteSpace.NORMAL.dbValue, copy.file.canonicalPath)),
		)
		local.rememberDownloadedIdentity(remote, copy)
		assertTrue(liveObservations.scan(remote, FavouriteSpace.NORMAL))
		assertEquals(setOf(remote.id), classifier.getKnownDownloadedIds(FavouriteSpace.NORMAL, listOf(remote.id)))

		val vm = favouritesViewModel()
		val observed = mutableListOf<DownloadVerification?>()
		val observer = launch(start = CoroutineStart.UNDISPATCHED) {
			vm.content.collect { models ->
				models.grid(remote.id)?.let { observed += it.downloadVerification }
			}
		}
		try {
			vm.awaitContent("preexisting downloaded badge without Details or completion event") {
				it.grid(remote.id)?.downloadVerification == DownloadVerification.VERIFIED_DOWNLOADED
			}
			assertTrue("Expected a verified downloaded card emission: $observed", observed.contains(DownloadVerification.VERIFIED_DOWNLOADED))
			assertFalse(
				"An indexed preexisting download was rendered as false before enrichment: $observed",
				observed.contains(DownloadVerification.VERIFIED_NOT_DOWNLOADED),
			)
		} finally {
			observer.cancel()
		}
	}

	@Test fun ownershipCompletionCanPrecedeLocalIndexWithoutLosingMetadataOrMembership() = runBlocking {
		val remote = remote(810_017L)
		favourite(remote)
		val memberships = repository.getCategoriesIds(remote.id)
		val copy = download(root, remote)
		assertNotEquals(remote.id, copy.manga.id)
		assertFalse(copy.manga.id in db.getMangaDao())
		// Deterministically exercise the order that independent BaseApp collectors can take.
		ownership.emit(copy)
		assertNotNull(db.getMangaDao().find(copy.manga.id))
		assertEquals(copy.file.canonicalPath, db.getFavouriteDownloadIndexDao().findEntry(0, copy.manga.id)?.path)
		index.emit(copy)
		assertEquals(memberships, repository.getCategoriesIds(remote.id))
		assertEquals(remote.title, db.getMangaDao().find(remote.id)?.manga?.title)
		assertNotNull(db.getLocalMangaIndexDao().findPath(copy.manga.id))
	}

	@Test fun genericListRemapsConcreteCompletionAndNullInvalidation() = runBlocking {
		val remote = remote(810_012L)
		data.storeManga(remote, replaceExisting = true)
		val storage = MutableSharedFlow<LocalManga?>()
		lateinit var vm: GenericList
		instrumentation.runOnMainSync {
			vm = GenericList(settings, data, storage, mapper, remote)
			viewModels.put("generic", vm)
		}
		withTimeout(10_000) { vm.content.first { it.grid(remote.id)?.isSaved == false } }
		val copy = download(root, remote)
		local.rememberDownloadedIdentity(remote, copy)
		storage.emit(copy)
		withTimeout(10_000) { vm.content.first { it.grid(remote.id)?.isSaved == true } }
		index.delete(remote.id)
		storage.emit(null)
		withTimeout(10_000) { vm.content.first { it.grid(remote.id)?.isSaved == false } }
		Unit
	}

	@Test fun legacyRepairRequiresChapterEvidenceAndPreservesSpaceOwnership() = runBlocking {
		val normal = remote(810_013L)
		val privateManga = remote(810_014L)
		favourite(normal)
		favourite(privateManga, FavouriteSpace.PRIVATE)
		val normalCopy = download(root, normal)
		val privateCopy = download(privateRoot, privateManga)
		// A persisted path can be lexically different from its canonical reconnect alias.
		val parent = checkNotNull(normalCopy.file.parentFile)
		index.put(normalCopy.copy(file = File(parent, "../${parent.name}/${normalCopy.file.name}")))
		index.put(privateCopy)
		assertEquals(setOf(normal.title, privateManga.title), index.getPersistedSnapshot().map { it.manga.title }.toSet())
		assertEquals(normal.chapters, data.findMangaById(normal.id, withChapters = true)?.chapters)
		assertEquals(privateManga.chapters, data.findMangaById(privateManga.id, withChapters = true)?.chapters)
		val titleOnly = normal.copy(id = 810_015L, chapters = emptyList())
		favourite(titleOnly)
		assertTrue(classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(normal.id)).isEmpty())
		val indexJob = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { events.collect(index) }
		val ownershipJob = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { events.collect(ownership) }
		try {
			reconciler.reconcileOnce()
			assertTrue(liveObservations.scan(normal, FavouriteSpace.NORMAL))
			assertEquals(setOf(normal.id), classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(normal.id, privateManga.id, titleOnly.id)))
			assertEquals(setOf(privateManga.id), classifier.getDownloadedIds(FavouriteSpace.PRIVATE, listOf(normal.id, privateManga.id)))
			assertFalse(titleOnly.id in index)
			assertEquals(normal.source.name, data.findMangaById(normal.id, withChapters = false)?.source?.name)
			assertTrue(normalCopy.file.exists() && privateCopy.file.exists())
		} finally {
			indexJob.cancel(); ownershipJob.cancel()
		}
	}

	@Test fun unchangedIconsNeverHideOrReloadDuringRebind() {
		instrumentation.runOnMainSync {
			val icons = IconsView(context)
			val download = CountingImageView(context)
			val check = CountingImageView(context)
			icons.addView(download); icons.addView(check)
			icons.clearIcons(); icons.addIcon(R.drawable.ic_storage); icons.addIcon(R.drawable.ic_check)
			assertEquals(2, icons.iconsCount)
			val drawable = download.drawable
			download.visibilityWrites = 0; download.resourceWrites = 0
			check.visibilityWrites = 0; check.resourceWrites = 0
			repeat(4) {
				icons.clearIcons()
				assertEquals(View.VISIBLE, download.visibility)
				icons.addIcon(R.drawable.ic_storage); icons.addIcon(R.drawable.ic_check)
				assertEquals(2, icons.iconsCount)
			}
			assertSame(drawable, download.drawable)
			assertEquals(0, download.visibilityWrites + check.visibilityWrites)
			assertEquals(0, download.resourceWrites + check.resourceWrites)
			icons.clearIcons(); icons.addIcon(R.drawable.ic_storage)
			assertEquals(1, icons.iconsCount)
			assertEquals(View.VISIBLE, download.visibility)
			assertEquals(View.GONE, check.visibility)
			assertEquals(0, download.visibilityWrites)
			icons.clearIcons(); assertEquals(0, icons.iconsCount)
			assertEquals(View.GONE, download.visibility)
			val custom = ColorDrawable(0xff123456.toInt())
			icons.clearIcons(); icons.addIcon(custom); assertEquals(1, icons.iconsCount)
			icons.clearIcons(); icons.addIcon(R.drawable.ic_storage); assertEquals(1, icons.iconsCount)
			assertNotSame(custom, download.drawable)
		}
	}

	@Test fun downloadedShelfUsesOwnedTitlesBeforePagingAndSelection() = runBlocking {
		val neverFavourited = remote(810_024L)
		val keptFile = remote(810_025L)
		val hidden = remote(810_026L)
		val active = remote(810_027L)
		val privateTitle = remote(810_028L)
		favourite(keptFile)
		favourite(active)
		favourite(privateTitle, FavouriteSpace.PRIVATE)
		favourite(remote(810_029L))
		// Match the hydrated chapter snapshot used by the observation visibility guard.
		data.storeManga(hidden, replaceExisting = true)
		val hiddenCategory = repository.createCategory("Hidden", ListSortOrder.NEWEST, false, false, false)
		repository.updateCategoryMemberships(listOf(hidden), mapOf(hiddenCategory.id to true), FavouriteSpace.NORMAL)
		data.storeManga(neverFavourited, replaceExisting = true)

		val owned = listOf(neverFavourited, keptFile, hidden, active)
		val copies = owned.associate { it.id to download(root, it) }
		db.getFavouriteDownloadIndexDao().upsert(copies.map { (id, copy) ->
			FavouriteDownloadIndexEntity(id, FavouriteSpace.NORMAL.dbValue, copy.file.canonicalPath)
		} + FavouriteDownloadIndexEntity(
			privateTitle.id, FavouriteSpace.PRIVATE.dbValue, download(privateRoot, privateTitle).file.canonicalPath,
		))
		repository.removeFromFavourites(setOf(keptFile.id))
		assertTrue(copies.getValue(keptFile.id).file.exists())
		assertTrue(repository.getCategoriesIds(keptFile.id).isEmpty())

		assertEquals(hidden.chapters, data.findMangaById(hidden.id, withChapters = true)?.chapters)
		assertTrue(liveObservations.scan(active, FavouriteSpace.NORMAL))
		assertTrue(liveObservations.scan(hidden, FavouriteSpace.NORMAL))
		val expected = owned.map { it.id }.toSet()
		assertEquals(expected, repository.getDownloadedEntries().map { it.mangaId }.toSet())
		assertEquals(
			listOf(neverFavourited.id, keptFile.id),
			repository.observeDownloaded(ListSortOrder.ALPHABETIC, emptySet(), limit = 2).first().map { it.id },
		)
		assertEquals(listOf(privateTitle.id), repository.getDownloadedEntries(FavouriteSpace.PRIVATE).map { it.mangaId })
		val vm = favouritesViewModel(DOWNLOADED_FAVOURITES_CATEGORY_ID)
		vm.awaitContent("all indexed Normal downloads") { cards ->
			cards.filterIsInstance<MangaGridModel>().map { it.id }.toSet() == expected
		}
		assertEquals(expected, vm.getAllSelectableIds())
		try {
			FavouritesContainerFragment.searchQuery.value = keptFile.title
			vm.awaitContent("Downloaded search") { cards ->
				cards.filterIsInstance<MangaGridModel>().map { it.id } == listOf(keptFile.id)
			}
			assertEquals(setOf(keptFile.id), vm.getAllSelectableIds())
		} finally {
			FavouritesContainerFragment.searchQuery.value = ""
		}
	}

	@Test fun downloadedDateAddedSortIgnoresMetadataRefresh() = runBlocking {
		val older = remote(810_035L).copy(title = "Older favourite")
		val newer = remote(810_036L).copy(title = "Newer favourite")
		val withoutMembership = remote(810_037L).copy(title = "Alpha download")
		val removed = remote(810_038L).copy(title = "Beta download")
		val category = repository.createCategory("First", ListSortOrder.NEWEST, false, false, true)
		val anotherCategory = repository.createCategory("Second", ListSortOrder.NEWEST, false, false, true)
		val titles = listOf(older, newer, withoutMembership, removed)
		for (title in titles) data.storeManga(title, replaceExisting = true)
		db.getFavouritesDao().apply {
			upsert(FavouriteEntity(older.id, category.id, 0, false, 100L, 0L))
			upsert(FavouriteEntity(older.id, anotherCategory.id, 0, false, 300L, 0L))
			upsert(FavouriteEntity(newer.id, category.id, 0, false, 200L, 0L))
			upsert(FavouriteEntity(removed.id, category.id, 0, false, 400L, 500L))
		}
		db.getFavouriteDownloadIndexDao().upsert(titles.map { title ->
			FavouriteDownloadIndexEntity(title.id, FavouriteSpace.NORMAL.dbValue, download(root, title).file.canonicalPath)
		})

		suspend fun ordered(order: ListSortOrder, limit: Int = titles.size) =
			repository.observeDownloaded(order, emptySet(), limit).first().map { it.id }

		assertTrue(liveObservations.scan(older, FavouriteSpace.NORMAL))
		assertTrue(liveObservations.scan(newer, FavouriteSpace.NORMAL))
		val newestFirst = listOf(newer.id, older.id, withoutMembership.id, removed.id)
		assertEquals(newestFirst, ordered(ListSortOrder.NEWEST))
		assertEquals(listOf(older.id, newer.id, withoutMembership.id, removed.id), ordered(ListSortOrder.OLDEST))

		data.storeManga(older, replaceExisting = true, detailsFetched = true)
		assertTrue(data.getDetailsUpdatedAt(older.id) > data.getDetailsUpdatedAt(newer.id))
		assertFalse(older.id in classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(older.id)))
		assertTrue(liveObservations.scan(older, FavouriteSpace.NORMAL))
		assertEquals(newestFirst, ordered(ListSortOrder.NEWEST))
		assertEquals(newestFirst.take(2), ordered(ListSortOrder.NEWEST, limit = 2))
	}

	@Test fun sharedDestinationShelfRequiresOwnershipAndHonorsVaultMembership() = runBlocking {
		PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
			remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT)
		}
		val normalTitle = remote(810_031L)
		val privateTitle = remote(810_032L)
		val unownedTitle = remote(810_034L)
		favourite(privateTitle, FavouriteSpace.PRIVATE)
		data.storeManga(normalTitle, replaceExisting = true)
		data.storeManga(unownedTitle, replaceExisting = true)
		val normalCopy = download(root, normalTitle)
		val privateCopy = download(root, privateTitle)
		local.rememberDownloadedIdentity(unownedTitle, download(root, unownedTitle))
		db.getFavouriteDownloadIndexDao().upsert(listOf(
			FavouriteDownloadIndexEntity(normalTitle.id, FavouriteSpace.NORMAL.dbValue, normalCopy.file.canonicalPath),
			FavouriteDownloadIndexEntity(privateTitle.id, FavouriteSpace.PRIVATE.dbValue, privateCopy.file.canonicalPath),
			FavouriteDownloadIndexEntity(privateTitle.id, FavouriteSpace.NORMAL.dbValue, privateCopy.file.canonicalPath),
		))

		assertEquals(listOf(normalTitle.id), repository.getDownloadedEntries().map { it.mangaId })
		assertEquals(listOf(privateTitle.id), repository.getDownloadedEntries(FavouriteSpace.PRIVATE).map { it.mangaId })
	}

	@Test fun selectAllInSimilarTitleScanMatchesTheCurrentSearch() = runBlocking {
		val first = remote(810_021L).copy(title = "Same Story", description = "Translated edition")
		val second = remote(810_022L).copy(
			title = first.title, source = MissingMangaSource("BATCH11_SCAN_SECOND"), description = "Original edition",
		)
		favourite(first)
		favourite(second)
		val vm = favouritesViewModel()
		try {
			assertEquals(2, vm.enterSimilarTitleScanMode())
			FavouritesContainerFragment.searchQuery.value = "Translated edition"
			vm.awaitContent("filtered scan") { it.grid(first.id) != null && it.grid(second.id) == null }
			assertEquals(setOf(first.id), vm.getAllSelectableIds())
			FavouritesContainerFragment.searchQuery.value = "missing"
			assertEquals(emptySet<Long>(), vm.getAllSelectableIds())
		} finally {
			FavouritesContainerFragment.searchQuery.value = ""
		}
	}

	@Test fun bulkActionsResolveEverySelectedTitleAcrossPages() = runBlocking {
		val titles = (810_100L until 810_170L).map(::remote)
		val category = repository.createCategory("Paged selection", ListSortOrder.NEWEST, false, false, true)
		repository.updateCategoryMemberships(titles, mapOf(category.id to true), FavouriteSpace.NORMAL)
		val vm = favouritesViewModel()
		vm.awaitContent("first page") { it.filterIsInstance<MangaGridModel>().size == 64 }
		val loaded = vm.content.value.filterIsInstance<MangaGridModel>().map { it.manga }.toSet()
		val selectedIds = checkNotNull(vm.getAllSelectableIds())
		assertEquals(titles.map { it.id }.toSet(), selectedIds)
		assertEquals(selectedIds, vm.resolveSelectedManga(selectedIds, loaded)?.map { it.id }?.toSet())
		assertNull(vm.resolveSelectedManga(selectedIds + Long.MAX_VALUE, loaded))
	}

	/** Phase 0 lower-layer T02/T15 probe: actual manual bytes, not a worker completion receipt. */
	@Test fun corruptManualStandaloneRemainsMappedAndCanBeDeletedAtExactLocalTarget() = runBlocking {
		val seed = remote(810_061L)
		val first = seed.chapters.orEmpty().single()
		val second = first.copy(id = first.id + 1L, title = "Chapter 2", number = 2f, url = "/chapter/2")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, seed).file
		val corrupt = File(container, "Chapter 1.cbz")
		assertTrue("The fixture must use the real standalone chapter layout", corrupt.isFile)
		val sibling = File(container, "Chapter 2.cbz")
		corrupt.copyTo(sibling)
		val siblingBytes = sibling.readBytes()
		val corruptBytes = byteArrayOf(1, 2, 3, 4)
		corrupt.writeBytes(corruptBytes)
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))

		val resolved = requireNotNull(local.findSavedMangaAtPath(manga, container, rememberIdentity = false))
		val chapters = resolved.manga.chapters.orEmpty()
		assertEquals(setOf(first.id, second.id), chapters.map { it.id }.toSet())
		val selected = chapters.single { it.id == first.id }
		assertTrue("The local archive read must not produce pages from corrupt bytes",
			runCatching { local.getPages(selected) }.getOrNull().isNullOrEmpty())
		assertArrayEquals(corruptBytes, corrupt.readBytes())
		assertArrayEquals(siblingBytes, sibling.readBytes())
		assertNull("Read-only lookup must not mint ownership",
			db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))

		// This is the lower-level operation on an explicit physical snapshot, not a proof
		// of Q24 UI/action-time authorization. The scoped action gate must be tested separately.
		local.deleteChapters(resolved.manga, setOf(first.id))
		assertFalse(corrupt.exists())
		assertArrayEquals(siblingBytes, sibling.readBytes())
	}

	/** Phase 0 T06 probe: active status must diverge from historical maintenance evidence. */
	@Test fun normalFavouriteHistoricalCopyDoesNotQualifyForActiveDownloadedShelf() = runBlocking {
		val manga = remote(810_062L)
		val nonFavourite = remote(810_063L)
		favourite(manga)
		data.storeManga(nonFavourite, replaceExisting = true)
		val oldCopy = download(root, manga)
		val controlCopy = download(root, nonFavourite)
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, oldCopy.file.canonicalPath)
		db.getFavouriteDownloadIndexDao().upsert(listOf(owner,
			FavouriteDownloadIndexEntity(nonFavourite.id, FavouriteSpace.NORMAL.dbValue, controlCopy.file.canonicalPath)))
		suspend fun shelfIds() = repository.observeDownloaded(
			ListSortOrder.ALPHABETIC, emptySet(), limit = 100,
		).first().map { it.id }.toSet()
		// A durable owner is not a current scanner observation.
		assertEquals(setOf(nonFavourite.id), shelfIds())
		assertTrue(liveObservations.scan(manga, FavouriteSpace.NORMAL))
		assertEquals(setOf(manga.id, nonFavourite.id), shelfIds())
		assertEquals(setOf(manga.id, nonFavourite.id), classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(manga.id, nonFavourite.id)))

		val oldFile = oldCopy.file.listFiles().orEmpty().single { it.extension.equals("cbz", true) }
		val bytes = oldFile.readBytes()
		val nextRoot = File(root, "active-normal-b")
		destinations.setRoot(FavouriteSpace.NORMAL, nextRoot)
		assertEquals(nextRoot.canonicalFile, destinations.effectiveRoot(FavouriteSpace.NORMAL)?.canonicalFile)
		assertEquals(owner, db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertTrue("Historical maintenance must retain the known download",
			manga.id in classifier.getKnownDownloadedIds(FavouriteSpace.NORMAL, listOf(manga.id)))
		assertArrayEquals(bytes, oldFile.readBytes())
		assertEquals("Only the eligible Normal favourite loses historical status; non-favourite is unchanged",
			setOf(nonFavourite.id), shelfIds())
		assertEquals(setOf(nonFavourite.id), classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(manga.id, nonFavourite.id)))
		// The historical owner must not be excluded by SQL before the active-root classifier runs.
		val vm = favouritesViewModel()
		vm.setFilterOption(ListFilterOption.NOT_DOWNLOADED, true)
		vm.awaitContent("historical owner remains in Not Downloaded") { it.grid(manga.id)?.isSaved == false }
		assertEquals(setOf(manga.id), vm.getAllSelectableIds())
		destinations.setRoot(FavouriteSpace.NORMAL, root)
		assertEquals(setOf(nonFavourite.id), shelfIds())
		assertTrue(liveObservations.scan(manga, FavouriteSpace.NORMAL))
		assertEquals(setOf(manga.id, nonFavourite.id), shelfIds())
	}

	/** Consumer parity and durable-mutation rejection with the production singleton store. */
	@Test
	fun normalConsumerShelfAndBadgeRejectInvalidatedObservation() = runBlocking {
		val (manga, file) = observedDownload(810_184L)
		val space = FavouriteSpace.NORMAL
		val requested = listOf(manga.id)
		assertTrue(liveObservations.scan(manga, space))
		assertEquals(setOf(manga.id), classifier.getDownloadState(space, requested).downloaded)
		assertEquals(setOf(manga.id), repository.getDownloadedEntries().map { it.mangaId }.toSet())
		val vm = favouritesViewModel(DOWNLOADED_FAVOURITES_CATEGORY_ID)
		vm.awaitContent("accepted shelf observation") { it.grid(manga.id)?.isSaved == true }
		assertEquals(setOf(manga.id), vm.getAllSelectableIds())
		val token = liveObservations.beginMutation(manga.id)
		try {
			assertTrue(file.exists())
			assertTrue(classifier.getDownloadState(space, requested).downloaded.isEmpty())
			assertTrue(classifier.getDownloadState(space, requested).resolved.isEmpty())
			assertTrue(repository.getDownloadedEntries().isEmpty())
			vm.awaitContent("invalidated shelf observation") { it.grid(manga.id) == null }
			assertEquals(emptySet<Long>(), vm.getAllSelectableIds())
		} finally {
			liveObservations.finishMutation(manga.id, token)
		}
		assertTrue(repository.getDownloadedEntries().isEmpty())
		assertTrue(liveObservations.scan(manga, space))
		vm.awaitContent("revalidated shelf observation") { it.grid(manga.id)?.isSaved == true }
		assertEquals(setOf(manga.id), vm.getAllSelectableIds())
	}

	/** Phase 0 T05 probe: exercise the scoped resolver with a real stale directory sidecar. */
	@Test fun indexedNormalFavouriteRetainsRenamedArtifactAsIndependentLocalChapter() = runBlocking {
		val seed = remote(810_064L)
		val first = seed.chapters.orEmpty().single()
		val second = first.copy(id = first.id + 1L, title = "Chapter 2", number = 2f, url = "/chapter/2")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, seed).file
		val original = File(container, "Chapter 1.cbz")
		assertTrue(original.isFile)
		val sibling = File(container, "Chapter 2.cbz")
		original.copyTo(sibling)
		val bytes = original.readBytes()
		val indexFile = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		indexFile.writeText(MangaIndex(null).apply {
			setMangaInfo(manga)
			manga.chapters.orEmpty().withIndex().forEach { addChapter(it, "${it.value.title}.cbz") }
		}.toString())
		val indexBytes = indexFile.readBytes()
		db.getFavouriteDownloadIndexDao().upsert(
			FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.canonicalPath),
		)
		val before = requireNotNull(resolver.findSavedManga(manga, FavouriteSpace.NORMAL))
		assertEquals(setOf(first.id, second.id), before.manga.chapters.orEmpty().map { it.id }.toSet())

		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(original.renameTo(special))
		val after = requireNotNull(resolver.findSavedManga(manga, FavouriteSpace.NORMAL))
		val chapters = after.manga.chapters.orEmpty()
		assertTrue("The untouched canonical chapter must survive", chapters.any { it.id == second.id })
		assertFalse("The missing canonical filename must not remain mapped", chapters.any { it.id == first.id })
		assertArrayEquals(indexBytes, indexFile.readBytes())
		assertArrayEquals(bytes, special.readBytes())
		val orphan = chapters.singleOrNull { it.title == "Chapter 1 Special" }
		assertNotNull("The unmatched physical chapter must remain visible despite the stale sidecar", orphan)
		val orphanId = requireNotNull(orphan).id
		assertNotEquals(first.id, orphanId)
		assertNotEquals(second.id, orphanId)
	}


	/** T06/T10/T11 foundation: membership, explicit provenance and protected source boundaries. */
	@Test fun normalScannerScopeRequiresActualNormalMembershipAndVerifiedContext() = runBlocking {
		val normal = remote(810_065L)
		val dual = remote(810_066L)
		val privateOnly = remote(810_067L)
		val nonFavourite = remote(810_068L)
		favourite(normal)
		favourite(dual)
		favourite(dual, FavouriteSpace.PRIVATE)
		favourite(privateOnly, FavouriteSpace.PRIVATE)
		data.storeManga(nonFavourite, replaceExisting = true)

		assertTrue(normalDownloadScope.appliesTo(normal, FavouriteSpace.NORMAL))
		assertTrue(normalDownloadScope.appliesTo(normal, null))
		assertFalse(normalDownloadScope.appliesTo(normal, FavouriteSpace.PRIVATE))
		assertTrue(normalDownloadScope.appliesTo(dual, FavouriteSpace.NORMAL))
		assertFalse(normalDownloadScope.appliesTo(dual, null))
		assertFalse(normalDownloadScope.appliesTo(dual, FavouriteSpace.PRIVATE))
		assertFalse(normalDownloadScope.appliesTo(privateOnly, FavouriteSpace.NORMAL))
		assertFalse(normalDownloadScope.appliesTo(privateOnly, null))
		assertFalse(normalDownloadScope.appliesTo(nonFavourite, FavouriteSpace.NORMAL))

		// Exercise the metadata guard even when the ID has actual Normal membership.
		for (source in listOf(
			LocalMangaSource,
			MissingMangaSource("LN_SCANNER_FIXTURE"),
			MissingMangaSource("TSUKI:MIYORARE:miyorare-global:EXHENTAI"),
			MissingMangaSource("TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"),
			MissingMangaSource("TSUKI:OTHER:fixture:EXHENTAI"),
			MissingMangaSource("MIHON_57122881048805941"),
		)) {
			assertFalse(source.name, normalDownloadScope.appliesTo(normal.copy(source = source), FavouriteSpace.NORMAL))
		}

		val memberships = repository.getCategoriesIds(dual.id, FavouriteSpace.NORMAL)
		repository.updateCategoryMemberships(listOf(dual), memberships.associateWith { false }, FavouriteSpace.NORMAL)
		assertFalse(normalDownloadScope.appliesTo(dual, FavouriteSpace.NORMAL))
		assertFalse(normalDownloadScope.appliesTo(dual, null))
		assertTrue(repository.isFavorite(dual.id, FavouriteSpace.PRIVATE))
	}

	/** T06/T07/T10 foundation: current root containment is not legacy or ownership lookup. */
	@Test fun normalScannerScopeRejectsHistoricalUnavailableAndNestedPrivatePaths() = runBlocking {
		val manga = remote(810_069L)
		favourite(manga)
		val activeA = File(root, "scope-a").apply { check(mkdirs()) }
		val activeB = File(root, "scope-b").apply { check(mkdirs()) }
		val nestedPrivate = File(activeA, "private").apply { check(mkdirs()) }
		val artifactA = File(activeA, "Chapter 1.cbz").apply { writeBytes(byteArrayOf(1)) }
		val artifactB = File(activeB, "Chapter 1.cbz").apply { writeBytes(byteArrayOf(2)) }
		val privateArtifact = File(nestedPrivate, "Chapter 1.cbz").apply { writeBytes(byteArrayOf(3)) }
		val missingRoot = File(root, "unavailable")
		val siblingPrefix = File(root, "scope-a-other").apply { check(mkdirs()) }
		val foreignArtifact = File(siblingPrefix, "Chapter 1.cbz").apply { writeBytes(byteArrayOf(4)) }

		assertTrue(normalDownloadScope.isWithinActiveRoot(artifactA, activeA, nestedPrivate))
		assertFalse(normalDownloadScope.isWithinActiveRoot(privateArtifact, activeA, nestedPrivate))
		assertFalse(normalDownloadScope.isWithinActiveRoot(nestedPrivate, activeA, nestedPrivate))
		assertFalse(normalDownloadScope.isWithinActiveRoot(foreignArtifact, activeA, nestedPrivate))
		// Shared and reverse-nested roots pass containment, not ownership/identity.
		assertTrue(normalDownloadScope.isWithinActiveRoot(artifactA, activeA, activeA))
		assertTrue(normalDownloadScope.isWithinActiveRoot(artifactA, activeA, root))
		assertFalse(normalDownloadScope.isWithinActiveRoot(artifactA, activeB, nestedPrivate))
		assertTrue(normalDownloadScope.isWithinActiveRoot(artifactB, activeB, nestedPrivate))
		assertFalse(normalDownloadScope.isWithinActiveRoot(artifactA, missingRoot, nestedPrivate))
		assertFalse(normalDownloadScope.isWithinActiveRoot(artifactA, null, nestedPrivate))
		assertFalse(missingRoot.exists())
		assertTrue(normalDownloadScope.appliesTo(manga, FavouriteSpace.NORMAL))
		assertArrayEquals(byteArrayOf(1), artifactA.readBytes())
		assertArrayEquals(byteArrayOf(2), artifactB.readBytes())
		assertArrayEquals(byteArrayOf(3), privateArtifact.readBytes())
	}


	/** T06/T07/T10/T11: active root selection must not inherit the legacy lookup purpose. */
	@Test fun normalScannerActiveLookupUsesSelectedRootWithoutChangingKnownCopies() = runBlocking {
		val manga = remote(810_070L)
		val nonFavourite = remote(810_071L)
		val privateOnly = remote(810_072L)
		favourite(manga)
		favourite(privateOnly, FavouriteSpace.PRIVATE)
		data.storeManga(nonFavourite, replaceExisting = true)
		val oldCopy = download(root, manga)
		val controlCopy = download(root, nonFavourite)
		val dao = db.getFavouriteDownloadIndexDao()
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, oldCopy.file.canonicalPath)
		dao.upsert(listOf(owner,
			FavouriteDownloadIndexEntity(nonFavourite.id, FavouriteSpace.NORMAL.dbValue, controlCopy.file.canonicalPath)))
		val oldFile = oldCopy.file.listFiles().orEmpty().single { it.extension.equals("cbz", true) }
		val bytes = oldFile.readBytes()
		val before = requireNotNull(normalDownloadScope.resolveActiveLookup(manga, FavouriteSpace.NORMAL))
		assertEquals(root.canonicalFile, before.normalRoot?.canonicalFile)
		assertEquals(privateRoot.canonicalFile, before.privateRoot?.canonicalFile)
		assertTrue(normalDownloadScope.isWithinActiveRoot(oldCopy.file, before.normalRoot, before.privateRoot))

		val activeB = File(root, "scanner-active-b")
		destinations.setRoot(FavouriteSpace.NORMAL, activeB)
		val after = requireNotNull(normalDownloadScope.resolveActiveLookup(manga, null))
		assertEquals(activeB.canonicalFile, after.normalRoot?.canonicalFile)
		assertFalse(normalDownloadScope.isWithinActiveRoot(oldCopy.file, after.normalRoot, after.privateRoot))
		assertTrue(root.canonicalFile in destinations.readableRoots(FavouriteSpace.NORMAL).map { it.canonicalFile })
		assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertEquals(setOf(manga.id, nonFavourite.id),
			classifier.getKnownDownloadedIds(FavouriteSpace.NORMAL, listOf(manga.id, nonFavourite.id)))
		// The existing legacy resolver remains usable; active discovery does not call it.
		assertEquals(oldCopy.file.canonicalFile,
			resolver.findSavedManga(manga, FavouriteSpace.NORMAL)?.file?.canonicalFile)
		assertNull(normalDownloadScope.resolveActiveLookup(nonFavourite, FavouriteSpace.NORMAL))
		assertNull(normalDownloadScope.resolveActiveLookup(privateOnly, null))
		assertNull(normalDownloadScope.resolveActiveLookup(manga, FavouriteSpace.PRIVATE))

		// Simulate a configured but unavailable destination without calling the mkdir-on-set API.
		val missing = File(root, "scanner-unavailable")
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		prefs.edit(commit = true) {
			putString(AppSettings.KEY_LOCAL_STORAGE, missing.path)
			remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT)
		}
		val unavailable = requireNotNull(normalDownloadScope.resolveActiveLookup(manga, FavouriteSpace.NORMAL))
		assertEquals(missing.canonicalFile, unavailable.normalRoot?.canonicalFile)
		assertEquals(missing.canonicalFile, unavailable.privateRoot?.canonicalFile)
		assertFalse(normalDownloadScope.isWithinActiveRoot(oldCopy.file, unavailable.normalRoot, unavailable.privateRoot))
		assertFalse(missing.exists())
		assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertArrayEquals(bytes, oldFile.readBytes())
	}

	/** Scanner default selection must not provision the selected manga directory. */
	@Test fun normalScannerUnconfiguredRootSelectionDoesNotCreateMangaDirectory() = runBlocking {
		val manga = remote(810_073L)
		favourite(manga)
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		prefs.edit(commit = true) {
			remove(AppSettings.KEY_LOCAL_STORAGE)
			remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT)
		}
		val external = context.getExternalFilesDir(null)?.let { File(it, "manga") }
		val internal = File(context.filesDir, "manga")
		val externalExisted = external?.exists()
		val internalExisted = internal.exists()
		val expected = if (external == null || (external.exists() && !external.isDirectory)) internal else external
		val selected = requireNotNull(normalDownloadScope.resolveActiveLookup(manga, FavouriteSpace.NORMAL))
		assertEquals(expected.absoluteFile, selected.normalRoot?.absoluteFile)
		assertEquals(selected.normalRoot, selected.privateRoot)
		assertEquals(externalExisted, external?.exists())
		assertEquals(internalExisted, internal.exists())
	}


	/** Phase 2 T05/T03: indexed extras, exact locator, ordinal independence and staging exclusion. */
	@Test fun normalScannerIndexedReconciliationRetainsIndependentArtifactAcrossListingChanges() = runBlocking {
		val seed = remote(810_074L)
		val first = seed.chapters.orEmpty().single().copy(number = 0f)
		val second = first.copy(id = first.id + 1L, title = "Chapter 2", url = "/chapter/2")
		val retired = first.copy(id = first.id + 2L, title = "Retired chapter", url = "/retired")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, seed).file.canonicalFile
		val original = File(container, "Chapter 1.cbz")
		val sibling = File(container, "Chapter 2.cbz")
		original.copyTo(sibling)
		val retiredFile = File(container, "Retired chapter.cbz")
		original.copyTo(retiredFile)
		val bytes = original.readBytes()
		val indexFile = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		indexFile.writeText(MangaIndex(null).apply {
			setMangaInfo(manga)
			(manga.chapters.orEmpty() + retired).withIndex().forEach { addChapter(it, "${it.value.title}.cbz") }
		}.toString())
		val indexBytes = indexFile.readBytes()
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.canonicalPath)
		val dao = db.getFavouriteDownloadIndexDao()
		dao.upsert(listOf(owner))
		val scope = requireNotNull(normalDownloadScope.resolveActiveLookup(manga, FavouriteSpace.NORMAL))
		assertTrue(normalDownloadScope.isWithinActiveRoot(container, scope.normalRoot, scope.privateRoot))

		suspend fun chapters() = requireNotNull(
			NormalDownloadDirectoryReconciler.reconcileIndexed(manga, container),
		).manga.chapters.orEmpty()
		val initial = chapters()
		assertEquals(setOf(first.id, second.id, retired.id), initial.map { it.id }.toSet())
		val retained = initial.single { it.id == retired.id }
		assertEquals(retiredFile.canonicalPath, android.net.Uri.parse(retained.url).path)
		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(original.renameTo(special))
		val staging = File(container, "Chapter 1.cbz.tmp").apply { writeBytes(bytes) }
		val backup = File(container, "Chapter 1.cbz.bak.tmp").apply { writeBytes(bytes) }
		val renamed = chapters()
		assertEquals(3, renamed.size)
		assertEquals(retained, renamed.single { it.id == retired.id })
		assertTrue(renamed.any { it.id == second.id })
		assertFalse(renamed.any { it.id == first.id })
		val orphan = renamed.single { it.title == "Chapter 1 Special" }
		assertNotEquals(first.id, orphan.id)
		assertNotEquals(second.id, orphan.id)
		assertEquals(0f, orphan.number, 0f)
		val uri = android.net.Uri.parse(orphan.url)
		assertEquals(container.canonicalPath, uri.path)
		assertEquals(special.name, uri.fragment)
		assertTrue(local.getPages(orphan).isNotEmpty())

		// A file sorting before Special cannot shift its ID or personal locator.
		val unrelated = File(container, "Chapter 0.cbz").apply { writeBytes(bytes) }
		assertEquals(orphan, chapters().single { it.title == orphan.title })
		assertTrue(unrelated.delete())
		assertEquals(orphan, chapters().single { it.title == orphan.title })
		val corrupt = byteArrayOf(1, 2, 3, 4)
		special.writeBytes(corrupt)
		assertEquals(orphan, chapters().single { it.title == orphan.title })
		original.writeBytes(bytes)
		val restored = chapters()
		assertEquals(setOf(first.id, second.id, retired.id, orphan.id), restored.map { it.id }.toSet())
		assertEquals(retained, restored.single { it.id == retired.id })
		assertEquals(orphan, restored.single { it.id == orphan.id })
		assertArrayEquals(indexBytes, indexFile.readBytes())
		assertArrayEquals(bytes, original.readBytes())
		assertArrayEquals(bytes, sibling.readBytes())
		assertArrayEquals(bytes, retiredFile.readBytes())
		assertArrayEquals(bytes, staging.readBytes())
		assertArrayEquals(bytes, backup.readBytes())
		assertArrayEquals(corrupt, special.readBytes())
		assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
	}

	/** Contradictory sidecar identity and duplicate physical mapping are not confirmed absence. */
	@Test fun normalScannerIndexedReconciliationRejectsContradictoryMetadata() = runBlocking {
		val seed = remote(810_075L)
		val first = seed.chapters.orEmpty().single()
		val second = first.copy(id = first.id + 1L, title = "Chapter 2", url = "/chapter/2")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, seed).file.canonicalFile
		val file = File(container, "Chapter 1.cbz")
		val bytes = file.readBytes()
		val indexFile = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		fun writeIndex(metadata: Manga, duplicate: Boolean = false) {
			indexFile.writeText(MangaIndex(null).apply {
				setMangaInfo(metadata)
				metadata.chapters.orEmpty().withIndex().forEach {
					addChapter(it, if (duplicate) file.name else "${it.value.title}.cbz")
				}
			}.toString())
		}
		writeIndex(manga.copy(source = MissingMangaSource("OTHER_SCANNER_FIXTURE")))
		assertNull(NormalDownloadDirectoryReconciler.reconcileIndexed(manga, container))
		writeIndex(manga, duplicate = true)
		assertNull(NormalDownloadDirectoryReconciler.reconcileIndexed(manga, container))
		writeIndex(manga.copy(chapters = listOf(first.copy(url = "/stale"), second)))
		val stale = indexFile.readBytes()
		assertNull(NormalDownloadDirectoryReconciler.reconcileIndexed(manga, container))
		assertArrayEquals(stale, indexFile.readBytes())
		assertArrayEquals(bytes, file.readBytes())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
	}


	/** T01/T03/T05: sidecar-free finished artifacts and preserved pre-scanner identity/locator. */
	@Test fun normalScannerSidecarFreeMatchingPreservesVerifiedLegacyPersonalKey() = runBlocking {
		val seed = remote(810_076L)
		val first = seed.chapters.orEmpty().single().copy(number = 0f)
		val second = first.copy(id = first.id + 1L, title = "Chapter 2", url = "/chapter/2")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, seed).file.canonicalFile
		val original = File(container, "Chapter 1.cbz")
		val special = File(container, "Chapter 1 Special.cbz")
		val bytes = original.readBytes()
		assertTrue(original.renameTo(special))
		val hash = java.security.MessageDigest.getInstance("MD5").digest(second.url.toByteArray(Charsets.UTF_8))
			.take(3).joinToString("") { "%02x".format(it.toInt() and 0xff) }
		val hashed = File(container, "Chapter 2 ($hash).cbz").apply { writeBytes(bytes) }
		val mismatchedHash = if (hash == "000000") "ffffff" else "000000"
		val wrongHash = File(container, "Chapter 2 ($mismatchedHash).cbz").apply { writeBytes(bytes) }
		val stage = File(container, "Chapter 1.cbz.tmp").apply { writeBytes(bytes) }
		assertFalse(File(container, LocalMangaOutput.ENTRY_NAME_INDEX).exists())

		// The pre-scanner parser establishes the existing exact ID/URL pair before listing changes.
		val legacyManga = LocalMangaParser(container).getManga(withDetails = true).manga
		val legacy = legacyManga.chapters.orEmpty().single { it.title == "Chapter 1 Special" }
		val key = ChapterPersonalKey.of(legacy)
		val personal = ChapterPersonalEntity(manga.id, key.source, key.url, 4, "Existing local note")
		val progress = ChapterProgressEntity(manga.id, key.source, key.url, FavouriteSpace.NORMAL.dbValue, 1, 0, 2)
		val personalDao = db.getChapterPersonalDao()
		val progressDao = db.getChapterProgressDao()
		personalDao.upsert(personal)
		progressDao.upsert(progress)
		val canonicalKey = ChapterPersonalKey.of(first)
		personalDao.upsert(ChapterPersonalEntity(manga.id, canonicalKey.source, canonicalKey.url, 5, "Original note"))
		val personalBefore = personalDao.findAll(listOf(manga.id)).toSet()
		val progressBefore = progressDao.findAll(listOf(manga.id), FavouriteSpace.NORMAL.dbValue).toSet()
		val page = local.getPages(legacy).first()
		val historyDao = db.getHistoryDao()
		val bookmarkDao = db.getBookmarksDao()
		val history = HistoryEntity(manga.id, 100L, 200L, legacy.id, 0, 0f, 0.5f, 0L, 3)
		val bookmark = BookmarkEntity(manga.id, page.id, legacy.id, 0, 0, page.url, 100L, 0.5f)
		historyDao.insert(history)
		bookmarkDao.insert(bookmark)
		settings.setChapterReadOverrides(manga.id, mapOf(first.id to false, legacy.id to true))
		val historyBefore = historyDao.findIncludingDeleted(manga.id)
		val bookmarksBefore = bookmarkDao.findAll(manga.id).toSet()
		val overridesBefore = settings.getChapterReadOverrides(manga.id)
		assertEquals(history, historyBefore)
		assertEquals(setOf(bookmark), bookmarksBefore)
		assertEquals(mapOf(first.id to false, legacy.id to true), overridesBefore)

		// Simulate a real persisted migration redirect that used an earlier ordinal ID.
		// Its container/path is valid but its chapter redirect contradicts the physical name.
		val oldRemoteId = manga.id + 1000L
		ids += oldRemoteId
		index.registerDownloadAlias(oldRemoteId, legacyManga.id, container)
		requireNotNull(index.prepareDownloadIdentityMove(oldRemoteId, manga.id, mapOf(legacy.id to first.id)))
		val aliasPrefs = context.getSharedPreferences("_local_index", Context.MODE_PRIVATE)
		val aliasKey = "download_alias_${manga.id}"
		val aliasBefore = requireNotNull(aliasPrefs.getString(aliasKey, null))
		assertTrue(aliasBefore.startsWith("m1:"))
		val hints = requireNotNull(normalDirectoryDiscovery.discover(manga, FavouriteSpace.NORMAL))
		assertTrue(container in hints.persistedHints)
		suspend fun scan() = requireNotNull(
			NormalDownloadDirectoryReconciler.reconcileSidecarFree(manga, container, listOf(legacy)),
		).manga.chapters.orEmpty()
		val initial = scan()
		assertFalse(initial.any { it.id == first.id })
		assertEquals(hashed.canonicalPath, android.net.Uri.parse(initial.single { it.id == second.id }.url).path)
		assertEquals(legacy, initial.single { it.title == legacy.title })
		assertTrue(initial.any { it.title == wrongHash.nameWithoutExtension && it.id != second.id })
		assertTrue(local.getPages(initial.single { it.id == legacy.id }).isNotEmpty())
		val added = File(container, "Chapter 0.cbz").apply { writeBytes(bytes) }
		assertEquals(legacy, scan().single { it.title == legacy.title })
		assertTrue(added.delete())
		original.writeBytes(byteArrayOf(1, 2, 3, 4))
		val restored = scan()
		assertEquals(original.canonicalPath, android.net.Uri.parse(restored.single { it.id == first.id }.url).path)
		assertEquals(legacy, restored.single { it.id == legacy.id })
		assertEquals(key, ChapterPersonalKey.of(restored.single { it.id == legacy.id }))
		assertEquals(personalBefore, personalDao.findAll(listOf(manga.id)).toSet())
		assertEquals(progressBefore, progressDao.findAll(listOf(manga.id), FavouriteSpace.NORMAL.dbValue).toSet())
		assertEquals(historyBefore, historyDao.findIncludingDeleted(manga.id))
		assertEquals(bookmarksBefore, bookmarkDao.findAll(manga.id).toSet())
		assertEquals(overridesBefore, settings.getChapterReadOverrides(manga.id))
		assertEquals(aliasBefore, aliasPrefs.getString(aliasKey, null))
		assertEquals(page.url, local.getPages(restored.single { it.id == legacy.id }).first().url)
		assertArrayEquals(bytes, special.readBytes())
		assertArrayEquals(bytes, hashed.readBytes())
		assertArrayEquals(bytes, stage.readBytes())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
	}

	/** T05: ambiguous remote names and mismatched legacy hashes do not get an invented remote ID. */
	@Test fun normalScannerSidecarFreeMatchingRejectsAmbiguousCanonicalNames() = runBlocking {
		val seed = remote(810_077L)
		val first = seed.chapters.orEmpty().single()
		val second = first.copy(id = first.id + 1L, url = "/other")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, seed).file.canonicalFile
		val original = File(container, "Chapter 1.cbz")
		val bytes = original.readBytes()
		val result = requireNotNull(NormalDownloadDirectoryReconciler.reconcileSidecarFree(manga, container))
		val independent = result.manga.chapters.orEmpty().single()
		assertNotEquals(first.id, independent.id)
		assertNotEquals(second.id, independent.id)
		assertArrayEquals(bytes, original.readBytes())
		// A prior snapshot with a contradictory canonical ID cannot steal that remote identity.
		assertNull(NormalDownloadDirectoryReconciler.reconcileSidecarFree(
			manga, container, listOf(independent.copy(id = first.id)),
		))
		// A later remote snapshot cannot silently consume the verified independent key.
		assertNull(NormalDownloadDirectoryReconciler.reconcileSidecarFree(
			manga.copy(chapters = listOf(first)), container, listOf(independent),
		))
		// A distinct, unsupported chapter-directory layout is not observed absence.
		val nested = File(container, "Nested chapter").apply { check(mkdirs()) }
		assertNull(NormalDownloadDirectoryReconciler.reconcileSidecarFree(manga, container))
		assertTrue(nested.delete())
		// A retired indexed chapter with the identical ID may retain its old fragment URI.
		val retired = first.copy(id = independent.id, title = "Retired local", url = "/retired")
		val indexFile = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		indexFile.writeText(MangaIndex(null).apply {
			setMangaInfo(manga)
			addChapter(IndexedValue(0, retired), original.name)
		}.toString())
		val prior = independent.copy(title = retired.title)
		val indexed = requireNotNull(NormalDownloadDirectoryReconciler.reconcileIndexed(manga, container, listOf(prior)))
		assertEquals(prior, indexed.manga.chapters.orEmpty().single())
		assertEquals(prior.url, indexed.manga.chapters.orEmpty().single().url)
	}

	/** T01/T06/T10/T11: scoped deterministic hints survive a stale alias and exclude Private paths. */
	@Test fun normalScannerDiscoveryUsesActiveRootAndVerifiedSourceAliasHints() = runBlocking {
		val seed = remote(810_078L)
		val source = MissingMangaSource("MIHON_2677079941490683989")
		val manga = seed.copy(source = source, chapters = seed.chapters.orEmpty().map { it.copy(source = source) })
		favourite(manga)
		val old = File(root, "downloads/Bacami (ID)/${manga.title}").apply { check(mkdirs()) }
		val staleFile = File(old, "Chapter 1.cbz").apply { writeBytes(byteArrayOf(7)) }
		index.registerDownloadAlias(manga.id, manga.id + 100, old)
		val aliasBefore = index.getDownloadAliasPaths(listOf(manga.id))
		val active = File(root, "scanner-active-c")
		destinations.setRoot(FavouriteSpace.NORMAL, active)
		val current = File(active, "downloads/Bacami (ID)/${manga.title}").apply { check(mkdirs()) }
		val manual = File(current, "Chapter 1.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
		val privateCopy = File(privateRoot, "downloads/Bacami (ID)/${manga.title}").apply { check(mkdirs()) }
		File(privateCopy, manual.name).writeBytes(byteArrayOf(8))
		val found = requireNotNull(normalDirectoryDiscovery.discover(manga, FavouriteSpace.NORMAL))
		assertTrue(found.available)
		assertTrue(current.canonicalFile in found.deterministicPaths)
		assertFalse(old.canonicalFile in found.deterministicPaths)
		assertTrue(found.persistedHints.isEmpty())
		assertFalse(privateCopy.canonicalFile in found.deterministicPaths)
		assertEquals(aliasBefore, index.getDownloadAliasPaths(listOf(manga.id)))
		assertArrayEquals(byteArrayOf(7), staleFile.readBytes())
		assertArrayEquals(byteArrayOf(1, 2, 3, 4), manual.readBytes())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertNull(normalDirectoryDiscovery.discover(manga, FavouriteSpace.PRIVATE))
		// The same verified family alias produces the same directory hint, not ownership.
		val official = MissingMangaSource("TSUKI:MIYORARE:miyorare-id:BACAMI")
		val aliasManga = manga.copy(source = official, chapters = manga.chapters.orEmpty().map { it.copy(source = official) })
		assertTrue(current.canonicalFile in requireNotNull(
			normalDirectoryDiscovery.discover(aliasManga, FavouriteSpace.NORMAL),
		).deterministicPaths)
	}


	/** T10: direct indexed manual evidence, shared/reverse-nested and foreign owner conflicts. */
	@Test fun normalScannerVerifiedIndexedContainerRequiresSpaceIsolation() = runBlocking {
		val manga = remote(810_079L)
		favourite(manga)
		val container = download(root, manga).file.canonicalFile
		val file = File(container, "Chapter 1.cbz")
		val bytes = file.readBytes()
		val sidecar = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		sidecar.writeText(MangaIndex(null).apply {
			setMangaInfo(manga)
			addChapter(IndexedValue(0, manga.chapters.orEmpty().single()), file.name)
		}.toString())
		val sidecarBytes = sidecar.readBytes()
		val dao = db.getFavouriteDownloadIndexDao()
		suspend fun verify() = normalDirectoryDiscovery.verifyIndexedCandidate(manga, FavouriteSpace.NORMAL, container)
		// Private is nested elsewhere under Normal; the exact sidecar is enough here, without an owner.
		assertEquals(manga.chapters.orEmpty().single().id, requireNotNull(verify()).manga.chapters.orEmpty().single().id)
		assertNull(dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertNull(normalDirectoryDiscovery.verifyIndexedCandidate(manga, FavouriteSpace.PRIVATE, container))
		destinations.setRoot(FavouriteSpace.PRIVATE, root)
		assertNull(verify())
		val normalOwner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path)
		dao.upsert(normalOwner)
		assertNotNull(verify())
		val privateOwner = normalOwner.copy(space = FavouriteSpace.PRIVATE.dbValue)
		dao.upsert(privateOwner)
		assertNull(verify())
		dao.delete(FavouriteSpace.PRIVATE.dbValue, manga.id)

		// A foreign child owner cannot be masked by the exact Normal directory row.
		val foreign = remote(810_080L)
		data.storeManga(foreign, replaceExisting = true)
		val foreignOwner = FavouriteDownloadIndexEntity(foreign.id, FavouriteSpace.PRIVATE.dbValue, file.canonicalPath)
		dao.upsert(foreignOwner)
		assertNull(verify())
		dao.delete(FavouriteSpace.PRIVATE.dbValue, foreign.id)
		assertNotNull(verify())
		assertEquals(normalOwner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertArrayEquals(bytes, file.readBytes())
		assertArrayEquals(sidecarBytes, sidecar.readBytes())

		// A selected Private root inside the container itself is excluded before parsing.
		val privateInside = File(container, "private-inside")
		destinations.setRoot(FavouriteSpace.PRIVATE, privateInside)
		assertNull(verify())
		destinations.setRoot(FavouriteSpace.PRIVATE, root)
		assertTrue(privateInside.deleteRecursively())
		assertNotNull(verify())

		// Reverse nesting: Normal destination lives inside Private's selected root.
		val nested = File(root, "scanner-nested-normal")
		destinations.setRoot(FavouriteSpace.NORMAL, nested)
		val nestedContainer = download(nested, manga).file.canonicalFile
		val nestedSidecar = File(nestedContainer, LocalMangaOutput.ENTRY_NAME_INDEX)
		val nestedBytes = File(nestedContainer, file.name).readBytes()
		nestedSidecar.writeBytes(sidecarBytes)
		assertNull(normalDirectoryDiscovery.verifyIndexedCandidate(manga, FavouriteSpace.NORMAL, nestedContainer))
		val nestedOwner = normalOwner.copy(path = nestedContainer.path)
		dao.upsert(nestedOwner)
		assertNotNull(normalDirectoryDiscovery.verifyIndexedCandidate(manga, FavouriteSpace.NORMAL, nestedContainer))
		assertNull(verify()) // old root still exists but is not active
		assertEquals(nestedOwner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertArrayEquals(nestedBytes, File(nestedContainer, file.name).readBytes())
		assertArrayEquals(sidecarBytes, nestedSidecar.readBytes())
	}

	/** T05/T10: a persisted migrated indexed redirect needs exact path, source and chapter locator. */
	@Test fun normalScannerVerifiedMigratedIndexedAliasRequiresMatchingLocators() = runBlocking {
		val seed = remote(810_081L)
		val oldSource = MissingMangaSource("MIHON_2677079941490683989")
		val oldChapter = seed.chapters.orEmpty().single().copy(source = oldSource, url = "/chapter/1")
		val old = seed.copy(source = oldSource, chapters = listOf(oldChapter))
		val targetSource = MissingMangaSource("TSUKI:MIYORARE:miyorare-id:BACAMI")
		val targetChapter = oldChapter.copy(id = oldChapter.id + 10L, source = targetSource)
		val target = old.copy(id = 810_082L, source = targetSource, chapters = listOf(targetChapter))
		ids += target.id
		data.storeManga(old, replaceExisting = true)
		favourite(target)
		val container = download(root, old).file.canonicalFile
		val file = File(container, "Chapter 1.cbz")
		val bytes = file.readBytes()
		val sidecar = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		fun writeIndex(chapter: org.koitharu.kotatsu.parsers.model.MangaChapter) {
			sidecar.writeText(MangaIndex(null).apply {
				setMangaInfo(old)
				addChapter(IndexedValue(0, chapter), file.name)
			}.toString())
		}
		writeIndex(oldChapter)
		val originalIndex = sidecar.readBytes()
		index.rememberVerifiedPath(old.id, container)
		requireNotNull(index.prepareDownloadIdentityMove(old.id, target.id, mapOf(oldChapter.id to targetChapter.id)))
		val alias = requireNotNull(index.getDownloadAliasForScanner(target.id))
		suspend fun verify() = normalDirectoryDiscovery.verifyIndexedCandidate(target, FavouriteSpace.NORMAL, container)
		assertNull(verify()) // prepare is not proof of a committed Normal transfer
		val owner = FavouriteDownloadIndexEntity(target.id, FavouriteSpace.NORMAL.dbValue, container.path)
		db.getFavouriteDownloadIndexDao().upsert(owner)
		val resolved = requireNotNull(verify()).manga.chapters.orEmpty().single()
		assertEquals(targetChapter.id, resolved.id)
		assertEquals(file.canonicalPath, android.net.Uri.parse(resolved.url).path)
		assertTrue(local.getPages(resolved).isNotEmpty())
		assertEquals(alias, index.getDownloadAliasForScanner(target.id))
		assertArrayEquals(originalIndex, sidecar.readBytes())

		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(file.renameTo(special))
		val renamed = requireNotNull(verify()).manga.chapters.orEmpty().single()
		assertNotEquals(targetChapter.id, renamed.id)
		assertEquals(special.name, android.net.Uri.parse(renamed.url).fragment)
		assertTrue(local.getPages(renamed).isNotEmpty())
		assertArrayEquals(originalIndex, sidecar.readBytes())
		assertTrue(special.renameTo(file))
		assertEquals(targetChapter.id, requireNotNull(verify()).manga.chapters.orEmpty().single().id)

		writeIndex(oldChapter.copy(url = "/different-chapter"))
		val staleIndex = sidecar.readBytes()
		assertNull(verify())
		assertArrayEquals(staleIndex, sidecar.readBytes())
		assertTrue(sidecar.delete())
		assertNull(verify()) // no ordinal-based redirect when the sidecar is gone
		assertFalse(sidecar.exists())
		assertEquals(alias, index.getDownloadAliasForScanner(target.id))
		assertEquals(owner, db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, target.id))
		assertArrayEquals(bytes, file.readBytes())
	}


	/** T01/T10: real manual sidecar-free proof, known competing titles and historical Private overlap. */
	@Test fun normalScannerManualSidecarFreeProofRequiresSourceTitleAndChapterEvidence() = runBlocking {
		val seed = remote(810_083L)
		val source = MissingMangaSource("MIHON_2677079941490683989")
		val chapter = seed.chapters.orEmpty().single().copy(source = source, url = "/chapter/1")
		val manga = seed.copy(source = source, chapters = listOf(chapter))
		favourite(manga)
		val container = File(root, "downloads/Bacami (ID)/${manga.title}").apply { check(mkdirs()) }.canonicalFile
		val artifact = File(container, "Chapter 1.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
		val bytes = artifact.readBytes()
		val dao = db.getFavouriteDownloadIndexDao()
		suspend fun verify() = normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container)
		assertEquals(chapter.id, requireNotNull(verify()).manga.chapters.orEmpty().single().id)
		assertNull(dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertFalse(File(container, LocalMangaOutput.ENTRY_NAME_INDEX).exists())

		val other = remote(810_084L).copy(title = manga.title, source = MissingMangaSource("MIHON_4838485846640015979"), chapters = emptyList())
		favourite(other)
		// Known Komiku and Bacami directories are distinct, despite identical titles.
		assertNotNull(verify())
		val foreign = remote(810_085L).copy(title = manga.title, source = MissingMangaSource("UNVERIFIED_SOURCE"), chapters = emptyList())
		data.storeManga(foreign, replaceExisting = true)
		assertNull(verify())
		val movedForeign = foreign.copy(title = "Unrelated title")
		data.storeManga(movedForeign, replaceExisting = true)
		assertNotNull(verify())
		val sameSource = remote(810_086L).copy(title = manga.title.uppercase(java.util.Locale.ROOT), source = source, url = "/different-manga", chapters = emptyList())
		data.storeManga(sameSource, replaceExisting = true)
		assertNull(verify())
		data.storeManga(sameSource.copy(title = "Different title"), replaceExisting = true)
		assertNotNull(verify())

		// A title-only legacy location is a candidate hint, not manual source proof.
		val bare = File(root, "downloads/${manga.title}").apply { check(mkdirs()) }
		File(bare, artifact.name).writeBytes(bytes)
		assertNull(normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, bare))
		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(artifact.renameTo(special))
		assertNull(verify())
		assertTrue(special.renameTo(artifact))

		// Move Private away from a formerly shared root. History is a safety boundary,
		// never an extra source of positive Normal presence.
		destinations.setRoot(FavouriteSpace.PRIVATE, root)
		destinations.setRoot(FavouriteSpace.PRIVATE, privateRoot)
		assertNull(verify())
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path)
		dao.upsert(owner)
		assertNotNull(verify())
		assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertArrayEquals(bytes, artifact.readBytes())
		assertFalse(File(container, LocalMangaOutput.ENTRY_NAME_INDEX).exists())
	}

	/** T10: absolute noncanonical child path must not hide a Private ownership conflict. */
	@Test fun normalScannerCanonicalOwnershipSnapshotRejectsNoncanonicalConflicts() = runBlocking {
		val manga = remote(810_087L)
		favourite(manga)
		val container = download(root, manga).file.canonicalFile
		val artifact = File(container, "Chapter 1.cbz")
		val bytes = artifact.readBytes()
		val dao = db.getFavouriteDownloadIndexDao()
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path)
		dao.upsert(owner)
		val foreign = remote(810_088L)
		data.storeManga(foreign, replaceExisting = true)
		val noncanonical = File(container, "./${artifact.name}").path
		assertNotEquals(artifact.canonicalPath, noncanonical)
		assertEquals(artifact.canonicalPath, File(noncanonical).canonicalPath)
		val foreignOwner = FavouriteDownloadIndexEntity(foreign.id, FavouriteSpace.PRIVATE.dbValue, noncanonical)
		dao.upsert(foreignOwner)
		val evidence = requireNotNull(normalDirectoryDiscovery.captureVerificationEvidence())
		assertNull(normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container, evidence))
		assertEquals(foreignOwner, dao.findEntry(FavouriteSpace.PRIVATE.dbValue, foreign.id))
		dao.delete(FavouriteSpace.PRIVATE.dbValue, foreign.id)
		// A new work snapshot is required after metadata changes.
		val refreshed = requireNotNull(normalDirectoryDiscovery.captureVerificationEvidence())
		assertNotNull(normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container, refreshed))
		assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertArrayEquals(bytes, artifact.readBytes())
	}

	/** T01/T03: exercise the real directory writer before and after durable chapter flush. */
	@Test fun normalScannerSidecarFreeProofWaitsForActualStandaloneWriterFlush() = runBlocking {
		val manga = remote(810_089L)
		favourite(manga)
		val image = File(root, "writer-fixture.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val chapter = manga.chapters.orEmpty().single()
		val output = LocalMangaOutput.getOrCreate(root, manga, DownloadFormat.MULTIPLE_CBZ)
		try {
			val container = output.rootFile.canonicalFile
			val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path)
			val dao = db.getFavouriteDownloadIndexDao()
			dao.upsert(owner)
			suspend fun verify() = normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container)
			output.addPage(IndexedValue(0, chapter), image, 0, MimeType("image/png"))
			val staging = File(container, "Chapter 1.cbz" + LocalMangaOutput.SUFFIX_TMP)
			val final = File(container, "Chapter 1.cbz")
			assertTrue(staging.isFile)
			assertFalse(final.exists())
			assertNull(verify())
			assertTrue(output.flushChapter(chapter))
			assertFalse(staging.exists())
			assertTrue(final.isFile)
			val bytes = final.readBytes()
			assertEquals(chapter.id, requireNotNull(verify()).manga.chapters.orEmpty().single().id)
			// Model the final-name-visible interval of the actual fallback copy. This is
			// not a fault-injected writer concurrency test; it exercises the proof boundary.
			staging.writeBytes(bytes)
			assertNull(verify())
			assertTrue(staging.delete())
			assertNotNull(verify())
			assertArrayEquals(bytes, final.readBytes())
			assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
			output.finish()
		} finally { output.close() }
	}


	/** T05: read durable ID + exact URI evidence without passing the old parser object to recovery. */
	@Test fun normalScannerRecoversCorroboratedPriorLocalIdentityFromStoredPersonalRecords() = runBlocking {
		val seed = remote(810_090L)
		val first = seed.chapters.orEmpty().single().copy(number = 0f)
		val second = first.copy(id = first.id + 1L, title = "Chapter 2", url = "/chapter/2")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, seed).file.canonicalFile
		val original = File(container, "Chapter 1.cbz")
		val bytes = original.readBytes()
		val sibling = File(container, "Chapter 2.cbz")
		original.copyTo(sibling)
		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(original.renameTo(special))
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path)
		val ownerDao = db.getFavouriteDownloadIndexDao()
		ownerDao.upsert(owner)
		// The surviving canonical sibling and exact owner establish this Normal container.
		val initialProof = requireNotNull(normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container))
		assertTrue(initialProof.manga.chapters.orEmpty().any { it.id == second.id })
		val legacy = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty()
			.single { it.title == "Chapter 1 Special" }
		val key = ChapterPersonalKey.of(legacy)
		val page = local.getPages(legacy).first()
		assertFalse(legacy.id in setOf(first.id, second.id))
		val personalDao = db.getChapterPersonalDao()
		val progressDao = db.getChapterProgressDao()
		val bookmarkDao = db.getBookmarksDao()
		val historyDao = db.getHistoryDao()
		personalDao.upsert(ChapterPersonalEntity(manga.id, key.source, key.url, 4, "Prior independent note"))
		progressDao.upsert(ChapterProgressEntity(manga.id, key.source, key.url, FavouriteSpace.NORMAL.dbValue, 1, 0, 2))
		val bookmark = BookmarkEntity(manga.id, page.id, legacy.id, 0, 0, page.url, 100L, 0.5f)
		bookmarkDao.insert(bookmark)
		historyDao.insert(HistoryEntity(manga.id, 100L, 200L, legacy.id, 0, 0f, 0.5f, 0L, 1))
		// An unrelated ID-only read flag cannot prove a chapter locator.
		val unknownId = legacy.id xor 123456789L
		assertFalse(unknownId in setOf(0L, first.id, second.id, legacy.id))
		settings.setChapterReadOverrides(manga.id, mapOf(legacy.id to true, unknownId to false))
		val personalBefore = personalDao.findAll(listOf(manga.id)).toSet()
		val progressBefore = progressDao.findAll(listOf(manga.id), FavouriteSpace.NORMAL.dbValue).toSet()
		val historyBefore = historyDao.findIncludingDeleted(manga.id)
		val overridesBefore = settings.getChapterReadOverrides(manga.id)

		// Listing changes after the old parser generated the ID. Recovery uses only stored
		// records, not that in-memory chapter nor a new ordinal. This is a fresh reader
		// instance, not an instrumentation process-death test.
		val unrelated = File(container, "Chapter 0.cbz").apply { writeBytes(bytes) }
		val reparsed = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty()
			.single { it.title == legacy.title }
		assertNotEquals("The pre-scanner ordinal really changed", legacy.id, reparsed.id)
		suspend fun recover() = requireNotNull(NormalDownloadPriorIdentityReader(db, settings).read(manga, container))
		val recovered = recover()
		assertEquals(legacy.id, recovered.verified.single().id)
		assertEquals(key, ChapterPersonalKey.of(recovered.verified.single()))
		assertTrue(recovered.unresolvedLocalKeys.isEmpty())
		assertEquals(setOf(unknownId), recovered.unresolvedIds)
		val projected = requireNotNull(
			NormalDownloadDirectoryReconciler.reconcileSidecarFree(manga, container, recovered.verified),
		).manga.chapters.orEmpty()
		assertEquals(legacy.id, projected.single { it.url == key.url }.id)
		assertEquals(page.url, local.getPages(projected.single { it.id == legacy.id }).first().url)
		assertTrue(unrelated.delete())
		assertTrue(special.delete())
		assertEquals(legacy.id, recover().verified.single().id) // reserve missing prior ID, no reassignment
		special.writeBytes(bytes)
		assertEquals(key, ChapterPersonalKey.of(recover().verified.single()))
		assertEquals(personalBefore, personalDao.findAll(listOf(manga.id)).toSet())
		assertEquals(progressBefore, progressDao.findAll(listOf(manga.id), FavouriteSpace.NORMAL.dbValue).toSet())
		assertEquals(historyBefore, historyDao.findIncludingDeleted(manga.id))
		assertEquals(overridesBefore, settings.getChapterReadOverrides(manga.id))
		assertEquals(listOf(bookmark), bookmarkDao.findAll(manga.id))

		// A stale/replaced image URL cannot independently establish the bookmark ID's locator.
		bookmarkDao.upsert(listOf(bookmark.copy(imageUrl = page.url + "?stale=1")))
		val ambiguous = recover()
		assertTrue(ambiguous.verified.isEmpty())
		assertEquals(setOf(key), ambiguous.unresolvedLocalKeys)
		assertTrue(legacy.id in ambiguous.unresolvedIds)
		assertArrayEquals(bytes, special.readBytes())
		assertArrayEquals(bytes, sibling.readBytes())
		assertEquals(owner, ownerDao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
	}


	/** T01/T03: actual SINGLE_CBZ writer commits embedded identity only on finish, including legacy ZIP suffix. */
	@Test fun normalScannerSingleArchiveVerifiesEmbeddedIndexAfterFinish() = runBlocking {
		val seed = remote(810_091L)
		val first = seed.chapters.orEmpty().single()
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2", number = 2f)
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val image = File(root, "archive-fixture.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val output = LocalMangaOutput.getOrCreate(root, manga, DownloadFormat.SINGLE_CBZ)
		val target = output.rootFile.canonicalFile
		try {
			output.addPage(IndexedValue(0, first), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(first))
			assertFalse(target.exists())
			assertNull(normalDirectoryDiscovery.verifyArchiveCandidate(manga, FavouriteSpace.NORMAL, target))
			output.addPage(IndexedValue(1, second), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(second))
			output.finish()
		} finally { output.close() }
		val bytes = target.readBytes()
		val result = requireNotNull(normalDirectoryDiscovery.verifyArchiveCandidate(manga, FavouriteSpace.NORMAL, target))
		assertEquals(setOf(first.id, second.id), result.manga.chapters.orEmpty().map { it.id }.toSet())
		for (chapter in result.manga.chapters.orEmpty()) {
			assertEquals(target.canonicalPath, android.net.Uri.parse(chapter.url).path)
			assertEquals(1, local.getPages(chapter).size)
		}
		assertNull(normalDirectoryDiscovery.verifyArchiveCandidate(manga.copy(url = "/wrong-manga"), FavouriteSpace.NORMAL, target))
		val stale = manga.copy(chapters = listOf(first.copy(url = "/wrong-chapter"), second))
		assertNull(normalDirectoryDiscovery.verifyArchiveCandidate(stale, FavouriteSpace.NORMAL, target))
		val legacy = File(target.parentFile, target.nameWithoutExtension + "_7.zip")
		assertTrue(target.renameTo(legacy))
		val candidates = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL))
		assertEquals(legacy, candidates.uniqueCandidate?.file)
		assertArrayEquals(bytes, legacy.readBytes())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
	}

	/** T01/T06/T10: suffix gaps and multiple copies are retained, never first-match selection. */
	@Test fun normalScannerMultiCandidateInventoryDoesNotChooseAmbiguousCopy() = runBlocking {
		val manga = remote(810_092L)
		favourite(manga)
		val original = download(root, manga).file.canonicalFile
		val cbz = File(original, "Chapter 1.cbz")
		val sidecar = File(original, LocalMangaOutput.ENTRY_NAME_INDEX)
		sidecar.writeText(MangaIndex(null).apply {
			setMangaInfo(manga)
			addChapter(IndexedValue(0, manga.chapters.orEmpty().single()), cbz.name)
		}.toString())
		val suffix = File(original.parentFile, original.name + "_9")
		original.copyRecursively(suffix)
		val other = File(original.parentFile, original.name + "_12")
		original.copyRecursively(other)
		val foreign = remote(810_093L)
		File(other, sidecar.name).writeText(MangaIndex(null).apply {
			setMangaInfo(foreign)
			addChapter(IndexedValue(0, foreign.chapters.orEmpty().single()), cbz.name)
		}.toString())
		val before = cbz.readBytes()
		val evidence = requireNotNull(normalDirectoryDiscovery.captureVerificationEvidence())
		val result = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL, evidence))
		assertEquals(setOf(original, suffix), result.verified.map { it.file }.toSet())
		assertEquals(setOf(other), result.unresolvedPaths)
		assertNull(result.uniqueCandidate)
		assertTrue(other.deleteRecursively())
		assertTrue(original.deleteRecursively())
		val only = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL, evidence))
		assertEquals(suffix, only.uniqueCandidate?.file)
		assertArrayEquals(before, File(suffix, cbz.name).readBytes())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
	}

	/** T03: force the actual root commit helper's rename-failure fallback and observe its copy window. */
	@Test fun normalScannerSingleArchiveRejectsActualForcedCopyWindowAndBackupOnlyState() = runBlocking {
		val manga = remote(810_094L)
		favourite(manga)
		val image = File(root, "copy-fixture.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val output = LocalMangaOutput.getOrCreate(root, manga, DownloadFormat.SINGLE_CBZ)
		val target = output.rootFile.canonicalFile
		try {
			val chapter = manga.chapters.orEmpty().single()
			output.addPage(IndexedValue(0, chapter), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(chapter))
			output.finish()
		} finally { output.close() }
		val bytes = target.readBytes()
		val temp = File(target.path + ".tmp").apply { writeBytes(bytes) }
		val backup = File(target.path + ".bak.tmp")
		var copyObserved = false
		commitRootReplacement(target, temp, object : RootFileCommitOps by RealRootFileCommitOps {
			override fun rename(source: File, targetFile: File): Boolean =
				if (source == temp) false else RealRootFileCommitOps.rename(source, targetFile)
			override fun copy(source: File, targetFile: File) {
				assertEquals(temp, source)
				assertEquals(target, targetFile)
				targetFile.outputStream().use { stream ->
					stream.write(bytes, 0, bytes.size / 2)
					stream.flush()
					assertTrue(temp.exists())
					assertTrue(backup.exists())
					assertNull(runBlocking {
						normalDirectoryDiscovery.verifyArchiveCandidate(manga, FavouriteSpace.NORMAL, target)
					})
					stream.write(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
					copyObserved = true
				}
			}
		})
		assertTrue(copyObserved)
		assertFalse(temp.exists())
		assertFalse(backup.exists())
		assertArrayEquals(bytes, target.readBytes())
		assertNotNull(normalDirectoryDiscovery.verifyArchiveCandidate(manga, FavouriteSpace.NORMAL, target))
		assertTrue(target.renameTo(backup))
		val unresolved = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL))
		assertNull(unresolved.uniqueCandidate)
		assertTrue(target in unresolved.unresolvedPaths)
		assertFalse(target.exists())
		assertArrayEquals(bytes, backup.readBytes())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
	}


	/** T05/T13: actual on-disk Room close/reopen, not a process-kill test or an identity migration. */
	@Test fun normalScannerPriorIdentityReopenedDatabaseWithholdsUnresolvedProjection() = runBlocking {
		val seed = remote(810_095L)
		val first = seed.chapters.orEmpty().single().copy(number = 0f)
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, seed).file.canonicalFile
		val original = File(container, "Chapter 1.cbz")
		val sibling = File(container, "Chapter 2.cbz")
		original.copyTo(sibling)
		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(original.renameTo(special))
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(
			manga.id, FavouriteSpace.NORMAL.dbValue, container.path,
		))
		val candidate = requireNotNull(normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container))
		val legacy = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty()
			.single { it.title == "Chapter 1 Special" }
		val page = local.getPages(legacy).first()
		val key = ChapterPersonalKey.of(legacy)
		// Real pre-scanner ID collision: identical relative names/order in a different root.
		val privateContainer = File(privateRoot, "prior-collision")
		assertTrue(container.copyRecursively(privateContainer))
		val privateLegacy = LocalMangaParser(privateContainer).getManga(withDetails = true).manga.chapters.orEmpty()
			.single { it.title == legacy.title }
		assertEquals(legacy.id, privateLegacy.id)
		val privatePage = local.getPages(privateLegacy).first()
		assertNotEquals(page.url, privatePage.url)
		assertNotEquals(page.id, privatePage.id)
		val entity = requireNotNull(db.getMangaDao().find(manga.id)).manga
		val personal = ChapterPersonalEntity(manga.id, key.source, key.url, 4, "Persisted prior note")
		val progress = ChapterProgressEntity(manga.id, key.source, key.url, FavouriteSpace.NORMAL.dbValue, 1, 0, 2)
		val bookmark = BookmarkEntity(manga.id, page.id, legacy.id, 0, 0, page.url, 100L, 0.5f)
		val conflictingBookmark = bookmark.copy(pageId = privatePage.id, imageUrl = privatePage.url)
		val unknownId = legacy.id xor 123456789L
		assertFalse(unknownId in setOf(0L, first.id, second.id, legacy.id))
		val history = HistoryEntity(manga.id, 100L, 200L, unknownId, 0, 0f, 0.5f, 0L, 2)
		val name = "normal-prior-${System.nanoTime()}.db"
		fun open() = Room.databaseBuilder(context, MangaDatabase::class.java, name).build()
		try {
			val initial = open()
			try {
				initial.getMangaDao().upsert(entity)
				initial.getChapterPersonalDao().upsert(personal)
				initial.getChapterProgressDao().upsert(progress)
				initial.getBookmarksDao().insert(bookmark)
				initial.getBookmarksDao().insert(conflictingBookmark)
				initial.getHistoryDao().insert(history)
			} finally { initial.close() }
			val extra = File(container, "Chapter 0.cbz").apply { writeBytes(special.readBytes()) }
			val reparsed = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty()
				.single { it.title == legacy.title }
			assertNotEquals(legacy.id, reparsed.id)
			val reopened = open()
			try {
				val reader = NormalDownloadPriorIdentityReader(reopened, AppSettings(context))
				val conflict = requireNotNull(reader.reconcileVerifiedDirectory(manga, candidate))
				assertTrue(conflict.evidence.verified.isEmpty())
				assertEquals(setOf(key), conflict.evidence.unresolvedLocalKeys)
				assertEquals(setOf(unknownId, legacy.id), conflict.evidence.unresolvedIds)
				assertNull(conflict.projection)
				assertEquals(setOf(bookmark, conflictingBookmark), reopened.getBookmarksDao().findAll(manga.id).toSet())
				// Explicit test cleanup removes only the conflicting record, not a scanner migration.
				reopened.getBookmarksDao().delete(conflictingBookmark)
				val result = requireNotNull(reader.reconcileVerifiedDirectory(manga, candidate))
				assertEquals(legacy.id, result.evidence.verified.single().id)
				assertEquals(key, ChapterPersonalKey.of(result.evidence.verified.single()))
				assertEquals(setOf(unknownId), result.evidence.unresolvedIds)
				assertNull(result.projection)
				assertEquals(history, reopened.getHistoryDao().findIncludingDeleted(manga.id))
				assertEquals(listOf(personal), reopened.getChapterPersonalDao().findAll(listOf(manga.id)))
				assertEquals(listOf(progress), reopened.getChapterProgressDao().findAll(listOf(manga.id), FavouriteSpace.NORMAL.dbValue))
				assertEquals(listOf(bookmark), reopened.getBookmarksDao().findAll(manga.id))
				// Explicit fixture mutation supplies the missing ID evidence. The scanner did not
				// rewrite the unknown record; the next cold read uses the actual known prior ID.
				reopened.getHistoryDao().update(history.copy(chapterId = legacy.id))
			} finally { reopened.close() }
			assertTrue(extra.delete())
			val again = open()
			try {
				val result = requireNotNull(NormalDownloadPriorIdentityReader(again, AppSettings(context))
					.reconcileVerifiedDirectory(manga, candidate))
				assertTrue(result.evidence.unresolvedIds.isEmpty())
				assertTrue(result.evidence.unresolvedLocalKeys.isEmpty())
				val restored = requireNotNull(result.projection).manga.chapters.orEmpty().single { it.id == legacy.id }
				assertEquals(key, ChapterPersonalKey.of(restored))
				assertEquals(page.url, local.getPages(restored).first().url)
				assertEquals(history.copy(chapterId = legacy.id), again.getHistoryDao().findIncludingDeleted(manga.id))
				assertEquals(listOf(personal), again.getChapterPersonalDao().findAll(listOf(manga.id)))
				assertEquals(listOf(progress), again.getChapterProgressDao().findAll(listOf(manga.id), FavouriteSpace.NORMAL.dbValue))
				assertEquals(listOf(bookmark), again.getBookmarksDao().findAll(manga.id))
			} finally { again.close() }
		} finally { context.deleteDatabase(name) }
	}


	/** T05: exact owned renamed-only copy needs surviving corroborated ID/URI, not an owner alone. */
	@Test fun normalScannerOwnedRenamedOnlyRequiresCorroboratedSurvivingIdentity() = runBlocking {
		val manga = remote(810_096L)
		favourite(manga)
		val container = download(root, manga).file.canonicalFile
		val original = File(container, "Chapter 1.cbz")
		val bytes = original.readBytes()
		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(original.renameTo(special))
		val legacy = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty().single()
		val key = ChapterPersonalKey.of(legacy)
		val page = local.getPages(legacy).first()
		val dao = db.getFavouriteDownloadIndexDao()
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path)
		suspend fun verify() = normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container)
		assertNull(verify())
		dao.upsert(owner)
		assertNull(verify())
		val personal = ChapterPersonalEntity(manga.id, key.source, key.url, 4, "Renamed prior")
		db.getChapterPersonalDao().upsert(personal)
		assertNull(verify()) // key-only cannot establish a legacy ID
		val bookmark = BookmarkEntity(manga.id, page.id, legacy.id, 0, 0, page.url, 100L, 0.5f)
		db.getBookmarksDao().insert(bookmark)
		val verified = requireNotNull(verify()).manga.chapters.orEmpty().single()
		assertEquals(legacy.id, verified.id)
		assertEquals(key, ChapterPersonalKey.of(verified))
		assertEquals(page.url, local.getPages(verified).first().url)
		assertNotEquals(manga.chapters.orEmpty().single().id, verified.id)

		val unknownId = legacy.id xor 123456789L
		assertFalse(unknownId in setOf(0L, legacy.id, manga.chapters.orEmpty().single().id))
		settings.setChapterReadOverrides(manga.id, mapOf(unknownId to true))
		assertNull(verify()) // insufficient locator evidence, not an absent observation
		assertEquals(mapOf(unknownId to true), settings.getChapterReadOverrides(manga.id))
		settings.setChapterReadOverrides(manga.id, emptyMap())

		// Corrupt bytes do not negate an already verified locator; no archive read is required.
		val corrupt = byteArrayOf(1, 2, 3, 4)
		special.writeBytes(corrupt)
		assertEquals(legacy.id, requireNotNull(verify()).manga.chapters.orEmpty().single().id)
		assertArrayEquals(corrupt, special.readBytes())
		assertTrue(special.delete())
		File(container, "Unrelated.cbz").writeBytes(bytes)
		assertNull(verify()) // prior ID cannot be transferred to another filename
		special.writeBytes(bytes)
		assertNotNull(verify())
		assertEquals(listOf(personal), db.getChapterPersonalDao().findAll(listOf(manga.id)))
		assertEquals(listOf(bookmark), db.getBookmarksDao().findAll(manga.id))
		assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertArrayEquals(bytes, special.readBytes())
		assertFalse(original.exists())
	}

	/** T03: force the real standalone writer's flush fallback using its per-instance file ops. */
	@Test fun normalScannerStandaloneFlushRejectsForcedPartialCopyAndRetainsStaleStaging() = runBlocking {
		val manga = remote(810_097L)
		favourite(manga)
		val container = File(root, "downloads/${manga.title}").canonicalFile
		val image = File(root, "standalone-copy.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val target = File(container, "Chapter 1.cbz")
		val temp = File(target.path + ".tmp")
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path)
		val dao = db.getFavouriteDownloadIndexDao()
		dao.upsert(owner)
		suspend fun verify() = normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container)
		var observed = false
		val writer = LocalMangaDirOutput(container, manga, object : RootFileCommitOps by RealRootFileCommitOps {
			override fun rename(source: File, targetFile: File): Boolean {
				assertEquals(temp, source)
				assertEquals(target, targetFile)
				return false
			}
			override fun copy(source: File, targetFile: File) {
				assertEquals(temp, source)
				assertEquals(target, targetFile)
				val bytes = source.readBytes()
				targetFile.outputStream().use { stream ->
					stream.write(bytes, 0, bytes.size / 2)
					stream.flush()
					assertTrue(temp.exists())
					assertTrue(target.isFile)
					assertNull(runBlocking { verify() })
					stream.write(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
					observed = true
				}
			}
		})
		try {
			val chapter = manga.chapters.orEmpty().single()
			writer.addPage(IndexedValue(0, chapter), image, 0, MimeType("image/png"))
			assertTrue(temp.isFile)
			assertNull(verify())
			assertTrue(writer.flushChapter(chapter))
			assertTrue(observed)
			assertFalse(temp.exists())
			val committed = requireNotNull(verify()).manga.chapters.orEmpty().single()
			assertEquals(chapter.id, committed.id)
			assertTrue(local.getPages(committed).isNotEmpty())
			val bytes = target.readBytes()
			val backup = File(target.path + ".bak.tmp").apply { writeBytes(bytes) }
			assertNull(verify()) // cannot distinguish interrupted/stale replacement from scan alone
			assertArrayEquals(bytes, backup.readBytes())
			assertArrayEquals(bytes, target.readBytes())
			assertTrue(backup.delete()) // explicit fixture cleanup, not scanner recovery
			assertNotNull(verify())
			assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
			writer.finish()
		} finally { writer.close() }
	}


	/** T01/T10/T14: bounded parent index reuse, case duplicates, symlink and unavailable root. */
	@Test fun normalScannerParentIndexReuseRejectsCaseAndSymlinkAmbiguity() = runBlocking {
		val manga = remote(810_098L)
		val other = remote(810_099L)
		favourite(manga)
		favourite(other)
		val container = download(root, manga).file.canonicalFile
		val sibling = download(root, other).file.canonicalFile
		val dao = db.getFavouriteDownloadIndexDao()
		dao.upsert(FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path))
		dao.upsert(FavouriteDownloadIndexEntity(other.id, FavouriteSpace.NORMAL.dbValue, sibling.path))
		val chapter = File(container, "Chapter 1.cbz")
		val bytes = chapter.readBytes()
		val parent = requireNotNull(container.parentFile)
		repeat(120) { n -> File(parent, "Unrelated-$n.cbz").writeBytes(byteArrayOf(0)) }
		val cache = HashMap<File, NormalScannerParentListing?>()
		val evidence = requireNotNull(normalDirectoryDiscovery.captureVerificationEvidence())
		val first = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL, evidence, cache))
		assertEquals(container, first.uniqueCandidate?.file)
		val listing = requireNotNull(cache[parent])
		val otherResult = requireNotNull(normalDirectoryDiscovery.resolveCandidates(other, FavouriteSpace.NORMAL, evidence, cache))
		assertEquals(sibling, otherResult.uniqueCandidate?.file)
		assertSame(listing, cache[parent])
		assertEquals(setOf(container), listing.matching(setOf(container.name.lowercase(java.util.Locale.ROOT))))

		// Case duplicates are not resolved by listing order. App-private test storage is case-sensitive.
		val duplicate = File(container, "chapter 1.CBZ")
		chapter.copyTo(duplicate)
		assertTrue(chapter.isFile && duplicate.isFile)
		assertNotEquals(chapter.canonicalPath, duplicate.canonicalPath)
		assertNull(normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container))
		assertTrue(duplicate.delete())

		val link = File(parent, container.name + "_19")
		try {
			android.system.Os.symlink(container.path, link.path)
			assertEquals(container, link.canonicalFile)
			val ambiguous = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL))
			assertEquals(setOf(container), ambiguous.verified.map { it.file }.toSet())
			assertTrue(link in ambiguous.unresolvedPaths)
			assertNull(ambiguous.uniqueCandidate)
			assertNull(normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, link))
		} finally { if (link.exists()) assertTrue(link.delete()) }
		assertNotNull(normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container))
		val unavailable = File(root, "unavailable-root")
		destinations.setRoot(FavouriteSpace.NORMAL, unavailable)
		// Simulate unavailable storage after selection, not a provider permission test.
		assertTrue(unavailable.deleteRecursively())
		val missing = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL))
		assertFalse(missing.inventoryComplete)
		assertTrue(missing.verified.isEmpty())
		assertNull(missing.uniqueCandidate)
		assertFalse(unavailable.exists())
		assertArrayEquals(bytes, chapter.readBytes())
		assertEquals(container.path, dao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id)?.path)
	}


	/** T05: exact producer names, punctuation-only rename, and literal-vs-hash collisions. */
	@Test fun normalScannerFilenameProofPreservesPunctuationAndRejectsCompetingHashClaims() = runBlocking {
		val seed = remote(810_100L)
		val first = seed.chapters.orEmpty().single().copy(title = "Chapter 1!", scanlator = "Group!")
		val second = first.copy(id = first.id + 1, title = "Chapter 2", scanlator = null, url = "/chapter/2")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val image = File(root, "filename-proof.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val writer = LocalMangaOutput.getOrCreate(root, manga, DownloadFormat.MULTIPLE_CBZ)
		val container = writer.rootFile.canonicalFile
		try {
			writer.addPage(IndexedValue(0, first), image, 0, MimeType("image/png"))
			assertTrue(writer.flushChapter(first))
			writer.finish()
		} finally { writer.close() }
		val original = File(container, "Group!_Chapter 1!.cbz")
		assertTrue(original.isFile)
		val bytes = original.readBytes()
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path)
		db.getFavouriteDownloadIndexDao().upsert(owner)
		suspend fun reconcile() = requireNotNull(
			NormalDownloadDirectoryReconciler.reconcileSidecarFree(manga, container),
		).manga.chapters.orEmpty()
		assertEquals(first.id, reconcile().single().id)
		val renamed = File(container, "Group!_Chapter 1.cbz")
		assertTrue(original.renameTo(renamed))
		val independent = reconcile().single()
		assertNotEquals(first.id, independent.id)
		assertEquals(renamed.name, android.net.Uri.parse(independent.url).fragment)
		assertTrue(local.getPages(independent).isNotEmpty())
		assertTrue(renamed.renameTo(original))
		assertEquals(first.id, reconcile().single().id)
		assertArrayEquals(bytes, original.readBytes())

		val hash = java.security.MessageDigest.getInstance("MD5").digest(second.url.toByteArray(Charsets.UTF_8))
			.take(3).joinToString("") { "%02x".format(it.toInt() and 0xff) }
		val hashed = "Chapter 2 ($hash).cbz"
		assertEquals(second, LegacyChapterDownloadCompat.matchStandaloneNames(listOf(second), listOf(hashed))[hashed])
		val literal = second.copy(id = second.id + 1, title = "Chapter 2 ($hash)", url = "/literal")
		val claims = LegacyChapterDownloadCompat.matchStandaloneNames(
			listOf(second, literal), listOf("Chapter 2.cbz", hashed),
		)
		assertTrue(claims.isEmpty())
		assertTrue(LegacyChapterDownloadCompat.matchStandaloneNames(
			listOf(first.copy(title = null)), listOf("Group!_Chapter 1.cbz"),
		).isEmpty())
		assertEquals(owner, db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
	}

	/** T01/T05/T10: source-qualified manual renamed-only evidence without minting ownership. */
	@Test fun normalScannerUnownedManualRenamedOnlyRequiresIndependentSourceAndPriorProof() = runBlocking {
		val seed = remote(810_101L)
		val source = MissingMangaSource("MIHON_2677079941490683989")
		val manga = seed.copy(source = source, chapters = seed.chapters.orEmpty().map { it.copy(source = source) })
		favourite(manga)
		val old = download(root, seed).file.canonicalFile
		val artifact = File(old, "Chapter 1.cbz")
		val bytes = artifact.readBytes()
		val container = File(root, "downloads/Bacami (ID)/${manga.title}").canonicalFile
		assertTrue(container != old)
		assertTrue(container.mkdirs() || container.isDirectory)
		val special = File(container, "Chapter 1 Special.cbz")
		artifact.copyTo(special)
		assertTrue(old.deleteRecursively())
		val legacy = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty().single()
		val key = ChapterPersonalKey.of(legacy)
		val page = local.getPages(legacy).first()
		val ownerDao = db.getFavouriteDownloadIndexDao()
		suspend fun verify() = normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, FavouriteSpace.NORMAL, container)
		assertNull(verify())
		val personal = ChapterPersonalEntity(manga.id, key.source, key.url, 4, "Manual prior")
		db.getChapterPersonalDao().upsert(personal)
		assertNull(verify())
		val bookmark = BookmarkEntity(manga.id, page.id, legacy.id, 0, 0, page.url, 100L, 0.5f)
		db.getBookmarksDao().insert(bookmark)
		val restored = requireNotNull(verify()).manga.chapters.orEmpty().single()
		assertEquals(legacy.id, restored.id)
		assertEquals(key, ChapterPersonalKey.of(restored))
		assertEquals(page.url, local.getPages(restored).first().url)
		assertNull(ownerDao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		// An unrelated provider with the same normalized title removes source-qualified
		// manual certainty. Personal records alone do not identify that provider.
		val foreign = remote(810_102L).copy(title = manga.title, source = MissingMangaSource("UNVERIFIED_SOURCE"), chapters = emptyList())
		data.storeManga(foreign, replaceExisting = true)
		assertNull(verify())
		assertEquals(listOf(personal), db.getChapterPersonalDao().findAll(listOf(manga.id)))
		assertEquals(listOf(bookmark), db.getBookmarksDao().findAll(manga.id))
		assertNull(ownerDao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertFalse(File(container, LocalMangaOutput.ENTRY_NAME_INDEX).exists())
		assertArrayEquals(bytes, special.readBytes())
	}

	/** T05: unique directory selection must retain prior identity separately from physical evidence. */
	@Test fun normalScannerUniqueDirectoryReconcilesPriorIdentityWithCanonicalSibling() = runBlocking {
		val seed = remote(810_103L)
		val first = seed.chapters.orEmpty().single().copy(number = 0f)
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2")
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, seed).file.canonicalFile
		val original = File(container, "Chapter 1.cbz")
		val bytes = original.readBytes()
		val sibling = File(container, "Chapter 2.cbz")
		original.copyTo(sibling)
		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(original.renameTo(special))
		val owner = FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path)
		val ownerDao = db.getFavouriteDownloadIndexDao()
		ownerDao.upsert(owner)
		val legacy = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty()
			.single { it.title == "Chapter 1 Special" }
		val key = ChapterPersonalKey.of(legacy)
		val page = local.getPages(legacy).first()
		assertFalse(legacy.id in setOf(first.id, second.id))
		val personal = ChapterPersonalEntity(manga.id, key.source, key.url, 4, "Prior independent note")
		val progress = ChapterProgressEntity(manga.id, key.source, key.url, FavouriteSpace.NORMAL.dbValue, 1, 0, 2)
		val bookmark = BookmarkEntity(manga.id, page.id, legacy.id, 0, 0, page.url, 100L, 0.5f)
		val history = HistoryEntity(manga.id, 100L, 200L, legacy.id, 0, 0f, 0.5f, 0L, 1)
		suspend fun resolve() = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL))
		db.getChapterPersonalDao().upsert(personal)
		db.getChapterProgressDao().upsert(progress)
		val keyOnly = resolve()
		assertEquals(container, keyOnly.uniqueCandidate?.file)
		val keyEvidence = requireNotNull(keyOnly.directoryIdentity)
		assertEquals(setOf(key), keyEvidence.evidence.unresolvedLocalKeys)
		assertNull(keyEvidence.projection)
		db.getBookmarksDao().insert(bookmark)
		db.getHistoryDao().insert(history)
		val unknownId = legacy.id xor 123456789L
		assertFalse(unknownId in setOf(0L, first.id, second.id, legacy.id))
		settings.setChapterReadOverrides(manga.id, mapOf(legacy.id to true, unknownId to false))
		val extra = File(container, "Chapter 0.cbz").apply { writeBytes(bytes) }
		val reparsed = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty()
			.single { it.title == legacy.title }
		assertNotEquals(legacy.id, reparsed.id)
		val unresolved = resolve()
		assertEquals(container, unresolved.uniqueCandidate?.file)
		val idEvidence = requireNotNull(unresolved.directoryIdentity)
		assertEquals(legacy.id, idEvidence.evidence.verified.single().id)
		assertEquals(setOf(unknownId), idEvidence.evidence.unresolvedIds)
		assertNull(idEvidence.projection)
		assertEquals(mapOf(legacy.id to true, unknownId to false), settings.getChapterReadOverrides(manga.id))
		// Explicit fixture correction only; discovery must never rewrite unknown references.
		settings.setChapterReadOverrides(manga.id, mapOf(legacy.id to true))
		suspend fun assertReconciled() {
			val result = resolve()
			assertEquals(container, result.uniqueCandidate?.file)
			val identity = requireNotNull(result.directoryIdentity)
			assertTrue(identity.evidence.unresolvedLocalKeys.isEmpty())
			assertTrue(identity.evidence.unresolvedIds.isEmpty())
			val chapters = requireNotNull(identity.projection).manga.chapters.orEmpty()
			assertTrue(chapters.any { it.id == second.id })
			assertFalse(chapters.any { it.id == first.id })
			val restored = chapters.single { it.id == legacy.id }
			assertEquals(key, ChapterPersonalKey.of(restored))
			assertEquals(page.url, local.getPages(restored).first().url)
		}
		assertReconciled()
		assertTrue(extra.delete())
		assertReconciled()
		// The same durable evidence must be applied to a direct indexed layout, too.
		val sidecar = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		sidecar.writeText(MangaIndex(null).apply {
			setMangaInfo(manga)
			addChapter(IndexedValue(1, second), sibling.name)
		}.toString())
		val indexBytes = sidecar.readBytes()
		assertReconciled()
		assertArrayEquals(indexBytes, sidecar.readBytes())
		assertEquals(listOf(personal), db.getChapterPersonalDao().findAll(listOf(manga.id)))
		assertEquals(listOf(progress), db.getChapterProgressDao().findAll(listOf(manga.id), FavouriteSpace.NORMAL.dbValue))
		assertEquals(listOf(bookmark), db.getBookmarksDao().findAll(manga.id))
		assertEquals(history, db.getHistoryDao().findIncludingDeleted(manga.id))
		assertEquals(mapOf(legacy.id to true), settings.getChapterReadOverrides(manga.id))
		assertEquals(owner, ownerDao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertArrayEquals(bytes, special.readBytes())
		assertArrayEquals(bytes, sibling.readBytes())
	}

	/** T01/T05/T10: migrated root archive keeps the real embedded Reader selector, not a re-keyed ID. */
	@Test fun normalScannerMigratedArchiveRetainsExactEmbeddedReadSelectors() = runBlocking {
		val seed = remote(810_104L)
		val oldSource = MissingMangaSource("MIHON_2677079941490683989")
		val first = seed.chapters.orEmpty().single().copy(source = oldSource, url = "/chapter/1")
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2", number = 2f)
		val old = seed.copy(source = oldSource, chapters = listOf(first, second))
		val targetSource = MissingMangaSource("TSUKI:MIYORARE:miyorare-id:BACAMI")
		val newFirst = first.copy(id = first.id + 10, source = targetSource)
		val newSecond = second.copy(id = second.id + 10, source = targetSource)
		val target = old.copy(id = 810_105L, source = targetSource, chapters = listOf(newFirst, newSecond))
		ids += target.id
		data.storeManga(old, replaceExisting = true)
		favourite(target)
		val image = File(root, "migrated-archive.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val output = LocalMangaOutput.getOrCreate(root, old, DownloadFormat.SINGLE_CBZ)
		val archive = output.rootFile.canonicalFile
		try {
			output.addPage(IndexedValue(0, first), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(first))
			output.addPage(IndexedValue(1, second), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(second))
			output.finish()
		} finally { output.close() }
		val bytes = archive.readBytes()
		val original = LocalMangaParser(archive).getManga(withDetails = true).manga.chapters.orEmpty()
		val originalPages = original.associate { it.id to local.getPages(it).single().url }
		assertEquals(2, originalPages.values.toSet().size)
		index.rememberVerifiedPath(old.id, archive)
		requireNotNull(index.prepareDownloadIdentityMove(old.id, target.id,
			mapOf(first.id to newFirst.id, second.id to newSecond.id)))
		val alias = requireNotNull(index.getDownloadAliasForScanner(target.id))
		suspend fun resolve(manga: Manga = target) =
			requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL))
		val prepared = resolve()
		assertNull(prepared.uniqueCandidate)
		assertNull(prepared.migratedArchiveIdentity)
		assertTrue(archive in prepared.unresolvedPaths)
		val owner = FavouriteDownloadIndexEntity(target.id, FavouriteSpace.NORMAL.dbValue, archive.path)
		val dao = db.getFavouriteDownloadIndexDao()
		dao.upsert(owner)
		val verified = resolve()
		assertEquals(archive, verified.uniqueCandidate?.file)
		assertNull(verified.directoryIdentity)
		val identity = requireNotNull(verified.migratedArchiveIdentity)
		assertEquals(setOf(newFirst.id, newSecond.id), identity.selectorsByRemoteId.keys)
		assertEquals(setOf(first.id, second.id), identity.physical.manga.chapters.orEmpty().map { it.id }.toSet())
		for ((oldChapter, targetChapter) in listOf(first to newFirst, second to newSecond)) {
			val selector = identity.selectorsByRemoteId.getValue(targetChapter.id)
			assertEquals(oldChapter.id, selector.id)
			assertEquals(archive.path, android.net.Uri.parse(selector.url).path)
			assertEquals(originalPages.getValue(oldChapter.id), local.getPages(selector).single().url)
		}
		// The direct archive API cannot silently expose a migrated projection without its mapping.
		assertNull(normalDirectoryDiscovery.verifyArchiveCandidate(target, FavouriteSpace.NORMAL, archive))
		assertNull(resolve(target.copy(chapters = listOf(newFirst.copy(url = "/wrong"), newSecond))).migratedArchiveIdentity)
		assertNull(resolve(target.copy(url = "/wrong-manga")).uniqueCandidate)
		val competing = File(archive.parentFile, archive.nameWithoutExtension + "_8.cbz")
		archive.copyTo(competing)
		val multiple = resolve()
		assertNull(multiple.uniqueCandidate)
		assertNull(multiple.migratedArchiveIdentity)
		assertTrue(competing in multiple.unresolvedPaths)
		assertTrue(competing.delete())
		val staging = File(archive.path + ".bak.tmp").apply { writeBytes(bytes) }
		assertNull(resolve().uniqueCandidate)
		assertNull(resolve().migratedArchiveIdentity)
		assertTrue(staging.delete())
		assertNotNull(resolve().migratedArchiveIdentity)
		assertEquals(alias, index.getDownloadAliasForScanner(target.id))
		assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, target.id))
		assertArrayEquals(bytes, archive.readBytes())
	}

	/** T01/T05/T10: non-registry writer path needs independent durable locator evidence. */
	@Test fun normalScannerNonRegistryManualRequiresTypedSourceAndSurvivingPriorLocator() = runBlocking {
		val seed = remote(810_106L)
		val source = MihonMangaSource(
			catalogueSource = object : CatalogueSource {
				override val id = 810_106_001L
				override val name = "Scanner Fixture Provider"
				override val lang = "en"
				override val supportsLatest = false
			},
			pkgName = "test.extension.en.scannerfixture",
		)
		val first = seed.chapters.orEmpty().single().copy(source = source, number = 0f)
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2")
		val manga = seed.copy(source = source, chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, manga.copy(chapters = listOf(first))).file.canonicalFile
		assertEquals(File(root, "downloads/Scanner Fixture Provider (EN)/${manga.title}"), container)
		val original = File(container, "Chapter 1.cbz")
		val bytes = original.readBytes()
		val sibling = File(container, "Chapter 2.cbz")
		original.copyTo(sibling)
		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(original.renameTo(special))
		assertFalse(File(container, LocalMangaOutput.ENTRY_NAME_INDEX).exists())
		val ownerDao = db.getFavouriteDownloadIndexDao()
		suspend fun verify(candidate: Manga = manga) =
			normalDirectoryDiscovery.verifySidecarFreeCandidate(candidate, FavouriteSpace.NORMAL, container)
		// A real writer path and canonical sibling alone do not identify the provider.
		assertNull(verify())
		val legacy = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty()
			.single { it.title == "Chapter 1 Special" }
		val key = ChapterPersonalKey.of(legacy)
		val page = local.getPages(legacy).first()
		val personal = ChapterPersonalEntity(manga.id, key.source, key.url, 4, "Manual non-registry prior")
		val bookmark = BookmarkEntity(manga.id, page.id, legacy.id, 0, 0, page.url, 100L, 0.5f)
		db.getChapterPersonalDao().upsert(personal)
		assertNull(verify())
		db.getBookmarksDao().insert(bookmark)
		val result = requireNotNull(verify())
		val chapters = result.manga.chapters.orEmpty()
		assertTrue(chapters.any { it.id == second.id })
		assertFalse(chapters.any { it.id == first.id })
		val restored = chapters.single { it.id == legacy.id }
		assertEquals(key, ChapterPersonalKey.of(restored))
		assertEquals(page.url, local.getPages(restored).first().url)
		val resolution = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL))
		assertEquals(container, resolution.uniqueCandidate?.file)
		assertEquals(legacy.id, requireNotNull(resolution.directoryIdentity?.projection).manga.chapters.orEmpty()
			.single { it.url == key.url }.id)
		// Renamed-only is also eligible with the same surviving corroborated locator.
		assertTrue(sibling.delete())
		assertEquals(legacy.id, requireNotNull(verify()).manga.chapters.orEmpty().single().id)
		assertTrue(special.delete())
		sibling.writeBytes(bytes)
		assertNull(verify()) // a stale prior locator does not prove the surviving canonical sibling
		special.writeBytes(bytes)
		assertNotNull(verify())
		// Missing-source cached title is not typed source metadata, even with an exact hint.
		index.rememberVerifiedPath(manga.id, container)
		assertNull(verify(manga.copy(source = MissingMangaSource(source.name, source.displayName))))
		val foreign = remote(810_107L).copy(title = manga.title, source = MissingMangaSource("OTHER_FIXTURE_SOURCE"))
		data.storeManga(foreign, replaceExisting = true)
		assertNull(verify())
		assertEquals(listOf(personal), db.getChapterPersonalDao().findAll(listOf(manga.id)))
		assertEquals(listOf(bookmark), db.getBookmarksDao().findAll(manga.id))
		assertNull(ownerDao.findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		assertFalse(File(container, LocalMangaOutput.ENTRY_NAME_INDEX).exists())
		assertArrayEquals(bytes, special.readBytes())
		assertArrayEquals(bytes, sibling.readBytes())
	}

	/** T05 closure: a verified migrated index must not drop corroborated target-local identity. */
	@Test fun normalScannerMigratedIndexedRetainsCorroboratedPriorIdentity() = runBlocking {
		val seed = remote(810_108L)
		val oldSource = MissingMangaSource("MIHON_2677079941490683989")
		val first = seed.chapters.orEmpty().single().copy(source = oldSource, url = "/chapter/1", number = 0f)
		val second = first.copy(id = first.id + 1L, title = "Chapter 2", url = "/chapter/2")
		val old = seed.copy(source = oldSource, chapters = listOf(first, second))
		val targetSource = MissingMangaSource("TSUKI:MIYORARE:miyorare-id:BACAMI")
		val newFirst = first.copy(id = first.id + 10L, source = targetSource)
		val newSecond = second.copy(id = second.id + 10L, source = targetSource)
		val target = old.copy(id = 810_109L, source = targetSource, chapters = listOf(newFirst, newSecond))
		ids += target.id
		data.storeManga(old, replaceExisting = true)
		favourite(target)
		val container = download(root, old.copy(chapters = listOf(first))).file.canonicalFile
		val original = File(container, "Chapter 1.cbz")
		val bytes = original.readBytes()
		val sibling = File(container, "Chapter 2.cbz")
		original.copyTo(sibling)
		val special = File(container, "Chapter 1 Special.cbz")
		assertTrue(original.renameTo(special))
		val legacy = LocalMangaParser(container).getManga(withDetails = true).manga.chapters.orEmpty()
			.single { it.title == "Chapter 1 Special" }
		val key = ChapterPersonalKey.of(legacy)
		val page = local.getPages(legacy).first()
		val sidecar = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		sidecar.writeText(MangaIndex(null).apply {
			setMangaInfo(old)
			addChapter(IndexedValue(0, first), original.name)
			addChapter(IndexedValue(1, second), sibling.name)
		}.toString())
		val indexBytes = sidecar.readBytes()
		index.rememberVerifiedPath(old.id, container)
		requireNotNull(index.prepareDownloadIdentityMove(old.id, target.id,
			mapOf(first.id to newFirst.id, second.id to newSecond.id)))
		val alias = requireNotNull(index.getDownloadAliasForScanner(target.id))
		val owner = FavouriteDownloadIndexEntity(target.id, FavouriteSpace.NORMAL.dbValue, container.path)
		val dao = db.getFavouriteDownloadIndexDao()
		dao.upsert(owner)
		val personal = ChapterPersonalEntity(target.id, key.source, key.url, 4, "Migrated local-only prior")
		val progress = ChapterProgressEntity(target.id, key.source, key.url, FavouriteSpace.NORMAL.dbValue, 1, 0, 2)
		val bookmark = BookmarkEntity(target.id, page.id, legacy.id, 0, 0, page.url, 100L, 0.5f)
		val history = HistoryEntity(target.id, 100L, 200L, legacy.id, 0, 0f, 0.5f, 0L, 1)
		suspend fun resolve() = requireNotNull(normalDirectoryDiscovery.resolveCandidates(target, FavouriteSpace.NORMAL))
		db.getChapterPersonalDao().upsert(personal)
		db.getChapterProgressDao().upsert(progress)
		val keyOnly = resolve()
		assertEquals(container, keyOnly.uniqueCandidate?.file)
		val withheld = requireNotNull(keyOnly.directoryIdentity)
		assertEquals(setOf(key), withheld.evidence.unresolvedLocalKeys)
		assertNull(withheld.projection)
		db.getBookmarksDao().insert(bookmark)
		db.getHistoryDao().insert(history)
		settings.setChapterReadOverrides(target.id, mapOf(legacy.id to true))
		suspend fun assertStable(originalPresent: Boolean) {
			val result = resolve()
			assertEquals(container, result.uniqueCandidate?.file)
			val identity = requireNotNull(result.directoryIdentity)
			assertTrue(identity.evidence.unresolvedIds.isEmpty())
			assertTrue(identity.evidence.unresolvedLocalKeys.isEmpty())
			val chapters = requireNotNull(identity.projection).manga.chapters.orEmpty()
			assertEquals(originalPresent, chapters.any { it.id == newFirst.id })
			assertTrue(chapters.any { it.id == newSecond.id })
			val restored = chapters.single { it.id == legacy.id }
			assertEquals(key, ChapterPersonalKey.of(restored))
			assertEquals(page.url, local.getPages(restored).first().url)
		}
		assertStable(false)
		val unrelated = File(container, "Chapter 0.cbz").apply { writeBytes(bytes) }
		assertStable(false)
		assertTrue(unrelated.delete())
		original.writeBytes(bytes)
		assertStable(true)
		val unknownId = legacy.id xor 123456789L
		assertFalse(unknownId in setOf(0L, legacy.id, newFirst.id, newSecond.id))
		settings.setChapterReadOverrides(target.id, mapOf(legacy.id to true, unknownId to false))
		val unresolved = requireNotNull(resolve().directoryIdentity)
		assertEquals(setOf(unknownId), unresolved.evidence.unresolvedIds)
		assertNull(unresolved.projection)
		settings.setChapterReadOverrides(target.id, mapOf(legacy.id to true))
		assertStable(true)
		assertEquals(listOf(personal), db.getChapterPersonalDao().findAll(listOf(target.id)))
		assertEquals(listOf(progress), db.getChapterProgressDao().findAll(listOf(target.id), FavouriteSpace.NORMAL.dbValue))
		assertEquals(listOf(bookmark), db.getBookmarksDao().findAll(target.id))
		assertEquals(history, db.getHistoryDao().findIncludingDeleted(target.id))
		assertEquals(mapOf(legacy.id to true), settings.getChapterReadOverrides(target.id))
		assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, target.id))
		assertEquals(alias, index.getDownloadAliasForScanner(target.id))
		assertArrayEquals(indexBytes, sidecar.readBytes())
		for (file in listOf(original, sibling, special)) assertArrayEquals(bytes, file.readBytes())
	}

	/** T02 foundation: scoped matching keeps the exact corrupt final artifact, independent of origin. */
	@Test fun normalScannerCorruptFinalArtifactKeepsExactScopedLocatorAcrossOrigins() = runBlocking {
		val seed = remote(810_110L)
		val source = MissingMangaSource("MIHON_2677079941490683989")
		val chapter = seed.chapters.orEmpty().single().copy(source = source, url = "/chapter/1")
		val manual = seed.copy(source = source, chapters = listOf(chapter))
		favourite(manual)
		val manualDir = File(root, "downloads/Bacami (ID)/${manual.title}").apply { check(mkdirs()) }.canonicalFile
		val corrupt = byteArrayOf(1, 2, 3, 4)
		val manualFile = File(manualDir, "Chapter 1.cbz").apply { writeBytes(corrupt) }
		val writer = remote(810_111L)
		favourite(writer)
		val writerDir = download(root, writer).file.canonicalFile
		val writerFile = File(writerDir, "Chapter 1.cbz")
		val dao = db.getFavouriteDownloadIndexDao()
		val owner = FavouriteDownloadIndexEntity(writer.id, FavouriteSpace.NORMAL.dbValue, writerDir.path)
		dao.upsert(owner)
		writerFile.writeBytes(corrupt)
		suspend fun assertCorrupt(manga: Manga, file: File) {
			val result = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL))
			assertEquals(file.parentFile, result.uniqueCandidate?.file)
			val identity = requireNotNull(result.directoryIdentity)
			val selected = requireNotNull(identity.projection).manga.chapters.orEmpty().single()
			assertEquals(manga.chapters.orEmpty().single().id, selected.id)
			assertEquals(file.canonicalPath, android.net.Uri.parse(selected.url).path)
			assertTrue(runCatching { local.getPages(selected) }.getOrNull().isNullOrEmpty())
			assertArrayEquals(corrupt, file.readBytes())
		}
		assertCorrupt(manual, manualFile)
		assertCorrupt(writer, writerFile)
		val sidecar = File(writerDir, LocalMangaOutput.ENTRY_NAME_INDEX)
		sidecar.writeText(MangaIndex(null).apply {
			setMangaInfo(writer)
			addChapter(IndexedValue(0, writer.chapters.orEmpty().single()), writerFile.name)
		}.toString())
		val indexBytes = sidecar.readBytes()
		assertCorrupt(writer, writerFile)
		assertArrayEquals(indexBytes, sidecar.readBytes())
		assertFalse(File(manualDir, LocalMangaOutput.ENTRY_NAME_INDEX).exists())
		assertNull(dao.findEntry(FavouriteSpace.NORMAL.dbValue, manual.id))
		assertEquals(owner, dao.findEntry(FavouriteSpace.NORMAL.dbValue, writer.id))
	}

	private fun observationStore(dao: NormalDownloadObservationDao = db.getNormalDownloadObservationDao()) =
		NormalDownloadObservationStore(db, destinations, normalDownloadScope, normalDirectoryDiscovery, dao)

	private suspend fun observedDownload(id: Long): Pair<Manga, File> {
		val manga = remote(id)
		favourite(manga)
		val file = download(root, manga).file.canonicalFile
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, file.path))
		return manga to file
	}

	/** T12: real store, process-style owner, OFF startup/root/manual, ON resume, no-change. */
	@Test fun normalCoordinatorLifecycleAndNoChange() = runBlocking {
		val (manga, file) = observedDownload(810_201L)
		val store = observationStore()
		val coordinator = NormalDownloadScanCoordinator(db, normalDownloadScope, store, normalDirectoryDiscovery)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val key = NormalDownloadScanCoordinator.KEY_SCAN_ON_RESUME
		val exKey = ExhentaiPresenceDiscovery.KEY_SCAN_ON_RESUME
		val exBefore = prefs.getBoolean(exKey, true)
		prefs.edit(commit = true) { remove(key) }
		assertTrue(prefs.getBoolean(key, true))
		prefs.edit(commit = true) { putBoolean(key, false) }
		val observer = NormalDownloadScanLifecycleObserver(prefs, { coordinator.start(scope) }, coordinator::requestFull)
		lateinit var registry: LifecycleRegistry
		lateinit var owner: LifecycleOwner
		instrumentation.runOnMainSync {
			owner = object : LifecycleOwner { override val lifecycle: Lifecycle get() = registry }
			// Hold the owner strongly throughout the real lifecycle event sequence.
			registry = LifecycleRegistry(owner)
			registry.addObserver(observer)
			registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
			registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
		}
		suspend fun awaitRun(after: Long) = withTimeout(20_000) {
			coordinator.state.first { !it.running && it.completedRuns > after }
		}
		try {
			val startup = awaitRun(0)
			assertEquals(NormalScanOutcome.COMPLETE, startup.outcome)
			assertEquals(file.path, store.readVisible(listOf(manga), FavouriteSpace.NORMAL)[manga.id]?.fact?.path)
			val unchanged = store.changes.value
			instrumentation.runOnMainSync {
				registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
				registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
			}
			assertNull(withTimeoutOrNull(400) { coordinator.state.first { it.completedRuns > startup.completedRuns } })
			coordinator.requestFull()
			val manual = awaitRun(startup.completedRuns)
			assertEquals(NormalScanOutcome.COMPLETE, manual.outcome)
			assertEquals(unchanged, store.changes.value)
			val unavailable = File(root, "unavailable-normal")
			// setRoot provisions a valid destination. Simulate storage disappearing after
			// selection, before the scanner can capture the newly selected root.
			destinations.withDestinationMutationLock {
				destinations.setRoot(FavouriteSpace.NORMAL, unavailable)
				assertTrue(unavailable.isDirectory)
				assertTrue(unavailable.deleteRecursively())
			}
			assertEquals(unavailable, destinations.configuredRoot(FavouriteSpace.NORMAL))
			assertFalse(unavailable.exists())
			val switched = awaitRun(manual.completedRuns)
			assertEquals(NormalScanOutcome.UNAVAILABLE, switched.outcome)
			assertFalse(unavailable.exists())
			assertEquals(NormalObservationStatus.UNAVAILABLE,
				store.readVisible(listOf(manga), FavouriteSpace.NORMAL)[manga.id]?.fact?.status)
			assertTrue(file.exists())
			destinations.setRoot(FavouriteSpace.NORMAL, root)
			val recovered = awaitRun(switched.completedRuns)
			assertEquals(NormalScanOutcome.COMPLETE, recovered.outcome)
			prefs.edit(commit = true) { putBoolean(key, true) }
			instrumentation.runOnMainSync {
				registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
				registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
			}
			assertEquals(NormalScanOutcome.COMPLETE, awaitRun(recovered.completedRuns).outcome)
			prefs.edit(commit = true) { remove(key) }
			val beforeDefaultResume = coordinator.state.value.completedRuns
			instrumentation.runOnMainSync {
				registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
				registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
			}
			assertEquals(NormalScanOutcome.COMPLETE, awaitRun(beforeDefaultResume).outcome)
			assertFalse(prefs.contains(key))
			assertEquals(exBefore, prefs.getBoolean(exKey, true))
		} finally {
			instrumentation.runOnMainSync { registry.removeObserver(observer) }
			scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
		}
	}

	/** T12: manual full arriving during an auto batch is not consumed by that batch. */
	@Test fun normalCoordinatorPreservesManualDuringAuto() = runBlocking {
		val (manga, _) = observedDownload(810_202L)
		val reached = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val realDao = db.getNormalDownloadObservationDao()
		val store = observationStore(object : NormalDownloadObservationDao by realDao {
			override suspend fun upsert(row: NormalDownloadObservationEntity) {
				reached.complete(Unit)
				release.await()
				realDao.upsert(row)
			}
		})
		val coordinator = NormalDownloadScanCoordinator(db, normalDownloadScope, store, normalDirectoryDiscovery)
		val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		try {
			coordinator.start(process)
			withTimeout(20_000) { reached.await() }
			assertTrue(coordinator.state.value.running)
			coordinator.requestFull()
			coordinator.requestFull()
			release.complete(Unit)
			val finished = withTimeout(20_000) {
				coordinator.state.first { !it.running && it.completedRuns >= 2L }
			}
			assertEquals(NormalScanOutcome.COMPLETE, finished.outcome)
			assertEquals(1, finished.processed)
			assertEquals(NormalObservationStatus.PRESENT,
				store.readVisible(listOf(manga), FavouriteSpace.NORMAL)[manga.id]?.fact?.status)
			assertNull(withTimeoutOrNull(400) { coordinator.state.first { it.completedRuns > finished.completedRuns } })
		} finally {
			release.complete(Unit)
			process.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
		}
	}

	/** T08/T09: shared batch proof may not survive another producer or contradictory owner. */
	@Test fun normalCoordinatorBatchRejectsChangedProofAndProducer() = runBlocking {
		val (first, file) = observedDownload(810_203L)
		val (second, _) = observedDownload(810_204L)
		val store = observationStore()
		val titles = listOf(first, second)
		val stale = requireNotNull(store.prepareBatch(titles))
		val token = store.beginMutation(second.id)
		store.finishMutation(second.id, token)
		assertTrue(store.publishBatch(stale).isEmpty())
		assertTrue(store.readVisible(titles, FavouriteSpace.NORMAL).isEmpty())
		val rootAba = requireNotNull(store.prepareBatch(titles))
		destinations.setRoot(FavouriteSpace.NORMAL, File(root, "batch-new-normal"))
		destinations.setRoot(FavouriteSpace.NORMAL, root)
		assertTrue(store.publishBatch(rootAba).isEmpty())
		assertTrue(store.readVisible(titles, FavouriteSpace.NORMAL).isEmpty())
		val proof = requireNotNull(store.prepareBatch(titles))
		val foreign = FavouriteDownloadIndexEntity(second.id, FavouriteSpace.PRIVATE.dbValue, file.path)
		db.getFavouriteDownloadIndexDao().upsert(foreign)
		assertTrue(store.publishBatch(proof).isEmpty())
		assertTrue(store.readVisible(titles, FavouriteSpace.NORMAL).isEmpty())
		val current = store.publishBatch(requireNotNull(store.prepareBatch(titles)))
		assertEquals(NormalObservationStatus.UNKNOWN, current[first.id])
		assertEquals(NormalObservationStatus.PRESENT, current[second.id])
		assertTrue(file.exists())
		assertEquals(foreign, db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.PRIVATE.dbValue, second.id))
	}

	/** T12: failed and cancelled work have truthful terminal state and manual retry. */
	@Test fun normalCoordinatorReportsFailureAndCancellation() = runBlocking {
		observedDownload(810_205L)
		val realDao = db.getNormalDownloadObservationDao()
		var fail = true
		val failedStore = observationStore(object : NormalDownloadObservationDao by realDao {
			override suspend fun upsert(row: NormalDownloadObservationEntity) {
				if (fail) {
					fail = false
					throw java.io.IOException("Controlled scanner write failure")
				}
				realDao.upsert(row)
			}
		})
		val failed = NormalDownloadScanCoordinator(db, normalDownloadScope, failedStore, normalDirectoryDiscovery)
		val failureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		try {
			failed.start(failureScope)
			val first = withTimeout(20_000) { failed.state.first { !it.running && it.completedRuns > 0 } }
			assertEquals(NormalScanOutcome.FAILED, first.outcome)
			failed.requestFull()
			val recovered = withTimeout(20_000) { failed.state.first { !it.running && it.completedRuns > first.completedRuns } }
			assertEquals(NormalScanOutcome.COMPLETE, recovered.outcome)
		} finally {
			failureScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
		}
		val reached = CompletableDeferred<Unit>()
		val cancelledStore = observationStore(object : NormalDownloadObservationDao by realDao {
			override suspend fun upsert(row: NormalDownloadObservationEntity) {
				reached.complete(Unit)
				CompletableDeferred<Unit>().await()
			}
		})
		val cancelled = NormalDownloadScanCoordinator(db, normalDownloadScope, cancelledStore, normalDirectoryDiscovery)
		val cancellationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		try {
			cancelled.start(cancellationScope)
			withTimeout(20_000) { reached.await() }
			assertTrue(cancelled.state.value.running)
			cancellationScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
			assertEquals(NormalScanOutcome.CANCELLED, cancelled.state.value.outcome)
			assertFalse(cancelled.state.value.running)
		} finally {
			cancellationScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
		}
	}

	/** Early T14 probe: comparable sequential vs batched observation work, 1,024 real Room titles.
	 * No arbitrary speed threshold; physical first-render/scroll still needs owner evidence.
	 */
	@Test fun normalCoordinatorLargeLibraryWorkProbe() = runBlocking<Unit> {
		val titles = (0 until 1024).map { remote(820_000L + it) }
		val category = repository.createCategory("Normal scanner performance fixture", ListSortOrder.NEWEST, false, false, true, FavouriteSpace.NORMAL)
		for (chunk in titles.chunked(128)) {
			for (manga in chunk) data.storeManga(manga, replaceExisting = true)
			repository.updateCategoryMemberships(chunk, mapOf(category.id to true), FavouriteSpace.NORMAL)
		}
		// Real populated download parent, one positive and remaining absent titles.
		val file = download(root, titles.first()).file.canonicalFile
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(titles.first().id, FavouriteSpace.NORMAL.dbValue, file.path))
		val realDao = db.getNormalDownloadObservationDao()
		val writes = java.util.concurrent.atomic.AtomicInteger()
		fun countedStore() = observationStore(object : NormalDownloadObservationDao by realDao {
			override suspend fun upsert(row: NormalDownloadObservationEntity) {
				writes.incrementAndGet()
				realDao.upsert(row)
			}
		})
		val baseline = countedStore()
		val space = FavouriteSpace.NORMAL
		suspend fun sequential(): Long {
			val start = android.os.SystemClock.elapsedRealtime()
			for (manga in titles) assertTrue(baseline.scan(manga, space))
			return android.os.SystemClock.elapsedRealtime() - start
		}
		val baselineInitial = sequential()
		assertEquals(titles.size, writes.getAndSet(0))
		val baselineChanges = baseline.changes.value
		val baselineUnchanged = sequential()
		assertEquals(0, writes.getAndSet(0))
		assertEquals(baselineChanges, baseline.changes.value)
		val batched = countedStore()
		val coordinator = NormalDownloadScanCoordinator(db, normalDownloadScope, batched, normalDirectoryDiscovery)
		val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		suspend fun awaitRun(after: Long) = withTimeout(180_000) {
			coordinator.state.first { !it.running && it.completedRuns > after }
		}
		try {
			val start = android.os.SystemClock.elapsedRealtime()
			coordinator.start(process)
			val initial = awaitRun(0)
			val batchInitial = android.os.SystemClock.elapsedRealtime() - start
			assertEquals(NormalScanOutcome.COMPLETE, initial.outcome)
			assertEquals(titles.size, initial.processed)
			assertEquals(titles.size, writes.getAndSet(0))
			val revision = batched.changes.value
			val unchangedStart = android.os.SystemClock.elapsedRealtime()
			coordinator.requestFull()
			val unchanged = awaitRun(initial.completedRuns)
			val batchUnchanged = android.os.SystemClock.elapsedRealtime() - unchangedStart
			assertEquals(NormalScanOutcome.COMPLETE, unchanged.outcome)
			assertEquals(titles.size, unchanged.processed)
			assertEquals(0, writes.getAndSet(0))
			assertEquals(revision, batched.changes.value)
			val visible = batched.readVisible(titles, space)
			assertEquals(titles.size, visible.size)
			assertEquals(file.path, visible[titles.first().id]?.fact?.path)
			assertEquals(titles.size - 1, visible.values.count { it.fact.status == NormalObservationStatus.ABSENT })
			android.util.Log.i("NormalScannerPerf", "titles=${titles.size} sequential_initial_ms=$baselineInitial sequential_unchanged_ms=$baselineUnchanged batched_initial_ms=$batchInitial batched_unchanged_ms=$batchUnchanged initial_writes=${titles.size} unchanged_writes=0")
		} finally {
			process.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
		}
	}

	/** T12: metadata scope changes trigger work even without a foreground/manual request. */
	@Test fun normalCoordinatorObservesMembershipAndProducerChanges() = runBlocking {
		val (first, _) = observedDownload(810_206L)
		val store = observationStore()
		val coordinator = NormalDownloadScanCoordinator(db, normalDownloadScope, store, normalDirectoryDiscovery)
		val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val space = FavouriteSpace.NORMAL
		try {
			coordinator.start(process)
			val startup = withTimeout(20_000) { coordinator.state.first { !it.running && it.completedRuns > 0 } }
			assertEquals(NormalScanOutcome.COMPLETE, startup.outcome)
			val (second, file) = observedDownload(810_207L)
			withTimeout(30_000) {
				while (store.readVisible(listOf(second), space)[second.id]?.fact?.path != file.path) {
					kotlinx.coroutines.delay(50)
				}
			}
			val token = store.beginMutation(second.id)
			try {
				assertTrue(file.deleteRecursively())
				assertNull(store.readVisible(listOf(second), space)[second.id])
			} finally {
				store.finishMutation(second.id, token)
			}
			withTimeout(30_000) {
				while (store.readVisible(listOf(second), space)[second.id]?.fact?.status != NormalObservationStatus.ABSENT) {
					kotlinx.coroutines.delay(50)
				}
			}
			assertEquals(NormalObservationStatus.PRESENT, store.readVisible(listOf(first), space)[first.id]?.fact?.status)
		} finally {
			process.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
		}
	}

	/** Real Activity/RecyclerView, actual production observation singleton; no artificial scan delay. */
	@Test fun normalCoordinatorActualFavoritesTimingAndIcons() = runBlocking<Unit> {
		val space = FavouriteSpace.NORMAL
		val (categoryId, titles) = scannerUiLibrary(1024, 830_000L)
		val manga = titles.last()
		val file = scannerUiDownload(manga)
		// Real parent inventory with positive and negative candidates.
		for (item in titles.take(31)) scannerUiDownload(item)
		val iconCategory = repository.createCategory("Scanner UI icons", ListSortOrder.NEWEST, false, false, true, space)
		val negative = titles[titles.lastIndex - 1]
		repository.updateCategoryMemberships(listOf(manga, negative), mapOf(iconCategory.id to true), space)
		settings.isOnboardingCompleted = true
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		display.setListMode(FavouriteContentType.MANGA, ListMode.GRID)
		val coordinator = scannerUiCoordinatorProvider.get()
		val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val intent = Intent(context, FavouritesActivity::class.java)
			.putExtra(AppRouter.KEY_ID, categoryId).putExtra(EXTRA_FAVOURITE_SPACE, space.dbValue)
		val iconIntent = Intent(context, FavouritesActivity::class.java)
			.putExtra(AppRouter.KEY_ID, iconCategory.id).putExtra(EXTRA_FAVOURITE_SPACE, space.dbValue)
		val overlaps = ArrayList<Boolean>()
		try {
			coordinator.start(process)
			awaitScannerUiRun(coordinator, 0)
			// Same candidate/workload: idle, then actual unchanged scan. Not a prefeature APK baseline.
			for (active in listOf(false, true)) {
				val run = if (active) startScannerUiRun(coordinator) else coordinator.state.value.completedRuns
				val before = SystemClock.elapsedRealtime()
				ActivityScenario.launch<FavouritesActivity>(intent).use { scenario ->
					awaitScannerUi("attached Favorites manga row") {
						var ready = false
						scenario.onActivity { activity ->
							val rv = activity.findViewById<RecyclerView>(R.id.recyclerView)
							val adapter = rv?.adapter as? MangaListAdapter
							ready = rv != null && rv.isShown && rv.childCount > 0 &&
								(0 until rv.childCount).any { index ->
									val child = rv.getChildAt(index)
									adapter?.items?.getOrNull(rv.getChildAdapterPosition(child)) is MangaListModel &&
										child.isShown && child.width > 0 && child.height > 0
								}
						}
						ready
					}
					val visibleMs = SystemClock.elapsedRealtime() - before
					val overlap = scannerUiOverlap(coordinator, run)
					if (active) overlaps += overlap else assertScannerUiIdle(coordinator, run)
					android.util.Log.i("NormalScannerUiPerf", "stage=favorites_visible active=$active overlap=$overlap run=$run titles=1024 elapsed_ms=$visibleMs")
					if (active) awaitScannerUiRun(coordinator, run)
					// Reset the same actual recycler position before each independent scroll sample.
					scenario.onActivity { it.findViewById<RecyclerView>(R.id.recyclerView).scrollToPosition(0) }
					instrumentation.waitForIdleSync()
					val scrollRun = if (active) startScannerUiRun(coordinator) else coordinator.state.value.completedRuns
					val frames = java.util.Collections.synchronizedList(mutableListOf<Long>())
					val dropped = java.util.concurrent.atomic.AtomicInteger()
					val worker = HandlerThread("normal-scanner-ui-frames").apply { start() }
					val handler = Handler(worker.looper)
					val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
						val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
						if (duration > 0) frames.add(duration)
						dropped.addAndGet(lost)
					}
					var window: Window? = null
					var firstPosition = -1
					val scrollStart = SystemClock.elapsedRealtime()
					try {
						scenario.onActivity { activity ->
							val rv = activity.findViewById<RecyclerView>(R.id.recyclerView)
							firstPosition = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
							window = activity.window
							activity.window.addOnFrameMetricsAvailableListener(listener, handler)
							rv.smoothScrollBy(0, rv.height * 3)
						}
						awaitScannerUi("actual scroll completed") {
							var done = false
							scenario.onActivity { activity ->
								val rv = activity.findViewById<RecyclerView>(R.id.recyclerView)
								done = rv.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
									(rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() > firstPosition
							}
							done
						}
						val scrollOverlap = scannerUiOverlap(coordinator, scrollRun)
						if (active) overlaps += scrollOverlap else assertScannerUiIdle(coordinator, scrollRun)
						val scrollElapsed = SystemClock.elapsedRealtime() - scrollStart
						scenario.onActivity { window?.removeOnFrameMetricsAvailableListener(listener); window = null }
						val flushed = java.util.concurrent.CountDownLatch(1)
						handler.post { flushed.countDown() }
						assertTrue(flushed.await(5, java.util.concurrent.TimeUnit.SECONDS))
						val values = synchronized(frames) { frames.sorted() }
						assertTrue("No actual frame samples", values.isNotEmpty())
						val p95 = values[((values.size - 1) * .95).toInt()]
						android.util.Log.i("NormalScannerUiPerf", "stage=favorites_scroll active=$active overlap=$scrollOverlap run=$scrollRun frames=${values.size} dropped=${dropped.get()} p95_ns=$p95 max_ns=${values.last()} elapsed_ms=$scrollElapsed")
					} finally {
						scenario.onActivity { window?.removeOnFrameMetricsAvailableListener(listener) }
						worker.quitSafely()
						worker.join(5000)
					}
					if (active) awaitScannerUiRun(coordinator, scrollRun)
				}
			}
			// Actual bound icons, not only adapter booleans: both styles and detailed list/grid.
			for (style in listOf(MiyorareDesignStyle.MODERN, MiyorareDesignStyle.CLASSIC)) {
				settings.setMiyorareDesignStyle(style)
				for (mode in listOf(ListMode.GRID, ListMode.DETAILED_LIST)) {
					display.setListMode(FavouriteContentType.MANGA, mode)
					ActivityScenario.launch<FavouritesActivity>(iconIntent).use { scenario ->
						assertScannerUiCover(scenario, manga.id, true)
						assertScannerUiCover(scenario, negative.id, false)
						if (style == MiyorareDesignStyle.MODERN && mode == ListMode.GRID) {
							val chapterFile = requireNotNull(file.listFiles()).single { it.extension == "cbz" }
							val bytes = chapterFile.readBytes()
							assertTrue(chapterFile.delete())
							val after = coordinator.state.value.completedRuns
							val changedAt = SystemClock.elapsedRealtime()
							coordinator.requestFull()
							awaitScannerUiRun(coordinator, after)
							assertScannerUiCover(scenario, manga.id, false)
							android.util.Log.i("NormalScannerUiPerf", "stage=favorites_changed_icon elapsed_ms=${SystemClock.elapsedRealtime() - changedAt}")
							assertTrue(file.isDirectory)
							chapterFile.writeBytes(bytes)
							val restored = coordinator.state.value.completedRuns
							coordinator.requestFull()
							awaitScannerUiRun(coordinator, restored)
							assertScannerUiCover(scenario, manga.id, true)
						}
					}
				}
			}
			// A missed natural overlap is incomplete evidence, never a passing active measurement.
			assertEquals("Natural scan overlap missing: $overlaps", listOf(true, true), overlaps)
		} finally {
			process.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
		}
	}

	/** Real Details Activity, inline Compose card and actual chapter sheet adapters. */
	@OptIn(ExperimentalTestApi::class)
	@Test fun normalCoordinatorActualDetailsChapterModes() = runComposeUiTest {
		val ui = this
		runBlocking<Unit> {
			val space = FavouriteSpace.NORMAL
			val seed = remote(831_100L)
			val first = seed.chapters.orEmpty().single().copy(title = "Chapter 0", number = 0f, branch = null)
			val second = first.copy(id = first.id + 1, title = "Chapter 2", number = 2f, url = "/chapter/2")
			val manga = seed.copy(chapters = listOf(first, second))
			favourite(manga)
			data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			val container = download(root, manga.copy(chapters = listOf(first))).file.canonicalFile
			val original = requireNotNull(container.listFiles()).single { it.extension == "cbz" }
			val bytes = original.readBytes()
			val sibling = File(container, "Chapter 2.cbz").apply { writeBytes(bytes) }
			val specialFile = File(container, "Chapter 0 Special.cbz")
			assertTrue(original.renameTo(specialFile))
			assertFalse(File(container, LocalMangaOutput.ENTRY_NAME_INDEX).exists())
			db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(manga.id, space.dbValue, container.path))
			scannerUiLibrary(1024, 832_000L)
			PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
				remove("details_chapter_options_v1_manga_normal")
				putString("details_chapter_options_v1_manga_normal_grouping", ChapterGroupingMode.OFF.name)
			}
			settings.isOnboardingCompleted = true
			settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
			val coordinator = scannerUiCoordinatorProvider.get()
			val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			try {
				coordinator.start(process)
				awaitScannerUiRun(coordinator, 0)
				val overlaps = ArrayList<Boolean>()
				for (active in listOf(false, true)) {
					val run = if (active) startScannerUiRun(coordinator) else coordinator.state.value.completedRuns
					val started = SystemClock.elapsedRealtime()
					ActivityScenario.launch<DetailsExpressiveActivity>(AppRouter.detailsIntent(context, manga.id, space)).use { scenario ->
						lateinit var vm: DetailsViewModel
						scenario.onActivity { vm = ViewModelProvider(it)[DetailsViewModel::class.java] }
						val rows = withTimeout(30_000) { vm.chapters.first { rows ->
							rows.size == 3 && rows.any { it.chapter.id == first.id && !it.isDownloaded } &&
								rows.any { it.chapter.id == second.id && it.isDownloaded } &&
								rows.any { it.chapter.id !in setOf(first.id, second.id) && it.isDownloaded }
						} }
						val special = rows.single { it.chapter.id !in setOf(first.id, second.id) }
						assertNotEquals(ChapterPersonalKey.of(first), special.personalKey)
						// Scope the icon to the clickable row containing the exact chapter title.
						fun inline(row: ChapterListItem, saved: Boolean) {
							val title = hasText(row.getTitle(context.resources))
							ui.onNode(hasScrollToIndexAction()).performScrollToNode(title)
							val card = hasClickAction() and hasAnyDescendant(title)
							val expected = hasContentDescription(context.getString(if (saved) R.string.downloaded else R.string.download))
							val opposite = hasContentDescription(context.getString(if (saved) R.string.download else R.string.downloaded))
							ui.onNode(expected and hasAnyAncestor(card), useUnmergedTree = true).assertIsDisplayed()
							ui.onNode(opposite and hasAnyAncestor(card), useUnmergedTree = true).assertDoesNotExist()
						}
						inline(rows.single { it.chapter.id == first.id }, false)
						val overlap = scannerUiOverlap(coordinator, run)
						if (active) overlaps += overlap else assertScannerUiIdle(coordinator, run)
						android.util.Log.i("NormalScannerUiPerf", "stage=details_chapter_visible active=$active overlap=$overlap run=$run titles=1025 elapsed_ms=${SystemClock.elapsedRealtime() - started}")
						if (active) awaitScannerUiRun(coordinator, run)
						inline(rows.single { it.chapter.id == second.id }, true)
						inline(special, true)
						lateinit var sheet: ChaptersPagesSheet
						scenario.onActivity {
							vm.setChapterGroupingMode(ChapterGroupingMode.OFF)
							sheet = ChaptersPagesSheet().apply { arguments = Bundle().apply { putInt(AppRouter.KEY_TAB, 0) } }
							sheet.showNow(it.supportFragmentManager, "normal-scanner-real-chapters")
						}
						for (grid in listOf(false, true)) {
							scenario.onActivity { vm.setChaptersGridView(grid) }
							for (row in rows) assertScannerUiChapter(scenario, sheet, row.chapter.id, row.isDownloaded, grid)
						}
						if (active) {
							assertTrue(specialFile.delete())
							val changed = coordinator.state.value.completedRuns
							coordinator.requestFull()
							awaitScannerUiRun(coordinator, changed)
							withTimeout(30_000) { vm.chapters.first { current ->
								current.none { it.chapter.id == special.chapter.id && it.isDownloaded } &&
									current.any { it.chapter.id == second.id && it.isDownloaded }
							} }
							assertScannerUiChapter(scenario, sheet, first.id, false, true)
							assertScannerUiChapter(scenario, sheet, second.id, true, true)
							assertTrue(sibling.exists())
						}
					}
				}
				assertEquals("Natural Details overlap missing", listOf(true), overlaps)
			} finally {
				process.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
			}
		}
	}

	/** Actual Downloads Settings renders in-flight progress and terminal state on a real workload. */
	@OptIn(ExperimentalTestApi::class)
	@Test fun normalCoordinatorActualSettingsProgress() = runComposeUiTest {
		val ui = this
		runBlocking<Unit> {
			scannerUiLibrary(1024, 834_000L)
			settings.isOnboardingCompleted = true
			val coordinator = scannerUiCoordinatorProvider.get()
			val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			val intent = Intent(context, SettingsActivity::class.java).setAction(AppRouter.ACTION_MANAGE_DOWNLOADS)
			try {
				ActivityScenario.launch<SettingsActivity>(intent).use {
					val action = hasText(context.getString(R.string.normal_download_full_scan))
					ui.onNode(hasScrollToIndexAction()).performScrollToNode(action)
					ui.onNode(action).assertIsDisplayed()
					coordinator.start(process)
					val prefix = context.getString(R.string.normal_download_scan_progress, 0, 1024).substringBefore("0")
					assertTrue(prefix.isNotBlank())
					var observedProgress: NormalDownloadScanState? = null
					ui.waitUntil(timeoutMillis = 30_000L) {
						val before = coordinator.state.value
						val visible = before.running && before.total == 1024 &&
							ui.onAllNodes(hasText(prefix, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
						val after = coordinator.state.value
						if (visible && after.running && before.completedRuns == after.completedRuns) {
							observedProgress = after
							true
						} else false
					}
					val progress = requireNotNull(observedProgress)
					android.util.Log.i("NormalScannerUiPerf", "stage=settings_progress processed=${progress.processed} total=${progress.total}")
					val result = awaitScannerUiRun(coordinator, progress.completedRuns)
					ui.onNode(hasText(context.getString(R.string.normal_download_scan_complete, result.processed, result.total)),
						useUnmergedTree = true).assertIsDisplayed()
				}
			} finally {
				process.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
			}
		}
	}

	private suspend fun scannerUiLibrary(count: Int, startId: Long): Pair<Long, List<Manga>> {
		val space = FavouriteSpace.NORMAL
		val category = repository.createCategory("Scanner UI $startId", ListSortOrder.NEWEST, false, false, true, space)
		val titles = (0 until count).map { remote(startId + it) }
		for (chunk in titles.chunked(128)) {
			for (manga in chunk) data.storeManga(manga, replaceExisting = true, detailsFetched = true)
			repository.updateCategoryMemberships(chunk, mapOf(category.id to true), space)
		}
		return category.id to titles
	}

	private suspend fun scannerUiDownload(manga: Manga): File {
		val file = download(root, manga).file.canonicalFile
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, file.path))
		return file
	}

	private suspend fun awaitScannerUiRun(coordinator: NormalDownloadScanCoordinator, after: Long): NormalDownloadScanState {
		val state = withTimeout(120_000) { coordinator.state.first { !it.running && it.completedRuns > after } }
		assertEquals(NormalScanOutcome.COMPLETE, state.outcome)
		assertEquals(state.total, state.processed)
		return state
	}

	private suspend fun startScannerUiRun(coordinator: NormalDownloadScanCoordinator): Long {
		val run = coordinator.state.value.completedRuns
		coordinator.requestFull()
		withTimeout(30_000) { coordinator.state.first { it.running && it.completedRuns == run && it.total >= 1024 } }
		return run
	}

	private fun scannerUiOverlap(coordinator: NormalDownloadScanCoordinator, run: Long): Boolean =
		coordinator.state.value.let { it.running && it.completedRuns == run }

	private fun assertScannerUiIdle(coordinator: NormalDownloadScanCoordinator, run: Long) {
		val state = coordinator.state.value
		assertTrue("Idle sample overlapped a scanner run: $state", !state.running && state.completedRuns == run)
	}

	private suspend fun awaitScannerUi(label: String, predicate: () -> Boolean) {
		assertTrue(label, withTimeoutOrNull(30_000) {
			while (!predicate()) delay(50)
			true
		} == true)
	}

	private suspend fun assertScannerUiCover(scenario: ActivityScenario<FavouritesActivity>, id: Long, saved: Boolean) {
		awaitScannerUi("actual Favorites icon id=$id saved=$saved") {
			var matched = false
			scenario.onActivity { activity ->
				val rv = activity.findViewById<RecyclerView>(R.id.recyclerView) ?: return@onActivity
				val adapter = rv.adapter as? MangaListAdapter ?: return@onActivity
				val position = adapter.items.indexOfFirst { item ->
					when (item) {
						is MangaGridModel -> item.id == id && item.isSaved == saved
						is MangaDetailedListModel -> item.id == id && item.isSaved == saved
						else -> false
					}
				}
				if (position < 0) return@onActivity
				val holder = rv.findViewHolderForAdapterPosition(position)
				if (holder == null) { rv.scrollToPosition(position); return@onActivity }
				val view = holder.itemView
				val modern = view.findViewById<ImageView>(R.id.cover_downloaded)
				val legacy = view.findViewById<ImageView>(R.id.status_downloaded)
				val icons = listOfNotNull(modern, legacy)
				matched = view.isShown && icons.isNotEmpty() && icons.any { it.isShown && it.drawable != null && it.width > 0 && it.height > 0 } == saved
			}
			matched
		}
	}

	private suspend fun assertScannerUiChapter(
		scenario: ActivityScenario<DetailsExpressiveActivity>, sheet: ChaptersPagesSheet,
		id: Long, saved: Boolean, grid: Boolean,
	) {
		awaitScannerUi("actual chapter icon id=$id saved=$saved grid=$grid") {
			var matched = false
			scenario.onActivity {
				val rv = sheet.view?.findViewById<RecyclerView>(R.id.recyclerView_chapters) ?: return@onActivity
				if ((rv.layoutManager is GridLayoutManager) != grid) return@onActivity
				val adapter = rv.adapter as? ChaptersAdapter ?: return@onActivity
				val position = adapter.items.indexOfFirst { it is ChapterListItem && it.chapter.id == id && it.isGrid == grid && it.isDownloaded == saved }
				if (position < 0) return@onActivity
				val holder = rv.findViewHolderForAdapterPosition(position)
				if (holder == null) { rv.scrollToPosition(position); return@onActivity }
				val icon = holder.itemView.findViewById<ImageView>(R.id.imageView_downloaded) ?: return@onActivity
				matched = holder.itemView.isShown && icon.isShown == saved &&
					(!saved || (icon.drawable != null && icon.width > 0 && icon.height > 0))
			}
			matched
		}
	}

	/** T08: actual destination A -> B -> A invalidates work and cached read snapshots. */
	@Test fun normalObservationRejectsRootAbaAndLateRead() = runBlocking {
		val (manga, file) = observedDownload(810_112L)
		val store = observationStore()
		val space = FavouriteSpace.NORMAL
		suspend fun read() = store.readVisible(listOf(manga), space)[manga.id]
		assertTrue(store.scan(manga, space))
		val previous = requireNotNull(read())
		assertEquals(NormalObservationStatus.PRESENT, previous.fact.status)
		val work = requireNotNull(store.begin(manga, space))
		val result = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space))
		destinations.setRoot(space, File(root, "new-normal"))
		destinations.setRoot(space, root)
		assertFalse(store.publish(work, result))
		assertNull(read())
		assertFalse(store.isCurrent(manga, space, previous))
		assertTrue(store.scan(manga, space))
		assertEquals(file.path, requireNotNull(read()).fact.path)
		assertTrue(store.takeRequests().full)
	}

	/** T09: stale positive and negative, overlapping producer intents, and no lost full request. */
	@Test fun normalObservationRejectsBothMutationDirectionsAndCoalescesRequests() = runBlocking {
		val (manga, file) = observedDownload(810_113L)
		val store = observationStore()
		val space = FavouriteSpace.NORMAL
		val chapter = File(file, "Chapter 1.cbz")
		val bytes = chapter.readBytes()
		suspend fun read() = store.readVisible(listOf(manga), space)[manga.id]
		assertTrue(store.scan(manga, space))
		val positive = requireNotNull(read())
		val stalePositive = requireNotNull(store.begin(manga, space))
		val positiveResult = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space))
		val first = store.beginMutation(manga.id)
		val overlapping = store.beginMutation(manga.id)
		assertNull(read())
		assertNull(store.begin(manga, space))
		assertTrue(file.deleteRecursively())
		store.finishMutation(manga.id, first)
		assertNull(store.begin(manga, space))
		store.finishMutation(manga.id, overlapping)
		assertFalse(store.publish(stalePositive, positiveResult))
		assertFalse(store.isCurrent(manga, space, positive))
		assertTrue(store.scan(manga, space))
		assertEquals(NormalObservationStatus.ABSENT, requireNotNull(read()).fact.status)
		val staleNegative = requireNotNull(store.begin(manga, space))
		val negativeResult = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space))
		val mutation = store.beginMutation(manga.id)
		assertTrue(file.mkdirs())
		chapter.writeBytes(bytes)
		store.finishMutation(manga.id, mutation)
		assertFalse(store.publish(staleNegative, negativeResult))
		assertTrue(store.scan(manga, space))
		assertEquals(NormalObservationStatus.PRESENT, requireNotNull(read()).fact.status)
		assertTrue(store.takeRequests().full)
		store.requestFull()
		assertTrue(store.takeRequests().full)
		assertFalse(store.takeRequests().full)
		assertArrayEquals(bytes, chapter.readBytes())
	}

	/** T08/T13: suspend inside actual Room upsert, switch root before commit and read acceptance. */
	@Test fun normalObservationRootSwitchDuringRoomWriteNeverExposesStaleRow() = runBlocking {
		val (manga, _) = observedDownload(810_114L)
		val realDao = db.getNormalDownloadObservationDao()
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		var written: NormalDownloadObservationEntity? = null
		val store = observationStore(object : NormalDownloadObservationDao by realDao {
			override suspend fun upsert(row: NormalDownloadObservationEntity) {
				realDao.upsert(row)
				written = row
				entered.complete(Unit)
				release.await()
			}
		})
		val space = FavouriteSpace.NORMAL
		val work = requireNotNull(store.begin(manga, space))
		val result = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space))
		val publishing = async { store.publish(work, result) }
		try {
			withTimeout(10_000) { entered.await() }
			destinations.setRoot(space, File(root, "during-write"))
		} finally { release.complete(Unit) }
		assertFalse(publishing.await())
		val row = requireNotNull(written)
		assertEquals(listOf(row), realDao.findCurrent(row.session, row.rootEpoch, listOf(manga.id)))
		assertTrue(store.readVisible(listOf(manga), space).isEmpty())
		destinations.setRoot(space, root)
		assertTrue(store.readVisible(listOf(manga), space).isEmpty())
	}

	/** T08: actual membership removal invalidates an outstanding result and old cached row. */
	@Test fun normalObservationRejectsMembershipChangeBeforePublication() = runBlocking {
		val (manga, _) = observedDownload(810_115L)
		val store = observationStore()
		val space = FavouriteSpace.NORMAL
		assertTrue(store.scan(manga, space))
		val work = requireNotNull(store.begin(manga, space))
		val result = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space))
		val categories = db.getFavouritesDao().findCategoriesIds(manga.id)
		assertTrue(categories.isNotEmpty())
		repository.updateCategoryMemberships(listOf(manga), categories.associateWith { false }, space)
		assertFalse(store.publish(work, result))
		assertTrue(store.readVisible(listOf(manga), space).isEmpty())
		repository.updateCategoryMemberships(listOf(manga), categories.associateWith { true }, space)
		assertTrue(store.readVisible(listOf(manga), space).isEmpty())
		assertTrue(store.scan(manga, space))
		assertEquals(NormalObservationStatus.PRESENT, store.readVisible(listOf(manga), space)[manga.id]?.fact?.status)
	}

	/** T13: old persisted positives are not a restart authority; no-change scan writes nothing. */
	@Test fun normalObservationSessionAndAvailabilityInvalidateWithoutRewritingOldRow() = runBlocking {
		val (manga, _) = observedDownload(810_116L)
		val realDao = db.getNormalDownloadObservationDao()
		var writes = 0
		val store = observationStore(object : NormalDownloadObservationDao by realDao {
			override suspend fun upsert(row: NormalDownloadObservationEntity) {
				writes += 1
				realDao.upsert(row)
			}
		})
		val space = FavouriteSpace.NORMAL
		assertTrue(store.scan(manga, space))
		val positive = requireNotNull(store.readVisible(listOf(manga), space)[manga.id])
		val change = store.changes.value
		assertTrue(store.scan(manga, space))
		assertEquals(1, writes)
		assertEquals(change, store.changes.value)
		assertEquals(positive, store.readVisible(listOf(manga), space)[manga.id])
		val restarted = observationStore()
		assertTrue(restarted.readVisible(listOf(manga), space).isEmpty())
		assertEquals(listOf(positive.row), realDao.findCurrent(positive.row.session, positive.row.rootEpoch, listOf(manga.id)))
		val offline = File(root.parentFile, root.name + "-offline")
		assertTrue(root.renameTo(offline))
		try {
			val unavailable = requireNotNull(store.readVisible(listOf(manga), space)[manga.id])
			assertEquals(NormalObservationStatus.UNAVAILABLE, unavailable.fact.status)
			assertNull(unavailable.fact.path)
			assertFalse(store.isCurrent(manga, space, positive))
			assertEquals(1, writes)
		} finally { assertTrue(offline.renameTo(root)) }
		assertTrue(store.readVisible(listOf(manga), space).isEmpty())
		assertEquals(1, writes)
		assertTrue(store.scan(manga, space))
		assertEquals(2, writes)
		assertEquals(NormalObservationStatus.PRESENT, store.readVisible(listOf(manga), space)[manga.id]?.fact?.status)
	}

	private fun admissionTask(manga: Manga, destination: File?, space: FavouriteSpace = FavouriteSpace.NORMAL) =
		DownloadTask(manga.id, true, true, manga.chapters.orEmpty().map { it.id }.toLongArray(),
			destination, DownloadFormat.MULTIPLE_CBZ, false, space, allowDiagnosticExport = false)

	/** T06/T10: only eligible tasks are pinned; explicit Private and nullable dual remain unchanged. */
	@Test fun normalTaskAdmissionPinsOnlyEligibleAndPreservesMixedSelection() = runBlocking {
		val normal = remote(810_117L)
		val dual = remote(810_118L)
		val privateOnly = remote(810_119L)
		val other = remote(810_120L)
		favourite(normal)
		favourite(dual)
		favourite(dual, FavouriteSpace.PRIVATE)
		favourite(privateOnly, FavouriteSpace.PRIVATE)
		data.storeManga(other, replaceExisting = true)
		val old = File(root, "other-choice").apply { check(mkdirs()) }
		val admission = NormalDownloadTaskAdmission(normalDownloadScope, destinations, context)
		val tasks = listOf(normal to admissionTask(normal, null), dual to admissionTask(dual, old),
			privateOnly to admissionTask(privateOnly, old, FavouriteSpace.PRIVATE), other to admissionTask(other, old))
		val (nullable, routed) = admission.prepare(tasks, null)
		assertEquals(setOf(normal.id), nullable.eligibleIds)
		assertEquals(root, routed[0].second.destination)
		assertEquals(tasks.drop(1), routed.drop(1))
		val pinned = routed[0].second
		assertEquals(pinned, DownloadTask(pinned.toData()))
		assertEquals(tasks[0].second.withDestination(root), pinned)
		val (explicit, routedNormal) = admission.prepare(tasks, FavouriteSpace.NORMAL)
		assertEquals(setOf(normal.id, dual.id), explicit.eligibleIds)
		assertEquals(root, routedNormal[1].second.destination)
		assertEquals(tasks.drop(2), routedNormal.drop(2))
		val (_, routedPrivate) = admission.prepare(tasks, FavouriteSpace.PRIVATE)
		assertEquals(tasks, routedPrivate)
		val categories = db.getFavouritesDao().findCategoriesIds(normal.id)
		repository.updateCategoryMemberships(listOf(normal), categories.associateWith { false }, FavouriteSpace.NORMAL)
		assertTrue(runCatching { admission.prepare(tasks, null, nullable) }.isFailure)
	}

	/** T06/T08: stale preview and late enqueue reject even on A -> B -> A; serialized old task stays pinned. */
	@Test fun normalTaskAdmissionRejectsStaleDialogAndEnqueueButKeepsPinnedTask() = runBlocking {
		val manga = remote(810_121L)
		favourite(manga)
		val admission = NormalDownloadTaskAdmission(normalDownloadScope, destinations, context)
		val tasks = listOf(manga to admissionTask(manga, root))
		val (preview, admitted) = admission.prepare(tasks, FavouriteSpace.NORMAL)
		val persisted = admitted.single().second.toData()
		val file = download(root, manga).file
		val next = File(root, "new-normal")
		destinations.setRoot(FavouriteSpace.NORMAL, next)
		assertTrue(runCatching { admission.prepare(tasks, FavouriteSpace.NORMAL, preview) }.isFailure)
		var enqueued = false
		assertTrue(runCatching { admission.enqueueIfCurrent(preview) { enqueued = true } }.isFailure)
		assertFalse(enqueued)
		val (_, newTasks) = admission.prepare(tasks, FavouriteSpace.NORMAL)
		assertEquals(next, newTasks.single().second.destination)
		val pinned = DownloadTask(persisted)
		assertEquals(root, pinned.destination)
		assertEquals(root, local.getOutputDir(manga, pinned.destination))
		assertTrue(file.exists())
		destinations.setRoot(FavouriteSpace.NORMAL, root)
		assertTrue(runCatching { admission.prepare(tasks, FavouriteSpace.NORMAL, preview) }.isFailure)
		assertTrue(runCatching { admission.enqueueIfCurrent(preview) { enqueued = true } }.isFailure)
		assertFalse(enqueued)
		val (fresh, _) = admission.prepare(tasks, FavouriteSpace.NORMAL)
		admission.enqueueIfCurrent(fresh) { enqueued = true }
		assertTrue(enqueued)
	}

	/** T07: unavailable configured target cannot fall back to a writable historical root. */
	@Test fun normalTaskAdmissionUnavailableRootRejectsWithoutFallback() = runBlocking {
		val manga = remote(810_122L)
		favourite(manga)
		val copy = download(root, manga).file
		val bytes = File(copy, "Chapter 1.cbz").readBytes()
		val next = File(root, "unmounted")
		destinations.setRoot(FavouriteSpace.NORMAL, next)
		assertTrue(next.deleteRecursively())
		val admission = NormalDownloadTaskAdmission(normalDownloadScope, destinations, context)
		val tasks = listOf(manga to admissionTask(manga, root))
		val preview = admission.plan(listOf(manga to FavouriteSpace.NORMAL))
		assertEquals(setOf(manga.id), preview.eligibleIds)
		assertEquals(next, preview.root)
		assertTrue(runCatching { admission.prepare(tasks, FavouriteSpace.NORMAL, preview) }.isFailure)
		assertNull(local.getOutputDir(manga, next))
		assertFalse(next.exists())
		assertArrayEquals(bytes, File(copy, "Chapter 1.cbz").readBytes())
		assertTrue(next.mkdirs())
		val (_, routed) = admission.prepare(tasks, FavouriteSpace.NORMAL)
		assertEquals(next, routed.single().second.destination)
		assertArrayEquals(bytes, File(copy, "Chapter 1.cbz").readBytes())
	}

	/** T09: real writer partial-copy window runs under the same mutation wrapper as DownloadWorker. */
	@Test fun normalWorkerMutationFenceRejectsScanDuringActualDurableFlush() = runBlocking {
		val manga = remote(810_123L)
		favourite(manga)
		val space = FavouriteSpace.NORMAL
		val store = observationStore()
		val fence = NormalDownloadMutationFence(normalDownloadScope, store)
		assertTrue(store.scan(manga, space))
		val absent = requireNotNull(store.readVisible(listOf(manga), space)[manga.id])
		assertEquals(NormalObservationStatus.ABSENT, absent.fact.status)
		val stale = requireNotNull(store.begin(manga, space))
		val negative = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space))
		val container = File(root, "downloads/${manga.title}").canonicalFile
		val image = File(root, "worker-fence.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val target = File(container, "Chapter 1.cbz")
		var sawPartial = false
		val writer = LocalMangaDirOutput(container, manga, object : RootFileCommitOps by RealRootFileCommitOps {
			override fun rename(source: File, targetFile: File) = false
			override fun copy(source: File, targetFile: File) {
				val bytes = source.readBytes()
				targetFile.outputStream().use { stream ->
					stream.write(bytes, 0, bytes.size / 2)
					stream.flush()
					assertEquals(target, targetFile)
					assertTrue(targetFile.isFile)
					runBlocking {
						assertNull(store.begin(manga, space))
						assertFalse(store.isCurrent(manga, space, absent))
						assertFalse(store.publish(stale, negative))
					}
					sawPartial = true
					stream.write(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
				}
			}
		})
		try {
			val chapter = manga.chapters.orEmpty().single()
			writer.addPage(IndexedValue(0, chapter), image, 0, MimeType("image/png"))
			fence.mutate(manga, space) {
				assertTrue(writer.flushChapter(chapter))
				db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(manga.id, space.dbValue, container.path))
				assertNull(store.begin(manga, space)) // ownership publication remains within intent
			}
			assertTrue(sawPartial)
			assertTrue(store.takeRequests().ids.contains(manga.id))
			assertTrue(store.scan(manga, space))
			val present = requireNotNull(store.readVisible(listOf(manga), space)[manga.id])
			assertEquals(NormalObservationStatus.PRESENT, present.fact.status)
			assertEquals(container.path, present.fact.path)
			val committed = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space)?.directoryIdentity?.projection)
				.manga.chapters.orEmpty().single()
			assertEquals(chapter.id, committed.id)
			assertTrue(local.getPages(committed).isNotEmpty())
			fence.mutate(manga, space) { writer.finish() }
		} finally { writer.close() }
	}

	/** T09: old pinned output can finalize on cancellation; never publish its old path into B. */
	@Test fun normalWorkerMutationFenceOldRootCleanupAndCancellationQueueCurrentRevalidation() = runBlocking {
		val manga = remote(810_124L)
		favourite(manga)
		val space = FavouriteSpace.NORMAL
		val store = observationStore()
		val fence = NormalDownloadMutationFence(normalDownloadScope, store)
		val image = File(root, "worker-cleanup.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val output = LocalMangaOutput.getOrCreate(root, manga, DownloadFormat.SINGLE_CBZ)
		try {
			val chapter = manga.chapters.orEmpty().single()
			output.addPage(IndexedValue(0, chapter), image, 0, MimeType("image/png"))
			fence.mutate(manga, space) { assertFalse(output.flushChapter(chapter)) }
			val next = File(root, "active-b").apply { check(mkdirs()) }
			destinations.setRoot(space, next)
			assertTrue(store.scan(manga, space))
			assertEquals(NormalObservationStatus.ABSENT, store.readVisible(listOf(manga), space)[manga.id]?.fact?.status)
			val stale = requireNotNull(store.begin(manga, space))
			val negative = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space))
			store.takeRequests()
			val entered = CompletableDeferred<Unit>()
			val running = launch {
				try {
					fence.mutate(manga, space) {
						entered.complete(Unit)
						awaitCancellation()
					}
				} finally {
					// Same NonCancellable cleanup context as worker; real zip salvages staged chapter.
					kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
						fence.mutate(manga, space) {
							output.cleanup()
							assertTrue(output.rootFile.isFile)
							assertNull(store.begin(manga, space))
						}
					}
				}
			}
			withTimeout(10_000) { entered.await() }
			assertNull(store.begin(manga, space))
			running.cancelAndJoin()
			assertFalse(store.publish(stale, negative))
			assertTrue(store.takeRequests().ids.contains(manga.id))
			assertTrue(store.scan(manga, space))
			assertEquals(NormalObservationStatus.ABSENT, store.readVisible(listOf(manga), space)[manga.id]?.fact?.status)
			assertTrue(output.rootFile.isFile)
			val bytes = output.rootFile.readBytes()
			destinations.setRoot(space, root)
			assertTrue(store.scan(manga, space))
			assertEquals(output.rootFile.canonicalPath, store.readVisible(listOf(manga), space)[manga.id]?.fact?.path)
			assertArrayEquals(bytes, output.rootFile.readBytes())
		} finally { output.close() }
	}

	/** Failed operation still releases the fence and requests affected revalidation; protected scope is untouched. */
	@Test fun normalWorkerMutationFenceFailureAndProtectedScope() = runBlocking {
		val (manga, _) = observedDownload(810_125L)
		val space = FavouriteSpace.NORMAL
		val store = observationStore()
		val fence = NormalDownloadMutationFence(normalDownloadScope, store)
		assertTrue(store.scan(manga, space))
		val before = requireNotNull(store.readVisible(listOf(manga), space)[manga.id])
		store.takeRequests()
		val error = java.io.IOException("fixture partial failure")
		assertSame(error, runCatching {
			fence.mutate(manga, space) {
				assertNull(store.readVisible(listOf(manga), space)[manga.id])
				throw error
			}
		}.exceptionOrNull())
		assertFalse(store.isCurrent(manga, space, before))
		assertTrue(store.takeRequests().ids.contains(manga.id))
		assertTrue(store.scan(manga, space))
		val after = requireNotNull(store.readVisible(listOf(manga), space)[manga.id])
		store.takeRequests()
		val changes = store.changes.value
		fence.mutate(manga, FavouriteSpace.PRIVATE) {
			assertEquals(after, store.readVisible(listOf(manga), space)[manga.id])
		}
		val other = remote(810_126L)
		data.storeManga(other, replaceExisting = true)
		fence.mutate(other, space) { assertTrue(store.isCurrent(manga, space, after)) }
		assertEquals(changes, store.changes.value)
		assertTrue(store.takeRequests().ids.isEmpty())
	}

	private fun normalReader(store: NormalDownloadObservationStore = observationStore()) =
		NormalDownloadReadResolver(normalDownloadScope, store)

	private fun chapterLoader(reader: NormalDownloadReadResolver) =
		ChaptersLoader(readerRepositoryFactory, readerContent, reader)

	/** T02/Q23: actual ChaptersLoader selects the corrupt local artifact, including retry. */
	@Test fun normalReaderCorruptSelectionNeverFallsBackAcrossRetry() = runBlocking {
		val seed = remote(810_127L)
		val source = MissingMangaSource("MIHON_2677079941490683989")
		val chapter = seed.chapters.orEmpty().single().copy(source = source, url = "/chapter/1")
		val manga = seed.copy(source = source, chapters = listOf(chapter))
		favourite(manga)
		val container = File(root, "downloads/Bacami (ID)/${manga.title}").apply { check(mkdirs()) }.canonicalFile
		val bytes = byteArrayOf(1, 2, 3, 4)
		val file = File(container, "Chapter 1.cbz").apply { writeBytes(bytes) }
		val reader = normalReader()
		val copy = requireNotNull(reader.resolve(manga, FavouriteSpace.NORMAL))
		val selector = requireNotNull(copy.normalReaderSelectors?.get(chapter.id))
		assertEquals(file.path, android.net.Uri.parse(selector.url).path)
		val expectedError = runCatching { local.getPages(selector) }.exceptionOrNull()
		val loader = chapterLoader(reader)
		try {
			loader.init(MangaDetails(manga, copy, null, null, true), FavouriteSpace.NORMAL, true)
			suspend fun assertLocalFailure() {
				val error = requireNotNull(runCatching { loader.loadSingleChapter(chapter.id) }.exceptionOrNull())
				if (expectedError != null) assertEquals(expectedError.javaClass, error.javaClass)
				else assertEquals("Selected local chapter has no readable pages", error.message)
				assertEquals(selector.url, loader.peekChapter(chapter.id)?.url)
				assertEquals(LocalMangaSource, loader.peekChapter(chapter.id)?.source)
				assertFalse(loader.refreshChapterPages(chapter.id))
				assertFalse(loader.hasPages(chapter.id))
			}
			assertLocalFailure()
			// A metadata-only emission cannot remove the failed local selection.
			loader.init(MangaDetails(manga), FavouriteSpace.NORMAL, true)
			assertLocalFailure()
			assertArrayEquals(bytes, file.readBytes())
			assertNull(db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id))
		} finally { loader.close() }
	}

	/** T06/T08: existing visible pages stay pinned, fresh Reader follows the active root. */
	@Test fun normalReaderRootSwitchPreservesSessionAndNewReaderUsesCurrentCopy() = runBlocking {
		val (manga, old) = observedDownload(810_128L)
		val space = FavouriteSpace.NORMAL
		val id = manga.chapters.orEmpty().single().id
		val reader = normalReader()
		val a = requireNotNull(reader.resolve(manga, space))
		val loader = chapterLoader(reader)
		val fresh = chapterLoader(reader)
		try {
			loader.init(MangaDetails(manga, a, null, null, true), space, true)
			assertTrue(loader.loadSingleChapter(id))
			val oldPage = loader.getPages(id).single().url
			assertEquals(File(old, "Chapter 1.cbz").path, android.net.Uri.parse(oldPage).path)
			val bRoot = File(root, "reader-b")
			destinations.setRoot(space, bRoot)
			assertNull(reader.resolve(manga, space))
			val b = download(bRoot, manga).file.canonicalFile
			db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(manga.id, space.dbValue, b.path))
			val current = requireNotNull(reader.resolve(manga, space))
			loader.init(MangaDetails(manga, current, null, null, true), space, true)
			assertEquals(oldPage, loader.getPages(id).single().url)
			assertFalse(loader.refreshChapterPages(id))
			assertTrue(runCatching { loader.loadSingleChapter(id) }.isFailure)
			assertEquals(oldPage, loader.getPages(id).single().url)
			fresh.init(MangaDetails(manga, current, null, null, true), space, true)
			assertTrue(fresh.loadSingleChapter(id))
			val newPage = fresh.getPages(id).single().url
			assertEquals(File(b, "Chapter 1.cbz").path, android.net.Uri.parse(newPage).path)
			assertNotEquals(oldPage, newPage)
			assertTrue(File(old, "Chapter 1.cbz").isFile)
		} finally { loader.close(); fresh.close() }
	}

	/** T05: migrated SINGLE_CBZ logical chapter ID is distinct from embedded Reader selector. */
	@Test fun normalReaderMigratedArchiveLoadsExactEmbeddedPage() = runBlocking {
		val seed = remote(810_129L)
		val oldSource = MissingMangaSource("MIHON_2677079941490683989")
		val first = seed.chapters.orEmpty().single().copy(source = oldSource, url = "/chapter/1")
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2", number = 2f)
		val old = seed.copy(source = oldSource, chapters = listOf(first, second))
		val newSource = MissingMangaSource("TSUKI:MIYORARE:miyorare-id:BACAMI")
		val newFirst = first.copy(id = first.id + 10, source = newSource)
		val newSecond = second.copy(id = second.id + 10, source = newSource)
		val target = old.copy(id = 810_130L, source = newSource, chapters = listOf(newFirst, newSecond))
		ids += target.id
		data.storeManga(old, replaceExisting = true)
		favourite(target)
		val image = File(root, "reader-migrated.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val output = LocalMangaOutput.getOrCreate(root, old, DownloadFormat.SINGLE_CBZ)
		val archive = output.rootFile.canonicalFile
		try {
			output.addPage(IndexedValue(0, first), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(first))
			output.addPage(IndexedValue(1, second), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(second))
			output.finish()
		} finally { output.close() }
		val bytes = archive.readBytes()
		val originals = LocalMangaParser(archive).getManga(withDetails = true).manga.chapters.orEmpty()
			.associate { it.id to local.getPages(it).single().url }
		index.rememberVerifiedPath(old.id, archive)
		requireNotNull(index.prepareDownloadIdentityMove(old.id, target.id, mapOf(first.id to newFirst.id, second.id to newSecond.id)))
		val space = FavouriteSpace.NORMAL
		val owner = FavouriteDownloadIndexEntity(target.id, space.dbValue, archive.path)
		db.getFavouriteDownloadIndexDao().upsert(owner)
		val reader = normalReader()
		val copy = requireNotNull(reader.resolve(target, space))
		val loader = chapterLoader(reader)
		try {
			loader.init(MangaDetails(target, copy, null, null, true), space, true)
			for ((embedded, logical) in listOf(first to newFirst, second to newSecond)) {
				assertEquals(embedded.id, copy.normalReaderSelectors?.get(logical.id)?.id)
				assertEquals(logical.id, loader.peekChapter(logical.id)?.id)
				assertTrue(loader.loadSingleChapter(logical.id))
				assertEquals(originals[embedded.id], loader.getPages(logical.id).single().url)
				assertEquals(logical.id, loader.snapshot().single().chapterId)
				assertFalse(loader.refreshChapterPages(logical.id))
			}
			assertArrayEquals(bytes, archive.readBytes())
			assertEquals(owner, db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, target.id))
		} finally { loader.close() }
	}

	/** T15: selected manual and owned rename parity; corrupt exact proof separate from rename guard. */
	@Test fun normalChapterActionExactRenameRejectsStaleAndProtectedTargets() = runBlocking {
		val (writer, ownedDir) = observedDownload(810_131L)
		val writerFile = File(ownedDir, "Chapter 1.cbz")
		val bytes = writerFile.readBytes()
		val seed = remote(810_132L)
		val source = MissingMangaSource("MIHON_2677079941490683989")
		val manual = seed.copy(source = source, chapters = listOf(seed.chapters.orEmpty().single().copy(source = source, url = "/chapter/1")))
		favourite(manual)
		val container = File(root, "downloads/Bacami (ID)/${manual.title}").apply { check(mkdirs()) }.canonicalFile
		val file = File(container, "Chapter 1.cbz").apply { writeBytes(bytes) }
		val space = FavouriteSpace.NORMAL
		val store = observationStore()
		val fence = NormalDownloadMutationFence(normalDownloadScope, store)
		val action = NormalDownloadChapterAction(normalDownloadScope, store, destinations, fence, MangaLock())
		val reader = normalReader(store)
		val selector = requireNotNull(reader.resolve(manual, space)?.normalReaderSelectors?.values?.single())
		suspend fun preview() = action.previewRename(manual, space, container, file, selector.id, selector.url)
		val approved = preview()
		assertTrue(runCatching { action.captureStandalone(manual, FavouriteSpace.PRIVATE, container, file, selector.id, selector.url) }.isFailure)
		assertTrue(runCatching { action.captureStandalone(manual, space, container, file, selector.id + 1L, selector.url) }.isFailure)
		val competing = File(container.parentFile, container.name + "_8")
		assertTrue(container.copyRecursively(competing))
		assertTrue(runCatching { preview() }.isFailure)
		assertTrue(competing.deleteRecursively())
		val next = File(root, "rename-b")
		destinations.setRoot(space, next)
		destinations.setRoot(space, root)
		assertTrue(runCatching { action.rename(manual, space, approved, "Chapter 1 Special") }.isFailure)
		assertArrayEquals(bytes, file.readBytes())
		val beforeReplace = preview()
		val replacement = File(container, "replacement").apply { writeBytes(bytes) }
		assertTrue(file.delete())
		assertTrue(replacement.renameTo(file))
		assertTrue(runCatching { action.rename(manual, space, beforeReplace, "Chapter 1 Special") }.isFailure)
		assertArrayEquals(bytes, file.readBytes())
		val corrupt = byteArrayOf(5, 6, 7)
		file.writeBytes(corrupt)
		assertNotNull(action.captureStandalone(manual, space, container, file, selector.id, selector.url))
		assertTrue(runCatching { preview() }.isFailure)
		assertArrayEquals(corrupt, file.readBytes())
		file.writeBytes(bytes)
		assertTrue(action.rename(manual, space, preview(), "Chapter 1 Special"))
		assertFalse(file.exists())
		assertArrayEquals(bytes, File(container, "Chapter 1 Special.cbz").readBytes())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manual.id))
		assertFalse(File(container, LocalMangaOutput.ENTRY_NAME_INDEX).exists())
		assertTrue(store.takeRequests().ids.contains(manual.id))
		val ownedSelector = requireNotNull(reader.resolve(writer, space)?.normalReaderSelectors?.values?.single())
		val owner = db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, writer.id)
		val writerApproval = action.previewRename(writer, space, ownedDir, writerFile, ownedSelector.id, ownedSelector.url)
		assertTrue(action.rename(writer, space, writerApproval, "Chapter 1 Special"))
		assertArrayEquals(bytes, File(ownedDir, "Chapter 1 Special.cbz").readBytes())
		assertEquals(owner, db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, writer.id))
	}

	/** T02/T15/Q24: exact corrupt manual chapter, approval transport, stale root and replacement. */
	@Test fun normalDeleteExactStandaloneKeepsSiblingsAndRejectsStaleApproval() = runBlocking {
		val (_, writerDir) = observedDownload(810_133L)
		val bytes = File(writerDir, "Chapter 1.cbz").readBytes()
		val seed = remote(810_134L)
		val source = MissingMangaSource("MIHON_2677079941490683989")
		val first = seed.chapters.orEmpty().single().copy(source = source, url = "/chapter/1")
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2", number = 2f)
		val manga = seed.copy(source = source, chapters = listOf(first, second))
		favourite(manga)
		val container = File(root, "downloads/Bacami (ID)/${manga.title}").apply { check(mkdirs()) }.canonicalFile
		val corrupt = byteArrayOf(4, 8, 15, 16)
		val file = File(container, "Chapter 1.cbz").apply { writeBytes(corrupt) }
		val sibling = File(container, "Chapter 2.cbz").apply { writeBytes(bytes) }
		val space = FavouriteSpace.NORMAL
		val store = observationStore()
		val action = NormalDownloadDeleteAction(normalDownloadScope, store, destinations,
			NormalDownloadMutationFence(normalDownloadScope, store), MangaLock(), local)
		val selectors = requireNotNull(normalReader(store).resolve(manga, space)?.normalReaderSelectors)
		val selected = mapOf(first.id to requireNotNull(selectors[first.id]).url)
		suspend fun preview() = requireNotNull(action.preview(manga, space, container, selected))
		assertTrue(runCatching { action.preview(manga, FavouriteSpace.PRIVATE, container, selected) }.isFailure)
		assertTrue(runCatching { action.preview(manga, space, container, mapOf(first.id to requireNotNull(selectors[second.id]).url)) }.isFailure)
		val competing = File(container.parentFile, container.name + "_9")
		assertTrue(container.copyRecursively(competing))
		assertTrue(runCatching { preview() }.isFailure)
		assertTrue(competing.deleteRecursively())
		val old = preview()
		destinations.setRoot(space, File(root, "delete-b"))
		destinations.setRoot(space, root)
		assertTrue(runCatching { action.execute(manga, space, old) }.isFailure)
		val beforeReplace = preview()
		val replacement = File(container, "replacement").apply { writeBytes(corrupt) }
		assertTrue(file.delete())
		assertTrue(replacement.renameTo(file))
		assertTrue(runCatching { action.execute(manga, space, beforeReplace) }.isFailure)
		assertArrayEquals(corrupt, file.readBytes())
		val unknown = File(container, "foreign.txt").apply { writeText("keep") }
		assertTrue(runCatching { action.preview(manga, space, container) }.isFailure)
		val approval = preview()
		val serialized = java.io.ByteArrayOutputStream().also {
			java.io.ObjectOutputStream(it).use { stream -> stream.writeObject(approval) }
		}.toByteArray()
		val restored = java.io.ObjectInputStream(serialized.inputStream()).use { it.readObject() as NormalDeleteApproval }
		assertEquals(approval, restored)
		assertTrue(action.execute(manga, space, restored))
		assertFalse(file.exists())
		assertArrayEquals(bytes, sibling.readBytes())
		assertEquals("keep", unknown.readText())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manga.id))
		assertTrue(store.takeRequests().ids.contains(manga.id))
	}

	/** Q24: whole/bulk delete only confirmed containers, never a new copy for an absent title. */
	@Test fun normalDeleteWholeAndBulkUseApprovedActiveContainers() = runBlocking {
		val (first, firstDir) = observedDownload(810_135L)
		val (second, secondDir) = observedDownload(810_136L)
		val absent = remote(810_137L)
		favourite(absent)
		val space = FavouriteSpace.NORMAL
		val ids = setOf(first.id, second.id, absent.id)
		val approved = deleteLocal.previewBulkDeletion(ids, space)
		assertEquals(ids, approved.normal.keys)
		assertNull(approved.normal[absent.id])
		assertEquals(firstDir, approved.normal[first.id]?.container)
		assertEquals(secondDir, approved.normal[second.id]?.container)
		val late = download(root, absent).file.canonicalFile
		val lateFile = File(late, "Chapter 1.cbz")
		val lateBytes = lateFile.readBytes()
		assertEquals(2, deleteLocal.deleteApprovedBulk(approved))
		assertFalse(firstDir.exists())
		assertFalse(secondDir.exists())
		assertArrayEquals(lateBytes, lateFile.readBytes())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, first.id))
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, second.id))
		assertTrue(runCatching { deleteLocal.deleteApprovedBulk(approved) }.isFailure)
	}

	/** Q24: partial SINGLE_CBZ filtering preserves the other embedded chapter and its pages. */
	@Test fun normalDeleteIndexedArchiveFiltersExactEmbeddedIdsAndKeepsRemainingPages() = runBlocking {
		val seed = remote(810_138L)
		val first = seed.chapters.orEmpty().single()
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2", number = 2f)
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val image = File(root, "delete-archive.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val output = LocalMangaOutput.getOrCreate(root, manga, DownloadFormat.SINGLE_CBZ)
		val target = output.rootFile.canonicalFile
		try {
			output.addPage(IndexedValue(0, first), image, 0, MimeType("image/png"))
			output.flushChapter(first)
			output.addPage(IndexedValue(1, second), image, 0, MimeType("image/png"))
			output.flushChapter(second)
			output.finish()
		} finally { output.close() }
		val space = FavouriteSpace.NORMAL
		val store = observationStore()
		val action = NormalDownloadDeleteAction(normalDownloadScope, store, destinations,
			NormalDownloadMutationFence(normalDownloadScope, store), MangaLock(), local)
		val reader = normalReader(store)
		val before = requireNotNull(reader.resolve(manga, space)?.normalReaderSelectors)
		assertEquals(setOf(first.id, second.id), before.keys)
		val approval = requireNotNull(action.preview(manga, space, target, mapOf(first.id to before.getValue(first.id).url)))
		assertFalse(approval.removeContainer)
		assertTrue(action.execute(manga, space, approval))
		assertTrue(target.exists())
		val after = requireNotNull(reader.resolve(manga, space)?.normalReaderSelectors)
		assertEquals(setOf(second.id), after.keys)
		assertEquals(1, local.getPages(after.getValue(second.id)).size)
		assertTrue(runCatching { action.execute(manga, space, approval) }.isFailure)
		val whole = requireNotNull(action.preview(manga, space, target))
		assertTrue(action.execute(manga, space, whole))
		assertFalse(target.exists())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manga.id))
	}

	/** Q24: actual migrated archive partial delete preserves old embedded ID and existing redirect map. */
	@Test fun normalDeleteMigratedArchivePreservesSurvivingLocatorAndAlias() = runBlocking {
		val seed = remote(810_139L)
		val oldSource = MissingMangaSource("MIHON_2677079941490683989")
		val first = seed.chapters.orEmpty().single().copy(source = oldSource, url = "/chapter/1")
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2", number = 2f)
		val retired = second.copy(id = second.id + 1, title = "Retired", url = "/retired", number = 3f)
		val old = seed.copy(source = oldSource, chapters = listOf(first, second, retired))
		val newSource = MissingMangaSource("TSUKI:MIYORARE:miyorare-id:BACAMI")
		val newFirst = first.copy(id = first.id + 10, source = newSource)
		val newSecond = second.copy(id = second.id + 10, source = newSource)
		val target = old.copy(id = 810_140L, source = newSource, chapters = listOf(newFirst, newSecond))
		ids += target.id
		data.storeManga(old, replaceExisting = true)
		favourite(target)
		val image = File(root, "delete-migrated.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val output = LocalMangaOutput.getOrCreate(root, old, DownloadFormat.SINGLE_CBZ)
		val archive = output.rootFile.canonicalFile
		try {
			output.addPage(IndexedValue(0, first), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(first))
			output.addPage(IndexedValue(1, second), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(second))
			output.addPage(IndexedValue(2, retired), image, 0, MimeType("image/png"))
			assertFalse(output.flushChapter(retired))
			output.finish()
		} finally { output.close() }
		val bytes = archive.readBytes()
		val originals = LocalMangaParser(archive).getManga(withDetails = true).manga.chapters.orEmpty()
			.associate { it.id to local.getPages(it).single().url }
		index.rememberVerifiedPath(old.id, archive)
		requireNotNull(index.prepareDownloadIdentityMove(old.id, target.id, mapOf(first.id to newFirst.id, second.id to newSecond.id)))
		val space = FavouriteSpace.NORMAL
		val owner = FavouriteDownloadIndexEntity(target.id, space.dbValue, archive.path)
		db.getFavouriteDownloadIndexDao().upsert(owner)
		val alias = requireNotNull(index.getDownloadAliasForScanner(target.id))
		val store = observationStore()
		val reader = normalReader(store)
		val action = NormalDownloadDeleteAction(normalDownloadScope, store, destinations,
			NormalDownloadMutationFence(normalDownloadScope, store), MangaLock(), local)
		val copy = requireNotNull(reader.resolve(target, space))
		val selectors = requireNotNull(copy.normalReaderSelectors)
		val selected = selectors.getValue(newFirst.id)
		assertTrue(runCatching { action.preview(target, space, archive, selectors.mapValues { it.value.url }) }.isFailure)
		assertArrayEquals(bytes, archive.readBytes())
		val approval = requireNotNull(action.preview(target, space, archive, mapOf(newFirst.id to selected.url)))
		assertEquals(setOf(first.id), approval.embeddedIds)
		assertFalse(approval.removeContainer)
		val pending = requireNotNull(store.begin(target, space))
		val oldResult = requireNotNull(normalDirectoryDiscovery.resolveCandidates(target, space))
		assertTrue(action.execute(target, space, approval))
		assertFalse(store.publish(pending, oldResult))
		val remaining = requireNotNull(reader.resolve(target, space)?.normalReaderSelectors)
		assertEquals(setOf(newSecond.id), remaining.keys)
		assertEquals(second.id, remaining.getValue(newSecond.id).id)
		assertTrue(runCatching { action.preview(target, space, archive, remaining.mapValues { it.value.url }) }.isFailure)
		assertEquals(originals.getValue(second.id), local.getPages(remaining.getValue(newSecond.id)).single().url)
		assertEquals(alias, index.getDownloadAliasForScanner(target.id))
		assertEquals(owner, db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, target.id))
		assertTrue(store.scan(target, space))
		assertEquals(NormalObservationStatus.PRESENT, store.readVisible(listOf(target), space)[target.id]?.fact?.status)
		assertTrue(runCatching { action.execute(target, space, approval) }.isFailure)
		assertTrue(runCatching { reader.resolve(target.copy(chapters = listOf(newFirst, newSecond.copy(url = "/wrong"))), space) }.isFailure)
		// Restoring the original archive still resolves both exact selectors: deletion did not rewrite aliases.
		archive.writeBytes(bytes)
		assertEquals(setOf(newFirst.id, newSecond.id), requireNotNull(reader.resolve(target, space)?.normalReaderSelectors).keys)
		assertTrue(action.execute(target, space, requireNotNull(action.preview(target, space, archive))))
		assertFalse(archive.exists())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, target.id))
	}

	/** Q24/T15: indexed metadata is proof, not permission to delete arbitrary cover/staging paths. */
	@Test fun normalDeleteIndexedDirectoryChecksSidecarAndPreservesForeignContent() = runBlocking {
		val seed = remote(810_141L)
		val first = seed.chapters.orEmpty().single()
		val second = first.copy(id = first.id + 1, title = "Chapter 2", url = "/chapter/2", number = 2f)
		val manga = seed.copy(chapters = listOf(first, second))
		favourite(manga)
		val container = download(root, manga.copy(chapters = listOf(first))).file.canonicalFile
		val firstFile = File(container, "Chapter 1.cbz")
		val secondFile = File(container, "Chapter 2.cbz").apply { writeBytes(firstFile.readBytes()) }
		val bytes = secondFile.readBytes()
		val sidecar = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		val foreign = File(container, "foreign.tmp").apply { writeText("keep") }
		fun metadata(cover: String? = null) = MangaIndex(null).apply {
			setMangaInfo(manga)
			addChapter(IndexedValue(0, first), firstFile.name)
			addChapter(IndexedValue(1, second), secondFile.name)
			cover?.let(::setCoverEntry)
		}.toString()
		sidecar.writeText(metadata(foreign.name))
		val space = FavouriteSpace.NORMAL
		val owner = FavouriteDownloadIndexEntity(manga.id, space.dbValue, container.path)
		db.getFavouriteDownloadIndexDao().upsert(owner)
		val store = observationStore()
		val reader = normalReader(store)
		val action = NormalDownloadDeleteAction(normalDownloadScope, store, destinations,
			NormalDownloadMutationFence(normalDownloadScope, store), MangaLock(), local)
		val selected = requireNotNull(reader.resolve(manga, space)?.normalReaderSelectors?.get(first.id))
		val selection = mapOf(first.id to selected.url)
		suspend fun preview() = requireNotNull(action.preview(manga, space, container, selection))
		assertTrue(runCatching { action.preview(manga, space, container) }.isFailure)
		assertEquals("keep", foreign.readText())
		val old = preview()
		sidecar.writeText(metadata())
		assertTrue(runCatching { action.execute(manga, space, old) }.isFailure)
		assertTrue(firstFile.exists())
		assertArrayEquals(bytes, secondFile.readBytes())
		val approved = preview()
		val staleWork = requireNotNull(store.begin(manga, space))
		val staleResult = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space))
		assertTrue(action.execute(manga, space, approved))
		assertFalse(store.publish(staleWork, staleResult))
		assertFalse(firstFile.exists())
		assertArrayEquals(bytes, secondFile.readBytes())
		assertEquals("keep", foreign.readText())
		assertTrue(sidecar.exists())
		assertEquals(setOf(second.id), requireNotNull(reader.resolve(manga, space)?.normalReaderSelectors).keys)
		assertTrue(runCatching { action.preview(manga, space, container) }.isFailure)
		assertTrue(foreign.delete())
		assertTrue(action.execute(manga, space, requireNotNull(action.preview(manga, space, container))))
		assertFalse(container.exists())
		assertNull(db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manga.id))
	}

	private suspend fun favourite(manga: Manga, space: FavouriteSpace = FavouriteSpace.NORMAL) {
		data.storeManga(manga, replaceExisting = true)
		val category = repository.createCategory("Batch 11 ${manga.id}", ListSortOrder.NEWEST, false, false, true, space)
		repository.updateCategoryMemberships(listOf(manga), mapOf(category.id to true), space)
	}

	private fun remote(id: Long): Manga {
		ids += id
		val source = MissingMangaSource("BATCH11_FIXTURE")
		return SampleData.manga.copy(
			id = id, title = "Batch 11 $id", altTitles = emptySet(), source = source, url = "/batch11/$id",
			contentRating = null, tags = emptySet(), coverUrl = null, largeCoverUrl = null,
			chapters = listOf(SampleData.chapter.copy(id = id * 10, title = "Chapter 1", number = 1f, scanlator = null, source = source)),
		)
	}

	private suspend fun download(destination: File, remote: Manga): LocalManga {
		val image = File(root, "fixture.png")
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
		finally { bitmap.recycle() }
		val chapter = remote.chapters.orEmpty().single()
		val output = LocalMangaOutput.getOrCreate(destination, remote, DownloadFormat.MULTIPLE_CBZ)
		try {
			output.addPage(IndexedValue(0, chapter), image, 0, MimeType("image/png"))
			assertTrue(output.flushChapter(chapter))
			output.finish()
		} finally { output.close() }
		return LocalMangaParser(output.rootFile).getManga(withDetails = true).also { ids += it.manga.id }
	}

	/** Same production chapter consumer, with source refresh controlled by this fixture. */
	private inner class ScopedDetailsViewModel : ChaptersPagesViewModel(
		settings, detailsInteractor, detailsBookmarks, detailsHistory, detailsScheduler,
		destinations, FavouriteSpace.NORMAL, deleteLocal, events, data, readerRepositoryFactory,
		detailsPersonal, detailsProgress, readerContent,
		downloadSpaceProvenance = FavouriteSpace.NORMAL, normalDetailsResolver = detailsNormalResolver,
	) {
		override fun reload() = Unit
		suspend fun accept(incoming: MangaDetails) = publishDetailsSnapshot(incoming)
	}

	@Test
	fun normalDetailsProjectionKeepsExactSpecialAndRejectsStaleCopy() = runBlocking {
		val seed = remote(810_280L)
		val original = seed.chapters.orEmpty().single().copy(title = "Chapter 0", number = 0f, branch = null)
		val manga = seed.copy(chapters = listOf(original))
		favourite(manga)
		val container = download(root, manga).file.canonicalFile
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(manga.id, FavouriteSpace.NORMAL.dbValue, container.path))
		val originalFile = requireNotNull(container.listFiles()).single { it.extension == "cbz" }
		val specialFile = File(container, "Chapter 0 Special.cbz")
		val sidecar = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
		assertFalse("MULTIPLE_CBZ writer must remain sidecar-free", sidecar.exists())
		val bytes = originalFile.readBytes()
		val key = ChapterPersonalKey.of(original)
		val personal = ChapterPersonalEntity(manga.id, key.source, key.url, 5, "Original note")
		db.getChapterPersonalDao().upsert(personal)
		val progress = ChapterProgressEntity(manga.id, key.source, key.url, FavouriteSpace.NORMAL.dbValue, 1, 0, 2)
		db.getChapterProgressDao().upsert(progress)
		val history = HistoryEntity(manga.id, 100L, 200L, original.id, 0, 0f, 0.5f, 0L, 1)
		db.getHistoryDao().insert(history)
		settings.setChapterReadOverrides(manga.id, mapOf(original.id to true))
		lateinit var vm: ScopedDetailsViewModel
		instrumentation.runOnMainSync {
			vm = ScopedDetailsViewModel()
			viewModels.put("normal-details", vm)
		}
		suspend fun awaitRows(stage: String, predicate: (List<ChapterListItem>) -> Boolean): List<ChapterListItem> {
			val rows = withTimeoutOrNull(10_000) { vm.chapters.first(predicate) }
			if (rows != null) return rows
			// Capture the failed boundary before a fresh read can change observation state.
			val details = vm.mangaDetails.value
			val rendered = vm.chapters.value.map { row ->
				"${row.chapter.id}:${row.chapter.title}:downloaded=${row.isDownloaded}:key=${row.personalKey}"
			}
			val visible = liveObservations.readVisible(listOf(manga), FavouriteSpace.NORMAL)[manga.id]
			val fresh = normalDirectoryDiscovery.resolveCandidates(manga, FavouriteSpace.NORMAL)
			throw AssertionError(
				"$stage timed out: rows=$rendered; selectedBranch=${vm.selectedBranch.value}; " +
					"exact=${details?.exactNormalDownloadIdentity}; source=${details?.sourceManga?.chapters}; " +
					"local=${details?.local?.manga?.chapters}; observation=${visible?.fact}; " +
					"acceptedProjection=${visible?.localProjection?.manga?.chapters}; " +
					"files=${container.list()?.sorted()}; freshVerified=${fresh?.verified?.map { it.manga.chapters }}; " +
					"freshUnresolved=${fresh?.unresolvedPaths}; inventoryComplete=${fresh?.inventoryComplete}; " +
					"priorEvidence=${fresh?.directoryIdentity?.evidence}; freshProjection=${fresh?.directoryIdentity?.projection?.manga?.chapters}",
			)
		}
		suspend fun assertIcon(row: ChapterListItem, downloaded: Boolean) = withContext(Dispatchers.Main) {
			// Exercise the existing real row binder, not a second rendering implementation.
			val adapter = ChaptersAdapter(OnListItemClickListener { _, _ -> }, {}, {}, {}, {})
			val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Kotatsu))
			adapter.emit(listOf(row.copy(flags = (row.flags.toInt() and ChapterListItem.FLAG_GRID.toInt().inv()).toByte())))
			// RecyclerView's public wrappers initialize the holder type and binding state.
			val viewType = adapter.getItemViewType(0)
			val holder = adapter.createViewHolder(parent, viewType)
			assertEquals(viewType, holder.itemViewType)
			adapter.bindViewHolder(holder, 0)
			assertEquals(if (downloaded) View.VISIBLE else View.GONE,
				holder.itemView.findViewById<View>(R.id.imageView_downloaded).visibility)
		}
		vm.accept(MangaDetails(manga))
		assertFalse(awaitRows("initial remote row") { it.size == 1 }.single().isDownloaded)
		assertTrue(liveObservations.scan(manga, FavouriteSpace.NORMAL))
		val ready = awaitRows("initial downloaded row") { it.size == 1 && it.single().isDownloaded }.single()
		assertEquals(key, ready.personalKey)
		assertIcon(ready, true)
		val positive = requireNotNull(vm.mangaDetails.value)
		val page = local.getPages(ready.chapter).first()
		val bookmark = BookmarkEntity(manga.id, page.id, original.id, 0, 0, page.url, 100L, 0.5f)
		db.getBookmarksDao().insert(bookmark)
		assertTrue(originalFile.renameTo(specialFile))
		assertTrue(liveObservations.scan(manga, FavouriteSpace.NORMAL))
		// An owned, renamed-only sidecar-free container without corroborated local
		// identity is deliberately UNKNOWN. Preserve the existing security boundary.
		val unresolved = requireNotNull(liveObservations.readVisible(listOf(manga), FavouriteSpace.NORMAL)[manga.id])
		assertEquals(NormalObservationStatus.UNKNOWN, unresolved.fact.status)
		assertNull(unresolved.localProjection)
		assertIcon(awaitRows("sidecar-free renamed-only remains unresolved") {
			it.size == 1 && it.single().chapter.id == original.id && !it.single().isDownloaded
		}.single(), false)
		vm.accept(positive)
		assertNull(vm.mangaDetails.value?.local)
		assertFalse(sidecar.exists())
		assertArrayEquals(bytes, specialFile.readBytes())

		// Explicit test setup for the independent legacy indexed rename case.
		// Restore the original before writing its index; the scanner never creates it.
		assertTrue(specialFile.renameTo(originalFile))
		sidecar.writeText(MangaIndex(null).apply {
			setMangaInfo(manga)
			addChapter(IndexedValue(0, original), originalFile.name)
		}.toString())
		val indexBytes = sidecar.readBytes()
		assertTrue(liveObservations.scan(manga, FavouriteSpace.NORMAL))
		assertIcon(awaitRows("indexed original download") {
			it.size == 1 && it.single().chapter.id == original.id && it.single().isDownloaded
		}.single(), true)
		assertTrue(originalFile.renameTo(specialFile))
		assertTrue(liveObservations.scan(manga, FavouriteSpace.NORMAL))
		assertEquals(NormalObservationStatus.PRESENT,
			liveObservations.readVisible(listOf(manga), FavouriteSpace.NORMAL)[manga.id]?.fact?.status)
		val renamed = awaitRows("rename separates Special from original") { rows -> rows.size == 2 && rows.any { it.chapter.id == original.id && !it.isDownloaded } }
		val originalRow = renamed.single { it.chapter.id == original.id }
		val special = renamed.single { it.chapter.id != original.id }
		assertTrue(special.isDownloaded)
		assertEquals(key, originalRow.personalKey)
		assertEquals(ChapterPersonalKey.of(special.chapter), special.personalKey)
		assertNotEquals(key, special.personalKey)
		assertTrue(special.isUnread)
		assertFalse(special.isBookmarked)
		assertTrue(local.getPages(special.chapter).isNotEmpty())
		assertArrayEquals(bytes, specialFile.readBytes())
		assertIcon(originalRow, false)
		assertIcon(special, true)
		vm.accept(positive)
		assertEquals(special.chapter.id, requireNotNull(vm.mangaDetails.value?.local).manga.chapters.orEmpty().single().id)
		val staleSpecial = requireNotNull(vm.mangaDetails.value)
		assertTrue(specialFile.delete())
		assertTrue(liveObservations.scan(manga, FavouriteSpace.NORMAL))
		assertEquals(NormalObservationStatus.ABSENT,
			liveObservations.readVisible(listOf(manga), FavouriteSpace.NORMAL)[manga.id]?.fact?.status)
		assertIcon(awaitRows("delete removes Special") { it.size == 1 && !it.single().isDownloaded }.single(), false)
		vm.accept(staleSpecial)
		assertNull(vm.mangaDetails.value?.local)
		val negative = requireNotNull(vm.mangaDetails.value)
		originalFile.writeBytes(bytes)
		assertTrue(liveObservations.scan(manga, FavouriteSpace.NORMAL))
		assertIcon(awaitRows("restore original download") { it.size == 1 && it.single().isDownloaded }.single(), true)
		vm.accept(negative)
		assertNotNull(vm.mangaDetails.value?.local)
		assertArrayEquals(indexBytes, sidecar.readBytes())
		assertArrayEquals(bytes, originalFile.readBytes())
		assertEquals(listOf(personal), db.getChapterPersonalDao().findAll(listOf(manga.id)))
		assertEquals(listOf(progress), db.getChapterProgressDao().findAll(listOf(manga.id), FavouriteSpace.NORMAL.dbValue))
		assertEquals(listOf(bookmark), db.getBookmarksDao().findAll(manga.id))
		assertEquals(history, db.getHistoryDao().findIncludingDeleted(manga.id))
		assertEquals(mapOf(original.id to true), settings.getChapterReadOverrides(manga.id))
		val missingHistory = history.copy(chapterId = special.chapter.id)
		db.getHistoryDao().update(missingHistory)
		assertEquals(missingHistory.chapterId, detailsHistory.getOneExact(manga.id)?.chapterId)
		assertEquals(missingHistory, db.getHistoryDao().findIncludingDeleted(manga.id))
	}


	/** T02/T04/T05: real sidecar-free proof, Details and cover icons, exact Reader locator. */
	@OptIn(ExperimentalTestApi::class)
	@Test fun normalSidecarFreeSpecialRendersScannerIcons() = runComposeUiTest {
		val ui = this
		runBlocking {
			val space = FavouriteSpace.NORMAL
			val seed = remote(810_281L)
			val first = seed.chapters.orEmpty().single().copy(title = "Chapter 0", number = 0f, branch = null)
			val second = first.copy(id = first.id + 1, title = "Chapter 2", number = 2f, url = "/chapter/2")
			val manga = seed.copy(chapters = listOf(first, second))
			favourite(manga)
			val container = download(root, manga.copy(chapters = listOf(first))).file.canonicalFile
			val original = requireNotNull(container.listFiles()).single { it.extension == "cbz" }
			val bytes = original.readBytes()
			val sibling = File(container, "Chapter 2.cbz").apply { writeBytes(bytes) }
			val specialFile = File(container, "Chapter 0 Special.cbz")
			val sidecar = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
			assertFalse(sidecar.exists())
			val owner = FavouriteDownloadIndexEntity(manga.id, space.dbValue, container.path)
			db.getFavouriteDownloadIndexDao().upsert(owner)
			val originalKey = ChapterPersonalKey.of(first)
			val originalPersonal = ChapterPersonalEntity(manga.id, originalKey.source, originalKey.url, 5, "Canonical note")
			db.getChapterPersonalDao().upsert(originalPersonal)
			lateinit var vm: ScopedDetailsViewModel
			instrumentation.runOnMainSync {
				vm = ScopedDetailsViewModel()
				viewModels.put("normal-sidecar-free-details", vm)
			}
			val library = favouritesViewModel()
			lateinit var cover: FavouriteCoverOverlayView
			instrumentation.runOnMainSync {
				cover = FavouriteCoverOverlayView(ContextThemeWrapper(context, R.style.Theme_Kotatsu))
			}
			suspend fun scan(status: NormalObservationStatus) {
				assertTrue(liveObservations.scan(manga, space))
				assertEquals(status, liveObservations.readVisible(listOf(manga), space)[manga.id]?.fact?.status)
			}
			suspend fun rows(stage: String, predicate: (List<ChapterListItem>) -> Boolean): List<ChapterListItem> =
				withTimeoutOrNull(10_000) { vm.chapters.first(predicate) }
					?: throw AssertionError("$stage: rows=" + vm.chapters.value + "; details=" + vm.mangaDetails.value)
			suspend fun assertCover(saved: Boolean) {
				val model = withTimeoutOrNull(10_000) {
					library.content.first { it.grid(manga.id)?.isSaved == saved }.grid(manga.id)
				} ?: throw AssertionError("cover expected $saved: " + library.content.value)
				instrumentation.runOnMainSync {
					cover.bind(ModernCoverState.from(model, ModernCoverContext.COLLECTION)) {}
					val density = cover.resources.displayMetrics.density
					cover.measure(View.MeasureSpec.makeMeasureSpec((194 * density).toInt(), View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec((344 * density).toInt(), View.MeasureSpec.EXACTLY))
					cover.layout(0, 0, cover.measuredWidth, cover.measuredHeight)
					val icon = cover.findViewById<ImageView>(R.id.cover_downloaded)
					assertEquals(if (saved) View.VISIBLE else View.GONE, icon.visibility)
					if (saved) {
						assertNotNull(icon.drawable)
						assertTrue(icon.width > 0 && icon.height > 0 && icon.alpha > 0f)
						assertEquals(View.VISIBLE, cover.findViewById<View>(R.id.cover_permanent_status).visibility)
					}
				}
			}
			fun assertChapterIcon(id: Long, saved: Boolean) {
				val row = hasAnyAncestor(hasTestTag("normal-chapter-$id"))
				val downloaded = ui.onNode(row and hasContentDescription(context.getString(R.string.downloaded)), useUnmergedTree = true)
				val download = ui.onNode(row and hasContentDescription(context.getString(R.string.download)), useUnmergedTree = true)
				if (saved) {
					downloaded.assertIsDisplayed()
					download.assertDoesNotExist()
				} else {
					download.assertIsDisplayed()
					downloaded.assertDoesNotExist()
				}
			}
			suspend fun assertLegacyGrid(row: ChapterListItem, saved: Boolean) = withContext(Dispatchers.Main) {
				val adapter = ChaptersAdapter(OnListItemClickListener { _, _ -> }, {}, {}, {}, {})
				val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Kotatsu))
				adapter.emit(listOf(row.copy(flags = (row.flags.toInt() or ChapterListItem.FLAG_GRID.toInt()).toByte())))
				val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
				adapter.bindViewHolder(holder, 0)
				assertEquals(if (saved) View.VISIBLE else View.GONE,
					holder.itemView.findViewById<View>(R.id.imageView_downloaded).visibility)
			}
			vm.accept(MangaDetails(manga))
			scan(NormalObservationStatus.PRESENT)
			rows("initial canonical files") { it.size == 2 && it.all { row -> row.isDownloaded } }
			val palette = miyorareThemeColors(MiyorareThemePreset.MIYORARE, "#00BCD4",
				darkTheme = true, amoled = false, effectLevel = VisualEffectLevel.BALANCED)
			assertTrue(palette.visualPalette.isModern)
			ui.setContent {
				val current = vm.chapters.collectAsState().value
				MaterialTheme(colorScheme = palette.colorScheme) {
					CompositionLocalProvider(LocalMiyorareVisualPalette provides palette.visualPalette) {
						Column {
							current.forEach { row ->
								key(row.chapter.id) {
									Box(Modifier.testTag("normal-chapter-" + row.chapter.id)) {
										InlineChapterCard(row, VisualEffectLevel.BALANCED, palette.visualPalette.primary,
											onClick = {}, onDownloadClick = {}, onManageClick = {},
											onPersonalClick = {}, onNoteClick = {})
									}
								}
							}
						}
					}
				}
			}
			assertChapterIcon(first.id, true)
			assertChapterIcon(second.id, true)
			assertCover(true)
			display.setShowDownloaded(FavouriteContentType.MANGA, false)
			assertCover(false)
			assertChapterIcon(first.id, true)
			display.setShowDownloaded(FavouriteContentType.MANGA, true)
			assertCover(true)

			// The surviving canonical sibling proves the sidecar-free container, not
			// lineage from Chapter 0 to Special. Replace Special's content explicitly.
			assertTrue(original.renameTo(specialFile))
			val replacement = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
			try {
				replacement.eraseColor(android.graphics.Color.RED)
				ZipOutputStream(specialFile.outputStream()).use { zip ->
					zip.putNextEntry(ZipEntry("1.png"))
					assertTrue(replacement.compress(Bitmap.CompressFormat.PNG, 100, zip))
					zip.closeEntry()
				}
			} finally { replacement.recycle() }
			val replacementBytes = specialFile.readBytes()
			scan(NormalObservationStatus.PRESENT)
			val renamed = rows("sidecar-free separate chapter") {
				it.size == 3 && it.any { row -> row.chapter.id == first.id && !row.isDownloaded } &&
					it.any { row -> row.chapter.id == second.id && row.isDownloaded }
			}
			val special = renamed.single { it.chapter.id !in setOf(first.id, second.id) }
			assertTrue(special.isDownloaded)
			assertTrue(special.isUnread)
			assertFalse(special.isBookmarked)
			assertEquals(ChapterPersonalKey.of(special.chapter), special.personalKey)
			assertNotEquals(originalKey, special.personalKey)
			assertEquals(originalKey, renamed.single { it.chapter.id == first.id }.personalKey)
			assertChapterIcon(first.id, false)
			assertChapterIcon(second.id, true)
			assertChapterIcon(special.chapter.id, true)
			assertLegacyGrid(renamed.single { it.chapter.id == first.id }, false)
			assertLegacyGrid(special, true)
			assertCover(true)
			val reader = normalReader()
			val copy = requireNotNull(reader.resolve(manga, space))
			val loader = chapterLoader(reader)
			val page = try {
				loader.init(MangaDetails(manga, copy, null, null, true), space, true)
				assertTrue(loader.loadSingleChapter(special.chapter.id))
				loader.getPages(special.chapter.id).single()
			} finally { loader.close() }
			val locator = android.net.Uri.parse(page.url)
			assertEquals(specialFile.path, locator.path)
			ZipFile(specialFile).use { zip ->
				val entry = requireNotNull(zip.getEntry(requireNotNull(locator.fragment)))
				val decoded = requireNotNull(zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) })
				try { assertEquals(android.graphics.Color.RED, decoded.getPixel(0, 0)) }
				finally { decoded.recycle() }
			}
			val unrelated = File(container, "Unrelated.cbz").apply { writeBytes(bytes) }
			scan(NormalObservationStatus.PRESENT)
			assertEquals(special.personalKey, rows("unrelated added") { it.size == 4 }
				.single { it.chapter.id == special.chapter.id }.personalKey)
			assertTrue(unrelated.delete())
			scan(NormalObservationStatus.PRESENT)
			rows("unrelated removed") { it.size == 3 }

			// Corroborated local key plus actual Reader bookmark permit renamed-only.
			val specialPersonal = ChapterPersonalEntity(manga.id, special.personalKey.source, special.personalKey.url, 4, "Independent note")
			val bookmark = BookmarkEntity(manga.id, page.id, special.chapter.id, 0, 0, page.url, 100L, 0.5f)
			db.getChapterPersonalDao().upsert(specialPersonal)
			db.getBookmarksDao().insert(bookmark)
			assertTrue(sibling.delete())
			scan(NormalObservationStatus.PRESENT)
			val renamedOnly = rows("corroborated renamed-only") {
				it.size == 3 && it.count { row -> row.isDownloaded } == 1 && it.any { row -> row.chapter.id == special.chapter.id && row.isDownloaded }
			}
			assertEquals(special.personalKey, renamedOnly.single { it.chapter.id == special.chapter.id }.personalKey)
			assertChapterIcon(second.id, false)
			assertChapterIcon(special.chapter.id, true)
			assertCover(true)
			assertArrayEquals(replacementBytes, specialFile.readBytes())

			// Corrupt but identifiable local artifact retains the existing Downloaded icon.
			val corrupt = byteArrayOf(1, 2, 3, 4)
			specialFile.writeBytes(corrupt)
			scan(NormalObservationStatus.PRESENT)
			assertChapterIcon(special.chapter.id, true)
			assertCover(true)
			val corruptCopy = requireNotNull(reader.resolve(manga, space))
			val corruptLoader = chapterLoader(reader)
			try {
				corruptLoader.init(MangaDetails(manga, corruptCopy, null, null, true), space, true)
				assertTrue(runCatching { corruptLoader.loadSingleChapter(special.chapter.id) }.isFailure)
				assertFalse(corruptLoader.hasPages(special.chapter.id))
				assertFalse(corruptLoader.refreshChapterPages(special.chapter.id))
			} finally { corruptLoader.close() }
			assertArrayEquals(corrupt, specialFile.readBytes())
			assertTrue(specialFile.delete())
			scan(NormalObservationStatus.ABSENT)
			rows("empty sidecar-free parent") { it.size == 2 && it.none { row -> row.isDownloaded } }
			assertChapterIcon(first.id, false)
			assertChapterIcon(second.id, false)
			assertCover(false)
			assertNull(reader.resolve(manga, space))
			val empty = requireNotNull(normalDirectoryDiscovery.resolveCandidates(manga, space))
			assertTrue(empty.inventoryComplete && empty.unresolvedPaths.isEmpty() && empty.verified.isEmpty())
			assertNull(normalDirectoryDiscovery.verifySidecarFreeCandidate(manga, space, container))
			assertTrue(container.isDirectory)
			original.writeBytes(bytes)
			scan(NormalObservationStatus.PRESENT)
			rows("canonical restored") { it.size == 2 && it.any { row -> row.chapter.id == first.id && row.isDownloaded } }
			assertChapterIcon(first.id, true)
			assertCover(true)
			val otherRoot = File(root, "render-root-b").apply { check(mkdirs()) }
			destinations.setRoot(space, otherRoot)
			scan(NormalObservationStatus.ABSENT)
			rows("root B") { it.size == 2 && it.none { row -> row.isDownloaded } }
			assertChapterIcon(first.id, false)
			assertCover(false)
			assertNull(reader.resolve(manga, space))
			destinations.setRoot(space, root)
			scan(NormalObservationStatus.PRESENT)
			rows("root A revalidated") { it.size == 2 && it.any { row -> row.chapter.id == first.id && row.isDownloaded } }
			assertChapterIcon(first.id, true)
			assertCover(true)
			assertFalse(sidecar.exists())
			assertArrayEquals(bytes, original.readBytes())
			assertEquals(owner, db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manga.id))
			assertEquals(setOf(originalPersonal, specialPersonal), db.getChapterPersonalDao().findAll(listOf(manga.id)).toSet())
			assertEquals(listOf(bookmark), db.getBookmarksDao().findAll(manga.id))
		}
	}

	private fun favouritesViewModel(categoryId: Long = 0L): FavouritesListViewModel {
		lateinit var vm: FavouritesListViewModel
		instrumentation.runOnMainSync {
			vm = FavouritesListViewModel(
				SavedStateHandle(mapOf(AppRouter.KEY_ID to categoryId)), repository, mapper, markAsRead, filters,
				settings, data, events, search, contentType, display, index, classifier, unread, sources,
				detailsCache, downloadedSort, groups, duplicates, setSelection,
			)
			viewModels.put("favourites", vm)
			// Mirror the visible Fragment: list production is suspended until its UI is active.
			vm.setWorkActive(true)
		}
		return vm
	}

	private fun List<ListModel>.grid(id: Long) = filterIsInstance<MangaGridModel>().find { it.id == id }

	private suspend fun FavouritesListViewModel.awaitContent(
		stage: String, predicate: (List<ListModel>) -> Boolean,
	) {
		val result = withTimeoutOrNull(10_000) { content.first(predicate) }
		assertNotNull("$stage: content=${content.value}; downloaded=${classifier.getDownloadedIds(FavouriteSpace.NORMAL)}; roots=${destinations.readableRoots(FavouriteSpace.NORMAL)}", result)
	}

	private class GenericList(
		settings: AppSettings, data: MangaDataRepository, events: MutableSharedFlow<LocalManga?>,
		mapper: MangaListMapper, manga: Manga,
	) : MangaListViewModel(settings, data, events) {
		override val content: StateFlow<List<ListModel>> = observeListModeWithTriggers()
			.map { mapper.toListModelList(listOf(manga), ListMode.GRID) }
			.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
		override fun onRefresh() = Unit
		override fun onRetry() = Unit
	}

	private class CountingImageView(context: Context) : ImageView(context) {
		var visibilityWrites = 0
		var resourceWrites = 0
		override fun setVisibility(visibility: Int) { visibilityWrites++; super.setVisibility(visibility) }
		override fun setImageResource(resId: Int) { resourceWrites++; super.setImageResource(resId) }
	}

	private fun SharedPreferences.restore(values: Map<String, *>) = edit(commit = true) {
		clear()
		for ((key, value) in values) when (value) {
			is String -> putString(key, value)
			is Int -> putInt(key, value)
			is Long -> putLong(key, value)
			is Float -> putFloat(key, value)
			is Boolean -> putBoolean(key, value)
			is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
		}
	}
}
