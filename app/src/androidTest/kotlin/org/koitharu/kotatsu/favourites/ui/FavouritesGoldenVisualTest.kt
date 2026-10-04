package org.koitharu.kotatsu.favourites.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.favourites.domain.FavouriteDownloadOwnershipIndex
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.ui.adapter.MangaListAdapter
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.TIP_UI_SCALING
import org.koitharu.kotatsu.local.data.LocalFavouritesRepository
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

/**
 * Canonical visual-evidence capture for the Normal Disukai screen.
 *
 * CI configures the emulator to 864x1536 px at 320 dpi: the 432x768 dp canonical viewport from
 * the approved visual reference. This seeds deterministic local favourites, checks major geometry
 * in dp, then stores a full screenshot and measured bounds for overlay analysis.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FavouritesGoldenVisualTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var database: MangaDatabase

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var displayPreferences: FavouriteDisplayPreferences

	@Inject
	lateinit var contentTypeStore: FavouriteContentTypeStore

	@Inject lateinit var favouritesRepository: FavouritesRepository
	@Inject lateinit var localFavouritesRepository: LocalFavouritesRepository
	@Inject lateinit var localRepository: LocalMangaRepository
	@Inject lateinit var localIndex: LocalMangaIndex
	@Inject lateinit var ownershipIndex: FavouriteDownloadOwnershipIndex
	@Inject lateinit var destinations: DownloadDestinationStore
	@Inject lateinit var historyRepository: HistoryRepository

	private var goldenCategoryId = 0L

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Before
	fun setUp() = runBlocking {
		hiltRule.inject()

		// The production Application implements Configuration.Provider, but Hilt instrumentation
		// swaps it for HiltTestApplication. The manifest intentionally removes WorkManager's default
		// initializer, so initialize the test process explicitly before MainActivity/ViewModels ask
		// for WorkManager. This is test-harness setup only; production startup behavior is unchanged.
		runCatching { WorkManager.getInstance(context) }.getOrElse {
			WorkManager.initialize(context, Configuration.Builder().build())
			WorkManager.getInstance(context)
		}

		database.clearAllTables()

		settings.isOnboardingCompleted = true
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		settings.setMiyorareThemePreset(MiyorareThemePreset.MIYORARE)
		settings.favoritesListMode = ListMode.GRID
		settings.mainNavItems = listOf(
			NavItem.FAVORITES,
			NavItem.UPDATED,
			NavItem.HISTORY,
			NavItem.EXPLORE,
		)
		settings.isTitleOverCover = true
		// The canonical reference has no first-run UI-scaling tip above the quick actions.
		settings.closeTip(TIP_UI_SCALING)

		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_NAV_LEGACY, true)
			.putBoolean(AppSettings.KEY_NAV_LABELS, true)
			.putBoolean(AppSettings.KEY_HIDE_STATUS_BAR, false)
			.commit()

		contentTypeStore.activateSpace(FavouriteSpace.NORMAL)
		contentTypeStore.setSelectedType(FavouriteContentType.MANGA, FavouriteSpace.NORMAL)
		contentTypeStore.setLastCategoryId(FavouriteContentType.MANGA, 0L, FavouriteSpace.NORMAL)

		displayPreferences.setListMode(FavouriteContentType.MANGA, ListMode.GRID)
		displayPreferences.setGridColumns(FavouriteContentType.MANGA, 3)
		displayPreferences.setGridSize(FavouriteContentType.MANGA, 100)
		displayPreferences.setTitleOverCover(FavouriteContentType.MANGA, true)
		displayPreferences.setGridSpacingIncreased(FavouriteContentType.MANGA, false)
		displayPreferences.setShowCategoryTabs(FavouriteContentType.MANGA, true)
		displayPreferences.setShowCategoryCounts(FavouriteContentType.MANGA, true)
		displayPreferences.setShowLanguage(FavouriteContentType.MANGA, true)

		val categoryId = database.getFavouriteCategoriesDao().insert(
			FavouriteCategoryEntity(
				categoryId = 0,
				createdAt = 1L,
				sortKey = 1,
				title = "Golden",
				order = "NEWEST",
				track = true,
				downloadNewChapters = false,
				isVisibleInLibrary = true,
				deletedAt = 0L,
				space = FavouriteSpace.NORMAL.dbValue,
			),
		)
		goldenCategoryId = categoryId

		GOLDEN_TITLES.forEachIndexed { index, title ->
			val id = 10_000L + index
			database.getMangaDao().upsert(
				MangaEntity(
					id = id,
					title = title,
					altTitles = null,
					url = "/golden/$id",
					publicUrl = "https://fixture.invalid/golden/$id",
					rating = -1f,
					isNsfw = false,
					contentRating = null,
					coverUrl = "",
					largeCoverUrl = null,
					state = null,
					authors = null,
					description = null,
					source = "MIHON_123",
					sourceTitle = "Golden Source",
				),
				emptyList(),
			)
			database.getFavouritesDao().upsert(
				FavouriteEntity(
					mangaId = id,
					categoryId = categoryId,
					sortKey = index,
					isPinned = false,
					createdAt = (GOLDEN_TITLES.size - index).toLong(),
					deletedAt = 0L,
				),
			)
		}
	}

	@Test
	fun captureCanonicalNormalFavourites() {
		AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("id-ID"))
		val activity = instrumentation.startActivitySync(
			Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as MainActivity

		try {
			waitUntilGoldenScreenReady(activity)
			SystemClock.sleep(500)
			instrumentation.waitForIdleSync()

			val geometryRef = AtomicReference<GeometryEvidence>()
			instrumentation.runOnMainSync {
				geometryRef.set(captureGeometry(activity))
			}
			val geometry = checkNotNull(geometryRef.get())

			val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
			val outDir = File(context.filesDir, "favourites-golden").apply { mkdirs() }
			File(outDir, "implementation.png").outputStream().use { output ->
				assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
			}
			val geometryJson = geometry.toJson().toString(2)
			File(outDir, "geometry.json").writeText(geometryJson)
			writeFinalEvidenceToDownloads(screenshot, geometryJson)
			println("FAVOURITES_GOLDEN_GEOMETRY=${geometry.toJson()}")

			// Persist evidence first so a geometry assertion still leaves a screenshot and exact
			// measurements for the next correction instead of forcing another blind emulator cycle.
			assertEquals(CANONICAL_SCREENSHOT_WIDTH_PX, screenshot.width)
			assertEquals(CANONICAL_SCREENSHOT_HEIGHT_PX, screenshot.height)
			logCanonicalGeometryDrifts(geometry)
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
			AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
		}
	}

	/** Exercises the actual selector and physical chapter deletion, including a non-Favourite
	 * download and an identically titled Local manga. Runs in the existing final visual workflow.
	 */
	@Test
	fun collectionsKeepMembershipFilesAndReaderProgressIndependent() = runBlocking {
		val root = File(context.filesDir, "collection-acceptance").apply {
			deleteRecursively()
			check(mkdirs())
		}
		val previousRoot = destinations.configuredRoot(FavouriteSpace.NORMAL)
		val previousFolders = settings.userSpecifiedMangaDirectories.toSet()
		val hidden = displayPreferences.currentHiddenVirtualCategoryIds(FavouriteSpace.NORMAL, FavouriteContentType.MANGA)
		var activity: MainActivity? = null
		try {
			destinations.setRoot(FavouriteSpace.NORMAL, root)
			val page = File(root, "fixture.png")
			page.outputStream().use {
				val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
				check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
				bitmap.recycle()
			}
			val remote = checkNotNull(database.getMangaDao().find(10_000L)).toManga()
			val downloadedFavourite = createDownload(root, remote, page)
			val nonFavourite = remote.copy(id = 12_001L, title = "Offline Non-Favourite", url = "/fixture/12001")
			val template = checkNotNull(database.getMangaDao().find(remote.id)).manga
			database.getMangaDao().upsert(template.copy(id = nonFavourite.id, title = nonFavourite.title, url = nonFavourite.url), emptyList())
			val downloadedNonFavourite = createDownload(root, nonFavourite, page)
			val localFolder = File(root, "local/${remote.title}").apply { check(mkdirs()) }
			ZipOutputStream(File(localFolder, "Chapter 1.cbz").outputStream()).use {
				it.putNextEntry(ZipEntry("001.png"))
				page.inputStream().use { input -> input.copyTo(it) }
				it.closeEntry()
			}
			val local = LocalMangaParser(localFolder).getManga(withDetails = true)
			localIndex.put(local)
			// A legacy Local membership must be excluded from every ordinary Favourite category.
			database.getFavouritesDao().upsert(FavouriteEntity(local.manga.id, goldenCategoryId, 100, false, 1L, 0L))
			localFavouritesRepository.refresh(FavouriteSpace.NORMAL)
			val localChapter = checkNotNull(local.manga.chapters).single()
			assertEquals(1, localRepository.getPages(localChapter).size)
			historyRepository.addOrUpdate(local.manga, localChapter.id, 0, 25, 0.5f, force = true)
			val progress = checkNotNull(historyRepository.getOne(local.manga))
			assertFalse(favouritesRepository.isFavorite(nonFavourite.id))
			assertTrue(local.manga.id != remote.id)

			// Old virtual-tab visibility settings must not remove the new collections.
			for (id in listOf(DOWNLOADED_FAVOURITES_CATEGORY_ID, LOCAL_FAVOURITES_CATEGORY_ID)) {
				displayPreferences.setVirtualCategoryVisible(FavouriteSpace.NORMAL, FavouriteContentType.MANGA, id, false)
			}
			val screen = instrumentation.startActivitySync(
				Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			) as MainActivity
			activity = screen
			waitUntilGoldenScreenReady(screen)
			instrumentation.runOnMainSync {
				val tabs = screen.findViewById<TabLayout>(R.id.tabs)
				val golden = (0 until tabs.tabCount).mapNotNull(tabs::getTabAt).first { it.text?.startsWith("Golden") == true }
				tabs.selectTab(golden)
			}
			waitForCollection(screen, "Favourite subtab", setOf(remote.id), setOf(local.manga.id, nonFavourite.id))
			assertEquals(goldenCategoryId, contentTypeStore.getLastCategoryId(FavouriteContentType.MANGA))

			selectCollection(screen, R.string.downloaded)
			waitForCollection(screen, "Downloaded", setOf(remote.id, nonFavourite.id), setOf(local.manga.id))
			instrumentation.runOnMainSync { assertEquals(View.GONE, screen.findViewById<View>(R.id.tabs).visibility) }
			val undo = favouritesRepository.removeFromFavourites(listOf(remote.id))
			assertFalse(favouritesRepository.isFavorite(remote.id))
			assertTrue(downloadedFavourite.file.exists())
			waitForCollection(screen, "Unfavourite retains download", setOf(remote.id, nonFavourite.id), setOf(local.manga.id))
			undo.reverse()
			localRepository.deleteChapters(downloadedFavourite.manga, checkNotNull(downloadedFavourite.manga.chapters).mapTo(HashSet()) { it.id })
			assertFalse(downloadedFavourite.file.exists())
			assertTrue(favouritesRepository.isFavorite(remote.id))
			waitForCollection(screen, "Last chapter removes only download", setOf(nonFavourite.id), setOf(remote.id))
			localRepository.deleteChapters(downloadedNonFavourite.manga, checkNotNull(downloadedNonFavourite.manga.chapters).mapTo(HashSet()) { it.id })
			waitForEmptyCollection(screen, R.string.downloads_collection_empty_title)

			selectCollection(screen, R.string.local_storage)
			waitForCollection(screen, "Local shelf", setOf(local.manga.id), setOf(remote.id, nonFavourite.id))
			assertEquals(progress, historyRepository.getOne(local.manga))
			assertEquals(1, localRepository.getPages(localChapter).size)
			selectCollection(screen, R.string.favourites)
			waitForCollection(screen, "Restored Favourite subtab", setOf(remote.id), setOf(local.manga.id, nonFavourite.id))
			assertEquals(goldenCategoryId, contentTypeStore.getLastCategoryId(FavouriteContentType.MANGA))
			assertEquals(progress, historyRepository.getOne(local.manga))
			selectCollection(screen, R.string.local_storage)
			assertTrue(localRepository.delete(local.manga))
			localFavouritesRepository.refresh(FavouriteSpace.NORMAL)
			waitForEmptyCollection(screen, R.string.local_collection_empty_title)
			println("COLLECTION_ACCEPTANCE: non-Favourite download, unfavourite, last-chapter deletion, Local identity, reader pages/history, hidden legacy tabs, subtab restore and empty states passed")
		} finally {
			activity?.let { instrumentation.runOnMainSync { it.finish() } }
			for (id in listOf(DOWNLOADED_FAVOURITES_CATEGORY_ID, LOCAL_FAVOURITES_CATEGORY_ID)) {
				displayPreferences.setVirtualCategoryVisible(FavouriteSpace.NORMAL, FavouriteContentType.MANGA, id, id !in hidden)
			}
			destinations.setRoot(FavouriteSpace.NORMAL, previousRoot)
			destinations.forgetRoot(root)
			settings.userSpecifiedMangaDirectories = previousFolders
			root.deleteRecursively()
		}
	}

	private suspend fun createDownload(root: File, manga: Manga, page: File): LocalManga {
		val chapter = MangaChapter(
			id = manga.id * 10, title = "Chapter 1", number = 1f, volume = 0,
			url = "/chapter/${manga.id}", scanlator = null, uploadDate = 1L, branch = null, source = manga.source,
		)
		val output = LocalMangaOutput.getOrCreate(root, manga, DownloadFormat.MULTIPLE_CBZ)
		try {
			output.addPage(IndexedValue(0, chapter), page, 0, MimeType("image/png"))
			check(output.flushChapter(chapter))
			output.finish()
		} finally {
			output.close()
		}
		return LocalMangaParser(output.rootFile).getManga(withDetails = true).also {
			localIndex.put(it)
			// Match DownloadWorker: sidecar-free folders have a filesystem Local id, while physical
			// ownership and the reconnect alias preserve the independent source manga identity.
			database.getFavouriteDownloadIndexDao().upsert(listOf(FavouriteDownloadIndexEntity(
				manga.id, FavouriteSpace.NORMAL.dbValue, it.file.canonicalPath,
			)))
			localRepository.rememberDownloadedIdentity(manga, it)
			ownershipIndex.emit(it)
		}
	}

	private fun selectCollection(activity: MainActivity, titleRes: Int) {
		instrumentation.runOnMainSync { activity.findViewById<View>(R.id.button_collection_selector).performClick() }
		instrumentation.waitForIdleSync()
		val title = activity.getString(titleRes)
		val nodes = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(title)
		val node = checkNotNull(nodes.firstOrNull { it.text?.toString() == title }) { "Missing collection option: $title" }
		val target = if (node.isClickable) node else checkNotNull(node.parent)
		assertTrue("Cannot select $title", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
		instrumentation.waitForIdleSync()
	}

	private fun currentItems(activity: MainActivity): List<ListModel> {
		fun find(fragments: List<Fragment>): FavouritesContainerFragment? {
			for (fragment in fragments) {
				if (fragment is FavouritesContainerFragment && fragment.isVisible) return fragment
				find(fragment.childFragmentManager.fragments)?.let { return it }
			}
			return null
		}
		return (find(activity.supportFragmentManager.fragments)?.recyclerView?.adapter as? MangaListAdapter)?.items.orEmpty()
	}

	private fun waitForCollection(activity: MainActivity, label: String, included: Set<Long>, excluded: Set<Long>) =
		waitForItems(activity, label) { items ->
			val ids = items.filterIsInstance<MangaListModel>().mapTo(HashSet()) { it.id }
			ids.containsAll(included) && ids.none { it in excluded }
		}

	private fun waitForEmptyCollection(activity: MainActivity, title: Int) =
		waitForItems(activity, "Empty collection: ${activity.getString(title)}") { items ->
			items.any { it is EmptyState && it.textPrimary == title }
		}

	private fun waitForItems(activity: MainActivity, label: String, matches: (List<ListModel>) -> Boolean) {
		val deadline = SystemClock.elapsedRealtime() + 20_000L
		var items = emptyList<ListModel>()
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			instrumentation.runOnMainSync { items = currentItems(activity).toList() }
			if (matches(items)) return
			SystemClock.sleep(100)
		}
		assertTrue("$label did not settle: $items", matches(items))
	}

	private fun writeFinalEvidenceToDownloads(screenshot: Bitmap, geometryJson: String) {
		val resolver = context.contentResolver
		val relativePath = "Download/miyorare-favourites-golden/"
		fun replace(name: String, mimeType: String, write: (java.io.OutputStream) -> Unit) {
			resolver.delete(
				MediaStore.Downloads.EXTERNAL_CONTENT_URI,
				"${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
				arrayOf(relativePath, name),
			)
			val values = ContentValues().apply {
				put(MediaStore.MediaColumns.DISPLAY_NAME, name)
				put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
				put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
			}
			val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
			resolver.openOutputStream(uri, "w").use { output ->
				write(checkNotNull(output))
			}
		}
		replace("implementation.png", "image/png") { output ->
			check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
		}
		replace("geometry.json", "application/json") { output ->
			output.write(geometryJson.toByteArray())
		}
	}

	private fun waitUntilGoldenScreenReady(activity: MainActivity) {
		val ready = AtomicBoolean(false)
		val deadline = SystemClock.elapsedRealtime() + 20_000L
		while (!ready.get() && SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			instrumentation.runOnMainSync {
				val recycler = activity.findViewById<RecyclerView>(R.id.recyclerView)
				val covers = recycler?.collectVisibleCoverRects().orEmpty()
				ready.set(
					activity.findViewById<android.view.View>(R.id.toggle_content_type)?.isLaidOut == true &&
						activity.findViewById<android.view.View>(R.id.tabs)?.isLaidOut == true &&
						covers.size >= 6,
				)
			}
			if (!ready.get()) SystemClock.sleep(150)
		}
		assertTrue("Normal Favourites did not reach the deterministic golden state", ready.get())
	}

	private fun captureGeometry(activity: MainActivity): GeometryEvidence {
		val density = activity.resources.displayMetrics.density
		val recycler = checkNotNull(activity.findViewById<RecyclerView>(R.id.recyclerView))
		return GeometryEvidence(
			density = density,
			search = checkNotNull(activity.findViewById<android.view.View>(R.id.search_bar)).screenRect(),
			toggle = checkNotNull(activity.findViewById<android.view.View>(R.id.toggle_content_type)).screenRect(),
			categories = checkNotNull(activity.findViewById<android.view.View>(R.id.tabs)).screenRect(),
			recycler = recycler.screenRect(),
			covers = recycler.collectVisibleCoverRects(),
			quickActions = recycler.collectQuickActionRects(),
			bottomNav = checkNotNull(activity.findViewById<android.view.View>(R.id.bottomNav)).screenRect(),
		)
	}

	private fun logCanonicalGeometryDrifts(evidence: GeometryEvidence) {
		val density = evidence.density
		val drifts = ArrayList<String>()
		fun near(name: String, expectedDp: Float, actualPx: Int, toleranceDp: Float) {
			val actualDp = actualPx / density
			if (abs(actualDp - expectedDp) > toleranceDp) {
				drifts += "$name expected $expectedDp dp (+/- $toleranceDp), got $actualDp dp"
			}
		}
		near("search height", MiyorareFavouritesVisualSpec.SEARCH_VISUAL_HEIGHT_DP, evidence.search.height(), 1.5f)
		near("toggle height", MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_HEIGHT_DP, evidence.toggle.height(), 1.5f)
		near("category height", MiyorareFavouritesVisualSpec.CATEGORY_RAIL_HEIGHT_DP, evidence.categories.height(), 1.5f)
		val first = evidence.covers.firstOrNull()
		if (first != null) {
			val widthDp = first.width() / density
			val heightDp = first.height() / density
			val ratio = first.width().toFloat() / first.height().coerceAtLeast(1)
			if (widthDp !in 124f..129f) drifts += "card width $widthDp dp"
			if (heightDp !in 148f..152f) drifts += "card height $heightDp dp"
			if (abs(ratio - MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO) > 0.015f) {
				drifts += "card ratio $ratio"
			}
			if (abs(first.left / density - MiyorareFavouritesVisualSpec.SCREEN_HORIZONTAL_MARGIN_DP) > 2f) {
				drifts += "first card outer edge " + (first.left / density) + " dp"
			}
		}
		if (evidence.quickActions.size >= 2) {
			val widths = evidence.quickActions.map { it.width() }
			if (widths.max() - widths.min() > 2) drifts += "quick action widths $widths"
		}
		println("FAVOURITES_GOLDEN_DRIFTS=" + JSONArray(drifts))
	}

	private fun RecyclerView.collectVisibleCoverRects(): List<Rect> = buildList {
		for (index in 0 until childCount) {
			val cover = getChildAt(index).findViewById<android.view.View>(R.id.imageView_cover) ?: continue
			if (cover.width > 0 && cover.height > 0) add(cover.screenRect())
		}
	}.sortedWith(compareBy<Rect>({ it.top }, { it.left }))

	private fun RecyclerView.collectQuickActionRects(): List<Rect> {
		for (index in 0 until childCount) {
			val chips = getChildAt(index).findViewById<android.view.ViewGroup>(R.id.chips_tags) ?: continue
			return buildList {
				for (chipIndex in 0 until chips.childCount) {
					val chip = chips.getChildAt(chipIndex)
					if (chip.width > 0 && chip.height > 0) add(chip.screenRect())
				}
			}
		}
		return emptyList()
	}

	private fun android.view.View.screenRect(): Rect {
		val location = IntArray(2)
		getLocationOnScreen(location)
		return Rect(location[0], location[1], location[0] + width, location[1] + height)
	}

	private data class GeometryEvidence(
		val density: Float,
		val search: Rect,
		val toggle: Rect,
		val categories: Rect,
		val recycler: Rect,
		val covers: List<Rect>,
		val quickActions: List<Rect>,
		val bottomNav: Rect,
	) {
		fun toJson() = JSONObject().apply {
			put("density", density)
			put("search", search.toJson())
			put("toggle", toggle.toJson())
			put("categories", categories.toJson())
			put("recycler", recycler.toJson())
			put("covers", JSONArray(covers.map { it.toJson() }))
			put("quickActions", JSONArray(quickActions.map { it.toJson() }))
			put("bottomNav", bottomNav.toJson())
		}

		private fun Rect.toJson() = JSONObject()
			.put("left", left)
			.put("top", top)
			.put("right", right)
			.put("bottom", bottom)
			.put("width", width())
			.put("height", height())
	}

	private companion object {
		const val CANONICAL_SCREENSHOT_WIDTH_PX = 864
		const val CANONICAL_SCREENSHOT_HEIGHT_PX = 1536

		val GOLDEN_TITLES = listOf(
			"The Returned C-Rank Tank Won...",
			"The Greatest Estate Developer",
			"Revenge Of The Iron-Blooded Swo...",
			"Return Of The Mount Hua Sect",
			"The Villain Of Destiny",
			"Infinite Mage",
			"Swordmaster's Youngest Son",
			"Mercenary Enrollment",
			"Eleceed",
			"Golden Manga 10",
			"Golden Manga 11",
			"Golden Manga 12",
		)
	}
}
