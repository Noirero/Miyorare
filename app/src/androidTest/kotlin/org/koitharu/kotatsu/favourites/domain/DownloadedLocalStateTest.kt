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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.widgets.IconsView
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.details.data.DetailsNavigationCache
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListViewModel
import org.koitharu.kotatsu.history.domain.MarkAsReadUseCase
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
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
		root = File(context.cacheDir, "batch11-${System.nanoTime()}").apply { check(mkdirs()) }
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

	@Test fun completionUpdatesBadgeAndBothNativeFiltersWithoutDetails() = runBlocking {
		val remote = remote(810_011L)
		val notDownloaded = remote(810_016L)
		favourite(remote)
		favourite(notDownloaded)
		val vm = favouritesViewModel()
		withTimeout(10_000) { vm.content.first { it.grid(remote.id)?.isSaved == false } }
		val indexJob = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { events.collect(index) }
		val ownershipJob = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { events.collect(ownership) }
		try {
			val copy = download(root, remote)
			// Match the already-authoritative DownloadWorker: durable ownership and alias precede completion.
			db.getFavouriteDownloadIndexDao().upsert(listOf(FavouriteDownloadIndexEntity(remote.id, 0, copy.file.canonicalPath)))
			local.rememberDownloadedIdentity(remote, copy)
			events.emit(copy)
			withTimeout(10_000) { vm.content.first { it.grid(remote.id)?.isSaved == true } }
			vm.setFilterOption(ListFilterOption.Downloaded, true)
			withTimeout(10_000) { vm.content.first { it.grid(remote.id)?.isSaved == true && it.grid(notDownloaded.id) == null } }
			vm.setFilterOption(ListFilterOption.NOT_DOWNLOADED, true)
			withTimeout(10_000) { vm.content.first { it.grid(remote.id) == null && it.grid(notDownloaded.id)?.isSaved == false } }
			vm.setFilterOption(ListFilterOption.NOT_DOWNLOADED, false)
			withTimeout(10_000) { vm.content.first { it.grid(remote.id)?.isSaved == true && it.grid(notDownloaded.id)?.isSaved == false } }

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
		val titleOnly = normal.copy(id = 810_015L, chapters = emptyList())
		favourite(titleOnly)
		assertTrue(classifier.getDownloadedIds(FavouriteSpace.NORMAL, listOf(normal.id)).isEmpty())
		val indexJob = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { events.collect(index) }
		val ownershipJob = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { events.collect(ownership) }
		try {
			reconciler.reconcileOnce()
			withTimeout(10_000) {
				while (classifier.getDownloadedIds(FavouriteSpace.PRIVATE, listOf(privateManga.id)).isEmpty()) delay(20)
			}
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

	private suspend fun favourite(manga: Manga, space: FavouriteSpace = FavouriteSpace.NORMAL) {
		data.storeManga(manga, replaceExisting = true)
		val category = repository.createCategory("Batch 11", ListSortOrder.NEWEST, false, false, true, space)
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

	private fun favouritesViewModel(): FavouritesListViewModel {
		lateinit var vm: FavouritesListViewModel
		instrumentation.runOnMainSync {
			vm = FavouritesListViewModel(
				SavedStateHandle(mapOf(AppRouter.KEY_ID to 0L)), repository, mapper, markAsRead, filters,
				settings, data, events, search, contentType, display, index, classifier, unread, sources,
				detailsCache, downloadedSort, groups, duplicates,
			)
			viewModels.put("favourites", vm)
		}
		return vm
	}

	private fun List<ListModel>.grid(id: Long) = filterIsInstance<MangaGridModel>().find { it.id == id }

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
