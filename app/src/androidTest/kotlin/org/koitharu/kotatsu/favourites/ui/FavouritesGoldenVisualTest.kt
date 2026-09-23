package org.koitharu.kotatsu.favourites.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.main.ui.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.abs

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

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Before
	fun setUp() = runBlocking {
		hiltRule.inject()
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
			assertCanonicalGeometry(geometry)

			val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
			assertEquals(CANONICAL_SCREENSHOT_WIDTH_PX, screenshot.width)
			assertEquals(CANONICAL_SCREENSHOT_HEIGHT_PX, screenshot.height)

			val outDir = File(context.filesDir, "favourites-golden").apply { mkdirs() }
			File(outDir, "implementation.png").outputStream().use { output ->
				assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
			}
			File(outDir, "geometry.json").writeText(geometry.toJson().toString(2))
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
			AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
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

	private fun assertCanonicalGeometry(evidence: GeometryEvidence) {
		val density = evidence.density
		assertTrue("Expected canonical 320dpi density, got $density", abs(density - 2f) <= 0.05f)
		assertDpNear(MiyorareFavouritesVisualSpec.SEARCH_VISUAL_HEIGHT_DP, evidence.search.height(), density, 1.5f)
		assertDpNear(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_HEIGHT_DP, evidence.toggle.height(), density, 1.5f)
		assertDpNear(MiyorareFavouritesVisualSpec.CATEGORY_RAIL_HEIGHT_DP, evidence.categories.height(), density, 1.5f)

		assertTrue("Need at least three visible manga cards for geometry evidence", evidence.covers.size >= 3)
		val first = evidence.covers[0]
		val second = evidence.covers[1]
		val third = evidence.covers[2]
		val widthDp = first.width() / density
		val heightDp = first.height() / density
		assertTrue("Card width drifted: $widthDp dp", widthDp in 124f..129f)
		assertTrue(
			"Card ratio drifted: ${first.width().toFloat() / first.height()}",
			abs(first.width().toFloat() / first.height() - MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO) <= 0.015f,
		)
		assertTrue("Card height drifted: $heightDp dp", heightDp in 148f..152f)
		assertTrue(
			"First card outer edge drifted: ${first.left / density} dp",
			abs(first.left / density - MiyorareFavouritesVisualSpec.SCREEN_HORIZONTAL_MARGIN_DP) <= 2f,
		)
		assertDpNear(8f, second.left - first.right, density, 2f)
		assertDpNear(8f, third.left - second.right, density, 2f)

		if (evidence.quickActions.size >= 3) {
			val widths = evidence.quickActions.take(3).map { it.width() }
			assertTrue("Quick actions must share one responsive width: $widths", widths.max() - widths.min() <= 2)
		}
	}

	private fun assertDpNear(expectedDp: Float, actualPx: Int, density: Float, toleranceDp: Float) {
		val actualDp = actualPx / density
		assertTrue(
			"Expected $expectedDp dp (+/- $toleranceDp), got $actualDp dp",
			abs(actualDp - expectedDp) <= toleranceDp,
		)
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
