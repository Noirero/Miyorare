package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageView
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineStart
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
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.widgets.IconsView
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.details.data.DetailsNavigationCache
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.details.data.ChapterPersonalKey
import org.koitharu.kotatsu.details.data.ChapterProgressEntity
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
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
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.NormalDownloadDirectoryReconciler
import org.koitharu.kotatsu.local.data.NormalDownloadDirectoryDiscovery
import org.koitharu.kotatsu.local.data.NormalDownloadPriorIdentityReader
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.DownloadedMangaResolver
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import javax.inject.Inject

/** Uses the native list/filter and storage pipeline; no Details or source/network request. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DownloadedLocalStateTest {
	@get:Rule val hiltRule = HiltAndroidRule(this)
	@Inject lateinit var db: MangaDatabase
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var data: MangaDataRepository
	@Inject lateinit var index: LocalMangaIndex
	@Inject lateinit var local: LocalMangaRepository
	@Inject lateinit var resolver: DownloadedMangaResolver
	@Inject lateinit var normalDownloadScope: NormalDownloadScope
	@Inject internal lateinit var normalDirectoryDiscovery: NormalDownloadDirectoryDiscovery
	@Inject lateinit var deleteLocal: DeleteLocalMangaUseCase
	@Inject lateinit var ownership: FavouriteDownloadOwnershipIndex
	@Inject lateinit var destinations: DownloadDestinationStore
	@Inject lateinit var classifier: DownloadedContentClassifier
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

		val newestFirst = listOf(newer.id, older.id, withoutMembership.id, removed.id)
		assertEquals(newestFirst, ordered(ListSortOrder.NEWEST))
		assertEquals(listOf(older.id, newer.id, withoutMembership.id, removed.id), ordered(ListSortOrder.OLDEST))

		data.storeManga(older, replaceExisting = true, detailsFetched = true)
		assertTrue(data.getDetailsUpdatedAt(older.id) > data.getDetailsUpdatedAt(newer.id))
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
		assertEquals(setOf(manga.id, nonFavourite.id), shelfIds())

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
