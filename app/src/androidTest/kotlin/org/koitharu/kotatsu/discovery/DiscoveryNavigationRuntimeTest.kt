package org.koitharu.kotatsu.discovery

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.lifecycle.ViewModelProvider
import org.koitharu.kotatsu.local.ui.LocalListViewModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalPanelModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalCollectionHeaderModel
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration as AndroidConfiguration
import org.koitharu.kotatsu.core.ui.widgets.DragNavigationRailView
import org.koitharu.kotatsu.core.ui.widgets.NavigationTabDrag
import androidx.compose.ui.geometry.Rect as SlotRect
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import org.koitharu.kotatsu.local.ui.LocalListFragment
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.*
import org.koitharu.kotatsu.discovery.data.*
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.discovery.ui.DiscoveryHubFragment
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService as Service
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicInteger

@HiltAndroidTest
@UninstallModules(DiscoveryCatalogModule::class)
class DiscoveryNavigationRuntimeTest {
	@get:Rule(order = 0) val hilt = HiltAndroidRule(this)
	@get:Rule(order = 1) val compose = createEmptyComposeRule()
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var alternatives: org.koitharu.kotatsu.alternatives.domain.AlternativesUseCase
	@Inject lateinit var sources: org.koitharu.kotatsu.explore.data.MangaSourcesRepository
	@Inject lateinit var searchPreferences: org.koitharu.kotatsu.search.domain.SearchSourcePreferences
	private val calls = AtomicInteger()
	private var knownForDetails: List<Manga>? = null
	private val provider = object : TrackerCatalogProvider {
		override val catalogService = Service.ANILIST
		override val catalogCapabilities = CatalogCapability.entries.toSet()
		override suspend fun home(filter: CatalogFilter, allowAdult: Boolean): TrackerCatalogHome {
			calls.incrementAndGet()
			return TrackerCatalogHome(filter.kinds.flatMap { kind -> listOf(CatalogSectionType.TRENDING, CatalogSectionType.POPULAR, CatalogSectionType.TOP_RATED).map { type ->
				TrackerCatalogSection(CatalogSectionKey(Service.ANILIST, kind, type), (1..12).map { TrackerCatalogItem(TrackerCatalogKey(Service.ANILIST, kind, it.toString()), "Fixture $kind $it", adult = false) })
			} })
		}
		override suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int) = TrackerCatalogPage(emptyList())
		override suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean) = TrackerCatalogDetails(TrackerCatalogItem(key, "Metadata title", adult = false))
		override suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int) = TrackerCatalogPage(emptyList())
	}
	@BindValue @JvmField val repository = TrackerCatalogRepository(setOf(provider), object : CatalogSnapshotStore {
		override fun read(key: CatalogHomeRequest, now: Long): TrackerCatalogHome? = null
		override fun write(key: CatalogHomeRequest, home: TrackerCatalogHome) = true
	})
	@BindValue @JvmField val sourceResolution: CatalogSourceResolution = object : CatalogSourceResolution {
		override suspend fun known(key: TrackerCatalogKey): List<Manga> = checkNotNull(knownForDetails) { "Home must not resolve sources" }
		override suspend fun availableSources(kind: CatalogMediaKind) = emptyList<org.koitharu.kotatsu.discovery.domain.CatalogReadingSource>()
		override suspend fun search(details: TrackerCatalogDetails, scope: org.koitharu.kotatsu.discovery.domain.CatalogSearchScope): org.koitharu.kotatsu.discovery.domain.CatalogReadingSearch {
			checkNotNull(knownForDetails) { "Home must not search sources" }
			return org.koitharu.kotatsu.discovery.domain.CatalogReadingSearch(kotlinx.coroutines.flow.emptyFlow(), canExpand = false)
		}
		override suspend fun canOpen(manga: Manga, kind: CatalogMediaKind) = manga in knownForDetails.orEmpty()
	}
	private val app = InstrumentationRegistry.getInstrumentation().targetContext
	private var previous: Map<String, *> = emptyMap<String, Any>()
	@Before fun setup() {
		hilt.inject()
		previous = PreferenceManager.getDefaultSharedPreferences(app).all
		runCatching { WorkManager.getInstance(app) }.getOrElse { WorkManager.initialize(app, Configuration.Builder().build()) }
		settings.isOnboardingCompleted = true
		settings.mainNavItems = listOf(NavItem.HISTORY, NavItem.EXPLORE, NavItem.LOCAL)
		settings.discoveryProvider = Service.ANILIST.name; settings.discoveryFilter = CatalogFilter.ALL.name
		PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.KEY_TRACKER_ENABLED, true).commit()
	}
	@After fun cleanup() {
		val edit = PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
		for ((key, value) in previous) when (value) {
			is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
			is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
		}
		edit.commit()
	}
	private fun launch() = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
	private fun showHub(scenario: ActivityScenario<MainActivity>) {
		scenario.onActivity { it.bottomNav!!.selectedItemId = R.id.nav_discovery_hub }
		compose.waitUntil(10000) { compose.onAllNodesWithTag("catalog_spotlight").fetchSemanticsNodes().isNotEmpty() }
	}
	// Matched pre-feature experiment: keep this method body identical on both source revisions.
	// Observed settle after a fixed dwell is not input-to-photon latency or exact animation duration.
	@Test fun matchedPreFeatureHubTapFrames() = matchedPreFeatureTapFrames(NavItem.DISCOVERY_HUB, "hub")
	@Test fun matchedPreFeatureLocalTapFrames() = matchedPreFeatureTapFrames(NavItem.LOCAL, "local")

	private fun matchedPreFeatureTapFrames(destination: NavItem, route: String) {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
			instrumentation.uiAutomation.executeShellCommand(command),
		).bufferedReader().use { it.readText().trim() }
		val scaleKeys = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
		val previousScales = scaleKeys.associateWith { shell("settings get global $it") }
		val previousNight = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
		try {
			for (key in scaleKeys) {
				shell("settings put global $key 1")
				assertEquals(1f, android.provider.Settings.Global.getFloat(app.contentResolver, key, -1f), 0.01f)
			}
			instrumentation.runOnMainSync {
				androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
			}
			settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
			settings.mainNavItems = listOf(NavItem.LOCAL, NavItem.HISTORY, NavItem.EXPLORE)
			// Identical fixture keys; the pre-feature build does not consume the two new preferences.
			assertTrue(PreferenceManager.getDefaultSharedPreferences(app).edit()
				.putBoolean(AppSettings.KEY_NAV_LEGACY, false)
				.putBoolean(AppSettings.KEY_NAV_LABELS, true)
				.putString("navigation_motion", "classic_glide")
				.putString("screen_transition", "directional_slide").commit())
			launch().use { scenario ->
				compose.waitForIdle()
				lateinit var window: Window
				var refreshHz = 0f
				var actualNight = 0
				var screenOffset = Offset.Zero
				scenario.onActivity { activity ->
					window = activity.window
					val decor = window.decorView
					refreshHz = decor.display?.refreshRate ?: 0f
					actualNight = activity.resources.configuration.uiMode and AndroidConfiguration.UI_MODE_NIGHT_MASK
					val screen = IntArray(2).also { decor.getLocationOnScreen(it) }
					val inWindow = IntArray(2).also { decor.getLocationInWindow(it) }
					screenOffset = Offset((screen[0] - inWindow[0]).toFloat(), (screen[1] - inWindow[1]).toFloat())
				}
				assertTrue("A measured display refresh rate is required", refreshHz > 0f)
				assertEquals(AndroidConfiguration.UI_MODE_NIGHT_YES, actualNight)
				fun center(item: NavItem) = compose.onNodeWithContentDescription(app.getString(item.navTitle))
					.fetchSemanticsNode().boundsInWindow.center + screenOffset
				val favoritesPoint = center(NavItem.FAVORITES)
				val destinationPoint = center(destination)
				assertTrue("Distinct visible tab centers are required", favoritesPoint != destinationPoint)
				val prefix = "motion=classic_glide actual_night=$actualNight refresh_hz=$refreshHz scale=1 scenario=matched_pre_feature_${route}_tap"
				Log.i("MiyorareNavMatched", "$prefix animator=1 transition=1 window=1 favorites=$favoritesPoint target=$destinationPoint dwell_ms=750")
				fun inject(action: Int, down: Long, eventTime: Long, point: Offset) {
					val event = MotionEvent.obtain(down, eventTime, action, point.x, point.y, 0)
					event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
					try {
						assertTrue("Touch injection failed: action=$action route=$route",
							instrumentation.uiAutomation.injectInputEvent(event, true))
					} finally { event.recycle() }
				}
				fun tap(point: Offset): Triple<Long, Long, Long> {
					val down = SystemClock.uptimeMillis()
					var released = false
					try {
						inject(MotionEvent.ACTION_DOWN, down, down, point)
						SystemClock.sleep(32)
						val up = SystemClock.uptimeMillis()
						inject(MotionEvent.ACTION_UP, down, up, point)
						released = true
						return Triple(down, up, SystemClock.uptimeMillis())
					} finally {
						if (!released) runCatching { inject(MotionEvent.ACTION_CANCEL, down, SystemClock.uptimeMillis(), point) }
					}
				}
				fun observedSettled(item: NavItem): Long {
					var observed = 0L
					scenario.onActivity { activity ->
						val fm = activity.supportFragmentManager
						assertEquals(item.id, activity.bottomNav!!.selectedItemId)
						assertEquals(0, fm.backStackEntryCount)
						val active = fm.fragments.filter { it.id == R.id.container && it.isAdded && !it.isHidden }
						assertEquals(1, active.size)
						val selected = active.single()
						assertEquals(Lifecycle.State.RESUMED, selected.lifecycle.currentState)
						assertEquals(View.VISIBLE, selected.requireView().visibility)
						assertEquals(0f, selected.requireView().translationX, 0.1f)
						assertTrue(when (item) {
							NavItem.FAVORITES -> selected is FavouritesContainerFragment
							NavItem.LOCAL -> selected is LocalListFragment
							NavItem.DISCOVERY_HUB -> selected is DiscoveryHubFragment
							else -> false
						})
						observed = SystemClock.uptimeMillis()
					}
					return observed
				}
				fun warm(item: NavItem, point: Offset) {
					tap(point)
					SystemClock.sleep(750)
					compose.waitForIdle()
					observedSettled(item)
				}
				// Keep Hub and Local as separate test cases: baseline B01 must remain a real failure,
				// not prevent the independent Hub experiment from running.
				warm(NavItem.FAVORITES, favoritesPoint)
				warm(destination, destinationPoint)
				when (destination) {
					NavItem.LOCAL -> assertLocalContentReady(scenario)
					NavItem.DISCOVERY_HUB -> compose.waitUntil(10000) {
						compose.onAllNodesWithTag("catalog_spotlight").fetchSemanticsNodes().isNotEmpty()
					}
					else -> error("Unexpected route")
				}
				warm(NavItem.FAVORITES, favoritesPoint)
				val budgetNs = (1_000_000_000.0 / refreshHz).toLong()
				repeat(5) { repeatIndex ->
					val label = "$prefix repeat=${repeatIndex + 1}"
					val timestamps = mutableListOf<String>()
					fun sampleTap(item: NavItem, point: Offset, index: Int) {
						val (down, up, injectionDone) = tap(point)
						SystemClock.sleep(750)
						val settled = observedSettled(item)
						timestamps += "$label tap=$index destination=${item.name} down_ms=$down up_ms=$up injection_done_ms=$injectionDone observed_settled_ms=$settled"
					}
					try {
						measureNavigationFrames(scenario, window, budgetNs, label) {
							repeat(3) { roundtrip ->
								sampleTap(destination, destinationPoint, roundtrip * 2 + 1)
								sampleTap(NavItem.FAVORITES, favoritesPoint, roundtrip * 2 + 2)
							}
						}
					} finally {
						timestamps.forEach { Log.i("MiyorareNavMatched", it) }
					}
					compose.waitForIdle()
				}
			}
		} finally {
			try {
				instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(previousNight) }
			} finally {
				for ((key, value) in previousScales) {
					if (value == "null" || value.isBlank()) shell("settings delete global $key")
					else shell("settings put global $key $value")
				}
			}
		}
	}

	private fun LocalListFragment.localContentModel(): LocalListViewModel =
		ViewModelProvider(this)[LocalListViewModel::class.java]

	// A retained STARTED/RESUMED fragment alone does not prove its content collector survived.
	private fun assertLocalContentReady(scenario: ActivityScenario<MainActivity>, query: String = "") {
		compose.waitUntil(10000) {
			var ready = false
			scenario.onActivity { activity ->
				val local = activity.supportFragmentManager.fragments.filterIsInstance<LocalListFragment>().singleOrNull()
				val model = local?.localContentModel() ?: return@onActivity
				val content = model.content.value
				ready = model.library.state.value.initialized &&
					content.any { it is SmartLocalPanelModel && it.query == query } &&
					content.any { it is SmartLocalCollectionHeaderModel }
			}
			ready
		}
	}

	private data class NavigationFrameSample(val totalNs: Long, val deadlineNs: Long, val firstDraw: Boolean, val phases: List<Long>)

	private fun measureNavigationFrames(
		scenario: ActivityScenario<MainActivity>, window: Window, budgetNs: Long, label: String, block: () -> Unit,
	) {
		val worker = HandlerThread("NavigationFrameMetrics").apply { start() }
		val samples = java.util.Collections.synchronizedList(mutableListOf<NavigationFrameSample>())
		val dropped = AtomicInteger()
		val phaseIds = listOf(
			"unknown" to FrameMetrics.UNKNOWN_DELAY_DURATION,
			"input" to FrameMetrics.INPUT_HANDLING_DURATION,
			"animation" to FrameMetrics.ANIMATION_DURATION,
			"layout" to FrameMetrics.LAYOUT_MEASURE_DURATION,
			"draw" to FrameMetrics.DRAW_DURATION,
			"sync" to FrameMetrics.SYNC_DURATION,
			"command" to FrameMetrics.COMMAND_ISSUE_DURATION,
			"swap" to FrameMetrics.SWAP_BUFFERS_DURATION,
		) + if (Build.VERSION.SDK_INT >= 31) listOf("gpu" to FrameMetrics.GPU_DURATION) else emptyList()
		val listener = Window.OnFrameMetricsAvailableListener { _, metrics, count ->
			val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
			val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else budgetNs
			dropped.addAndGet(count.coerceAtLeast(0))
			if (total >= 0) samples.add(NavigationFrameSample(total, deadline.takeIf { it > 0 } ?: budgetNs,
				metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L, phaseIds.map { metrics.getMetric(it.second) }))
		}
		var registered = false
		try {
			scenario.onActivity {
				window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper))
				registered = true
			}
			block()
			// Allow queued reports to arrive; no screenshots or forced redraws.
			SystemClock.sleep(100)
		} finally {
			try {
				if (registered) scenario.onActivity { window.removeOnFrameMetricsAvailableListener(listener) }
			} finally {
				worker.quitSafely()
				worker.join(5000)
			}
		}
		assertFalse("FrameMetrics worker must terminate", worker.isAlive)
		val all = synchronized(samples) { samples.toList() }
		val measured = all.filterNot { it.firstDraw }
		assertTrue("No usable frames: $label", measured.isNotEmpty())
		val totals = measured.map { it.totalNs }.sorted()
		fun percentile(p: Double) = totals[((totals.size - 1) * p).toInt()]
		val missed = measured.count { it.totalNs > it.deadlineNs }
		Log.i("MiyorareNavPerf", "$label frames=${measured.size} first_draw=${all.size - measured.size} " +
			"dropped=${dropped.get()} missed=$missed p50_ns=${percentile(0.50)} p95_ns=${percentile(0.95)} " +
			"max_ns=${totals.last()} budget_ns=$budgetNs")
		// Phases can overlap; do not sum their percentiles or equate GPU time with total duration.
		Log.i("MiyorareNavPhase", "$label " + phaseIds.mapIndexed { i, phase ->
			val values = measured.map { it.phases[i] }.filter { it >= 0 }.sorted()
			val p95 = if (values.isEmpty()) -1L else values[((values.size - 1) * 0.95).toInt()]
			"${phase.first}_p95_ns=$p95 ${phase.first}_count=${values.size}"
		}.joinToString(" "))
		// Raw pairs support post-run reanalysis without retaining screenshot or user data.
		measured.chunked(60).forEachIndexed { index, chunk ->
			Log.i("MiyorareNavPerf", "$label chunk=$index total_deadline_ns=" +
				chunk.joinToString(",") { "${it.totalNs}:${it.deadlineNs}" })
		}
	}

	@Test fun modernRootKeepsHubViewAndScrollAcrossTabSwitchAndSettingsRoundTrip() {
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.KEY_NAV_LEGACY, false).commit()
		launch().use { scenario ->
			showHub(scenario)
			compose.onNodeWithTag("catalog_home_list").performScrollToNode(hasTestTag("catalog_section_NOVEL_TOP_RATED"))
			compose.onNodeWithTag("catalog_section_NOVEL_TOP_RATED").assertIsDisplayed()
			var fragment: DiscoveryHubFragment? = null
			scenario.onActivity { activity ->
				fragment = activity.supportFragmentManager.findFragmentByTag(DiscoveryHubFragment::class.java.name) as DiscoveryHubFragment
				assertEquals(5, activity.bottomNav!!.menu.size())
				assertEquals(R.id.nav_favorites, activity.bottomNav!!.menu.getItem(0).itemId)
				assertEquals(R.id.nav_discovery_hub, activity.bottomNav!!.menu.getItem(2).itemId)
				activity.bottomNav!!.selectedItemId = R.id.nav_history
			}
			compose.waitForIdle(); scenario.onActivity { it.bottomNav!!.selectedItemId = R.id.nav_discovery_hub }; compose.waitForIdle()
			val instrumentation = InstrumentationRegistry.getInstrumentation()
			val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
			try {
				scenario.onActivity { activity ->
					assertSame(fragment, activity.supportFragmentManager.findFragmentByTag(DiscoveryHubFragment::class.java.name))
					activity.viewBinding.buttonSettings.performClick()
				}
				val opened = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
				assertNotNull("Gear must open Settings directly", opened)
				instrumentation.runOnMainSync { opened.finish() }
				compose.waitForIdle()
				scenario.onActivity { activity ->
					assertNull(activity.supportFragmentManager.findFragmentByTag("main_utilities"))
					assertEquals(R.id.nav_discovery_hub, activity.bottomNav!!.selectedItemId)
				}
			} finally { instrumentation.removeMonitor(monitor) }
			compose.onNodeWithTag("catalog_section_NOVEL_TOP_RATED").assertIsDisplayed(); assertEquals(1, calls.get())
		}
	}
	@Test fun floatingDragPreviewsCustomTabAndHomeBeforeRelease() {
		assertTabDragPreviewsCustomTabAndHome(legacy = false)
	}

	@Test fun legacyDragPreviewsCustomTabAndHomeBeforeRelease() {
		assertTabDragPreviewsCustomTabAndHome(legacy = true)
	}

	@Test fun dragIndicatorMovesWithinOneSlotWithoutRepeatedPreview() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			val host = View(app)
			val slop = android.view.ViewConfiguration.get(app).scaledTouchSlop.toFloat().coerceAtLeast(1f)
			val slots = listOf(
				1 to SlotRect(0f, 0f, 10 * slop, 4 * slop),
				2 to SlotRect(10 * slop, 0f, 20 * slop, 4 * slop),
			)
			val previews = mutableListOf<Int?>()
			val drag = NavigationTabDrag(
				host, vertical = false, visibleSlots = { slots }, selectedId = { 1 },
				accent = { android.graphics.Color.RED }, onIndicatorChanged = {},
			)
			drag.preview = { previews.add(it); it != 2 }
			val down = SystemClock.uptimeMillis()
			fun send(action: Int, x: Float, elapsed: Long) {
				val event = MotionEvent.obtain(down, down + elapsed, action, x * slop, 2 * slop, 0)
				try { assertTrue(drag.dispatch(event) { true }) } finally { event.recycle() }
			}
			try {
				send(MotionEvent.ACTION_DOWN, 5f, 0)
				send(MotionEvent.ACTION_MOVE, 7f, 16)
				assertEquals(7 * slop, checkNotNull(drag.indicatorBounds).center.x, 0.01f)
				send(MotionEvent.ACTION_MOVE, 9f, 32)
				assertEquals(9 * slop, checkNotNull(drag.indicatorBounds).center.x, 0.01f)
				assertEquals(listOf(1), previews)
				send(MotionEvent.ACTION_MOVE, 12f, 48)
				assertNull("Rejected preview must not advertise a different tab", drag.indicatorBounds)
			} finally {
				drag.reset()
			}
		}
	}

	@Test fun landscapeRailPreviewsAndCommitsWithModernPreference() {
		assertLandscapeRailDrag(legacy = false)
	}

	@Test fun landscapeRailPreviewsAndCommitsWithLegacyPreference() {
		assertLandscapeRailDrag(legacy = true)
	}

	private fun assertLandscapeRailDrag(legacy: Boolean) {
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		settings.mainNavItems = listOf(NavItem.LOCAL, NavItem.HISTORY, NavItem.EXPLORE)
		PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.KEY_NAV_LEGACY, legacy).commit()
		launch().use { scenario ->
			scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
			compose.waitUntil(15000) {
				var ready = false
				scenario.onActivity { activity ->
					val rail = activity.findViewById<DragNavigationRailView>(R.id.navRail)
					ready = activity.resources.configuration.orientation == AndroidConfiguration.ORIENTATION_LANDSCAPE &&
						rail?.findViewById<View>(R.id.nav_discovery_hub)?.let { it.isShown && it.height > 0 } == true
				}
				ready
			}
			fun send(action: Int, id: Int, down: Long, elapsed: Long) = scenario.onActivity { activity ->
				val rail = checkNotNull(activity.findViewById<DragNavigationRailView>(R.id.navRail))
				val item = checkNotNull(rail.findViewById<View>(id))
				val visible = Rect()
				assertTrue(item.getLocalVisibleRect(visible))
				val railOrigin = IntArray(2).also { rail.getLocationInWindow(it) }
				val itemOrigin = IntArray(2).also { item.getLocationInWindow(it) }
				val event = MotionEvent.obtain(down, down + elapsed, action,
					itemOrigin[0] - railOrigin[0] + visible.exactCenterX(),
					itemOrigin[1] - railOrigin[1] + visible.exactCenterY(), 0)
				try {
					assertTrue(rail.dispatchTouchEvent(event))
				} finally {
					event.recycle()
				}
			}
			var down = SystemClock.uptimeMillis()
			send(MotionEvent.ACTION_DOWN, R.id.nav_favorites, down, 0)
			send(MotionEvent.ACTION_MOVE, R.id.nav_local, down, 32)
			scenario.onActivity { activity ->
				val local = activity.supportFragmentManager.fragments.filterIsInstance<LocalListFragment>().single()
				assertEquals(View.VISIBLE, local.requireView().visibility)
				assertTrue(local.isHidden)
			}
			send(MotionEvent.ACTION_MOVE, R.id.nav_discovery_hub, down, 64)
			compose.waitUntil(10000) {
				var ready = false
				scenario.onActivity { activity ->
					val rail = activity.findViewById<DragNavigationRailView>(R.id.navRail)
					assertEquals(R.id.nav_favorites, rail.selectedItemId)
					val home = activity.supportFragmentManager.fragments.filterIsInstance<DiscoveryHubFragment>().single()
					assertTrue(home.isHidden)
					assertEquals(Lifecycle.State.STARTED, home.lifecycle.currentState)
					val host = home.requireView() as ComposeView
					val root = host.getChildAt(0) as? ViewRootForTest ?: return@onActivity
					val provider = root.semanticsOwner.unmergedRootSemanticsNode.findPreviewTag("catalog_provider_selector")
					ready = host.isShown && host.alpha > 0f && host.getGlobalVisibleRect(Rect()) &&
						provider?.boundsInWindow?.isEmpty == false
				}
				ready
			}
			send(MotionEvent.ACTION_CANCEL, R.id.nav_discovery_hub, down, 96)
			scenario.onActivity { activity ->
				val home = activity.supportFragmentManager.fragments.filterIsInstance<DiscoveryHubFragment>().single()
				assertEquals(View.GONE, home.requireView().visibility)
				assertEquals(R.id.nav_favorites, activity.findViewById<DragNavigationRailView>(R.id.navRail).selectedItemId)
			}
			down = SystemClock.uptimeMillis()
			send(MotionEvent.ACTION_DOWN, R.id.nav_favorites, down, 0)
			send(MotionEvent.ACTION_MOVE, R.id.nav_discovery_hub, down, 32)
			send(MotionEvent.ACTION_UP, R.id.nav_discovery_hub, down, 64)
			compose.onNodeWithTag("catalog_home_list").assertIsDisplayed()
			scenario.onActivity { activity ->
				assertEquals(R.id.nav_discovery_hub, activity.findViewById<DragNavigationRailView>(R.id.navRail).selectedItemId)
				assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
			}
		}
	}

	private fun assertTabDragPreviewsCustomTabAndHome(legacy: Boolean) {
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		settings.mainNavItems = listOf(NavItem.LOCAL, NavItem.HISTORY, NavItem.EXPLORE)
		PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.KEY_NAV_LEGACY, legacy).commit()
		launch().use { scenario ->
			compose.waitForIdle()
			fun tabCenters() = listOf(NavItem.FAVORITES, NavItem.LOCAL, NavItem.DISCOVERY_HUB).map {
				compose.onNodeWithContentDescription(app.getString(it.navTitle)).fetchSemanticsNode().boundsInWindow.center
			}
			var downTime = SystemClock.uptimeMillis()
			fun send(action: Int, point: Offset, elapsed: Long) = scenario.onActivity { activity ->
				val bar = checkNotNull(activity.bottomNav)
				val origin = IntArray(2).also { bar.getLocationInWindow(it) }
				val event = MotionEvent.obtain(
					downTime, downTime + elapsed, action, point.x - origin[0], point.y - origin[1], 0,
				)
				try {
					bar.dispatchTouchEvent(event)
				} finally {
					event.recycle()
				}
			}
			val (start, custom, home) = tabCenters()
			send(MotionEvent.ACTION_DOWN, start, 0)
			send(MotionEvent.ACTION_MOVE, custom, 32)
			scenario.onActivity { activity ->
				val preview = activity.supportFragmentManager.fragments.filterIsInstance<LocalListFragment>().single()
				assertEquals(View.VISIBLE, preview.requireView().visibility)
				assertEquals(Lifecycle.State.STARTED, preview.lifecycle.currentState)
				assertTrue(preview.isHidden)
				assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
			}
			compose.waitForIdle()
			assertEquals(listOf(start, custom, home), tabCenters())
			send(MotionEvent.ACTION_MOVE, home, 64)
			scenario.onActivity { activity ->
				val preview = activity.supportFragmentManager.fragments.filterIsInstance<DiscoveryHubFragment>().single()
				assertEquals(View.VISIBLE, preview.requireView().visibility)
				assertEquals(Lifecycle.State.STARTED, preview.lifecycle.currentState)
				assertTrue(preview.isHidden)
				assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
			}
			// Compose's test registry only exposes RESUMED roots. Read the STARTED
			// preview's own semantics on main without promoting its lifecycle.
			compose.waitUntil(10000) {
				var ready = false
				scenario.onActivity { activity ->
					val preview = activity.supportFragmentManager.fragments.filterIsInstance<DiscoveryHubFragment>().single()
					val host = preview.requireView() as ComposeView
					val root = host.getChildAt(0) as? ViewRootForTest ?: return@onActivity
					val semantics = root.semanticsOwner.unmergedRootSemanticsNode
					val list = semantics.findPreviewTag("catalog_home_list")
					val provider = semantics.findPreviewTag("catalog_provider_selector")
					val spotlightIndex = list?.config?.getOrNull(SemanticsProperties.IndexForKey)?.invoke("spotlight")
					ready = host.isShown && host.alpha > 0f && host.getGlobalVisibleRect(Rect()) &&
						list?.boundsInWindow?.isEmpty == false && provider?.boundsInWindow?.isEmpty == false &&
						spotlightIndex != null && spotlightIndex >= 0
				}
				ready
			}
			send(MotionEvent.ACTION_CANCEL, home, 96)
			compose.waitForIdle()
			scenario.onActivity { activity ->
				val fragments = activity.supportFragmentManager.fragments
				assertEquals(View.VISIBLE, fragments.filterIsInstance<FavouritesContainerFragment>().single().requireView().visibility)
				assertEquals(View.GONE, fragments.filterIsInstance<DiscoveryHubFragment>().single().requireView().visibility)
				assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
			}
			val (startAgain, localAgain, homeAgain) = tabCenters()
			downTime = SystemClock.uptimeMillis()
			send(MotionEvent.ACTION_DOWN, startAgain, 0)
			send(MotionEvent.ACTION_MOVE, localAgain, 32)
			send(MotionEvent.ACTION_MOVE, homeAgain, 64)
			send(MotionEvent.ACTION_UP, homeAgain, 96)
			compose.waitForIdle()
			scenario.onActivity { activity ->
				val manager = activity.supportFragmentManager
				val selected = manager.fragments.filterIsInstance<DiscoveryHubFragment>().single()
				assertFalse(selected.isHidden)
				assertEquals(Lifecycle.State.RESUMED, selected.lifecycle.currentState)
				assertEquals(R.id.nav_discovery_hub, activity.bottomNav!!.selectedItemId)
				assertEquals(0, manager.backStackEntryCount)
			}
			compose.onNodeWithTag("catalog_home_list").performScrollToNode(hasTestTag("catalog_section_NOVEL_TOP_RATED"))
			compose.onNodeWithTag("catalog_section_NOVEL_TOP_RATED").assertIsDisplayed()
			val (_, away, committedHome) = tabCenters()
			downTime = SystemClock.uptimeMillis()
			send(MotionEvent.ACTION_DOWN, committedHome, 0)
			send(MotionEvent.ACTION_MOVE, away, 32)
			send(MotionEvent.ACTION_CANCEL, away, 64)
			compose.onNodeWithTag("catalog_section_NOVEL_TOP_RATED").assertIsDisplayed()
			assertEquals(1, calls.get())

			// Recreate on Home: Favorites has no retained list producer from this Activity.
			scenario.recreate()
			compose.waitForIdle()
			val (favouritesAfterRestore, _, homeAfterRestore) = tabCenters()
			downTime = SystemClock.uptimeMillis()
			send(MotionEvent.ACTION_DOWN, homeAfterRestore, 0)
			send(MotionEvent.ACTION_MOVE, favouritesAfterRestore, 32)
			scenario.onActivity { activity ->
				val preview = activity.supportFragmentManager.fragments.filterIsInstance<FavouritesContainerFragment>().single()
				assertTrue(preview.isHidden)
				assertEquals(Lifecycle.State.STARTED, preview.lifecycle.currentState)
				assertTrue(preview.isListWorkActive)
				assertEquals(R.id.nav_discovery_hub, activity.bottomNav!!.selectedItemId)
			}
			compose.waitUntil(10000) {
				var active = false
				scenario.onActivity { activity ->
					val preview = activity.supportFragmentManager.fragments.filterIsInstance<FavouritesContainerFragment>().single()
					active = preview.childFragmentManager.fragments.filterIsInstance<FavouritesListFragment>()
						.any { it.isHidden && it.isListWorkActive }
				}
				active
			}
			send(MotionEvent.ACTION_CANCEL, favouritesAfterRestore, 64)
			scenario.onActivity { activity ->
				val preview = activity.supportFragmentManager.fragments.filterIsInstance<FavouritesContainerFragment>().single()
				assertFalse(preview.isListWorkActive)
				assertTrue(preview.childFragmentManager.fragments.filterIsInstance<FavouritesListFragment>()
					.none { it.isListWorkActive })
				assertEquals(View.GONE, preview.requireView().visibility)
			}

			val (releaseFavorites, _, fromHome) = tabCenters()
			downTime = SystemClock.uptimeMillis()
			send(MotionEvent.ACTION_DOWN, fromHome, 0)
			send(MotionEvent.ACTION_MOVE, releaseFavorites, 32)
			send(MotionEvent.ACTION_UP, releaseFavorites, 64)
			scenario.onActivity { activity ->
				val manager = activity.supportFragmentManager
				val selected = manager.fragments.filterIsInstance<FavouritesContainerFragment>().single()
				assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
				assertEquals(View.VISIBLE, selected.requireView().visibility)
				assertEquals(Lifecycle.State.RESUMED, selected.lifecycle.currentState)
				assertTrue(selected.isListWorkActive)
				assertFalse(selected.isTabPreviewVisible)
				assertEquals(0, manager.backStackEntryCount)
			}
		}
	}

	private fun SemanticsNode.findPreviewTag(tag: String): SemanticsNode? {
		if (config.getOrNull(SemanticsProperties.TestTag) == tag) return this
		return children.firstNotNullOfOrNull { it.findPreviewTag(tag) }
	}

	@Test fun classicLegacyMigrationAndRecreationPreserveFixedRootSelectionWithoutRefetch() {
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.CLASSIC)
		PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.KEY_NAV_LEGACY, true)
			.putString(AppSettings.KEY_NAV_MAIN, "READER_JOURNEY,HISTORY,HISTORY,LOCAL,FAVORITES,UNKNOWN").commit()
		launch().use { scenario ->
			showHub(scenario); scenario.recreate()
			compose.waitUntil(10000) { compose.onAllNodesWithTag("catalog_spotlight").fetchSemanticsNodes().isNotEmpty() }
			scenario.onActivity { activity ->
				assertEquals(R.id.nav_discovery_hub, activity.bottomNav!!.selectedItemId)
				assertEquals(listOf(NavItem.FAVORITES, NavItem.HISTORY, NavItem.DISCOVERY_HUB, NavItem.LOCAL, NavItem.FEED).map { it.id }, (0 until activity.bottomNav!!.menu.size()).map { activity.bottomNav!!.menu.getItem(it).itemId })
			}
			assertEquals(1, calls.get())
		}
	}
	@Test fun hubReselectionScrollsToTopWithoutASecondProviderRequest() {
		launch().use { scenario ->
			showHub(scenario); compose.onNodeWithTag("catalog_home_list").performScrollToNode(hasTestTag("catalog_section_NOVEL_TOP_RATED"))
			scenario.onActivity { it.bottomNav!!.selectedItemId = R.id.nav_discovery_hub }
			compose.waitForIdle(); compose.onNodeWithTag("catalog_provider_selector").assertIsDisplayed(); assertEquals(1, calls.get())
		}
	}
	@Test fun gearOpensSettingsOnlyAndPreservesFavouritesSearchBack() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		launch().use { scenario ->
			scenario.onActivity { activity ->
				activity.bottomNav!!.selectedItemId = R.id.nav_history
				activity.supportFragmentManager.executePendingTransactions()
			}
			instrumentation.waitForIdleSync()
			scenario.onActivity { activity ->
				activity.bottomNav!!.selectedItemId = R.id.nav_favorites
				activity.supportFragmentManager.executePendingTransactions()
			}
			instrumentation.waitForIdleSync()
			scenario.onActivity { activity ->
				assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
				activity.viewBinding.searchBar.performClick()
				assertEquals(activity.getString(R.string.close), activity.viewBinding.buttonSettings.contentDescription)
				activity.viewBinding.buttonSettings.performClick()
				assertEquals(activity.getString(R.string.settings), activity.viewBinding.buttonSettings.contentDescription)
			}
			showHub(scenario)
			val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
			try {
				scenario.onActivity { activity ->
					assertEquals(activity.getString(R.string.settings), activity.viewBinding.buttonSettings.contentDescription)
					activity.viewBinding.buttonSettings.performClick()
				}
				val opened = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
				assertNotNull("Gear must launch Settings without auxiliary menu", opened)
				instrumentation.runOnMainSync { opened.finish() }
				compose.waitForIdle()
				scenario.onActivity { activity ->
					assertNull(activity.supportFragmentManager.findFragmentByTag("main_utilities"))
					assertEquals(R.id.nav_discovery_hub, activity.bottomNav!!.selectedItemId)
				}
			} finally { instrumentation.removeMonitor(monitor) }
			assertEquals(1, calls.get())
		}
	}
	@Test fun metadataReadNavigatesWithGenuineSourceMangaThroughExistingRouter() {
		val manga = org.koitharu.kotatsu.SampleData.manga
		knownForDetails = listOf(manga)
		val target = org.koitharu.kotatsu.details.ui.DetailsExpressiveActivity::class.java
		interceptNavigation(target) { captured ->
			val key = TrackerCatalogKey(Service.ANILIST, CatalogMediaKind.MANGA, "42")
			ActivityScenario.launch<org.koitharu.kotatsu.discovery.ui.TrackerCatalogActivity>(org.koitharu.kotatsu.discovery.ui.TrackerCatalogActivity.detailsIntent(app, key)).use {
				compose.waitUntil(10000) { compose.onAllNodesWithTag("catalog_read").fetchSemanticsNodes().isNotEmpty() }
				compose.onNodeWithTag("catalog_read").performClick()
				compose.waitUntil(5000) { captured.get() != null }
				val actual = androidx.core.content.IntentCompat.getParcelableExtra(captured.get()!!, org.koitharu.kotatsu.core.nav.AppRouter.KEY_MANGA, org.koitharu.kotatsu.core.model.parcelable.ParcelableManga::class.java)!!.manga
				assertEquals(manga.id, actual.id); assertEquals(manga.source, actual.source); assertEquals(manga.url, actual.url)
				assertNotEquals(key.remoteId.toLong(), actual.id)
			}
		}
	}
	@Test fun unresolvedNovelGlobalFallbackKeepsTypedScopeWithoutChangingGlobalPreference() {
		knownForDetails = emptyList()
		settings.isGlobalSearchNovelScope = false
		interceptNavigation(org.koitharu.kotatsu.search.ui.multi.SearchActivity::class.java) { captured ->
			val key = TrackerCatalogKey(Service.ANILIST, CatalogMediaKind.NOVEL, "42")
			ActivityScenario.launch<org.koitharu.kotatsu.discovery.ui.TrackerCatalogActivity>(org.koitharu.kotatsu.discovery.ui.TrackerCatalogActivity.detailsIntent(app, key)).use {
				compose.waitUntil(10000) { compose.onAllNodesWithTag("catalog_read").fetchSemanticsNodes().isNotEmpty() }
				compose.onNodeWithTag("catalog_read").performClick()
				compose.waitUntil(5000) { compose.onAllNodesWithTag("catalog_source_empty").fetchSemanticsNodes().isNotEmpty() }
				compose.onNodeWithTag("catalog_global_search").performClick(); compose.waitUntil(5000) { captured.get() != null }
				assertEquals("Metadata title", captured.get()!!.getStringExtra(org.koitharu.kotatsu.core.nav.AppRouter.KEY_QUERY))
				assertTrue(captured.get()!!.getBooleanExtra(org.koitharu.kotatsu.core.nav.AppRouter.KEY_CATALOG_NOVEL_SCOPE, false)); assertFalse(settings.isGlobalSearchNovelScope)
			}
		}
	}
	private fun interceptNavigation(target: Class<*>, check: (java.util.concurrent.atomic.AtomicReference<Intent?>) -> Unit) {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val captured = java.util.concurrent.atomic.AtomicReference<Intent?>(null)
		val monitor = object : android.app.Instrumentation.ActivityMonitor() {
			override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
				if (intent.component?.className != target.name) return null
				captured.set(Intent(intent))
				return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
			}
		}
		instrumentation.addMonitor(monitor)
		try { check(captured) } finally { instrumentation.removeMonitor(monitor) }
	}
	@Test fun exactMappingKeepsProviderSourceKindAndPrivateBoundariesWithoutWrites() = kotlinx.coroutines.runBlocking {
		val database = androidx.room.Room.inMemoryDatabaseBuilder(app, org.koitharu.kotatsu.core.db.MangaDatabase::class.java).build()
		try {
			val resolver = CatalogSourceResolver(alternatives, sources, database, settings, searchPreferences)
			val id = 456L
			val remoteId = Long.MAX_VALUE
			val entity = org.koitharu.kotatsu.core.db.entity.MangaEntity(id, "Actual saved source title", null, "file:///actual.cbz", "file:///actual.cbz", 0f, false, null, "", null, null, null, null, "LOCAL", null)
			database.getMangaDao().upsert(entity)
			database.getScrobblingDao().upsert(org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity(Service.ANILIST.id, 0, id, remoteId, "reading", 1, null, 0f))
			val key = TrackerCatalogKey(Service.ANILIST, CatalogMediaKind.MANGA, remoteId.toString())
			assertEquals(id, resolver.known(key).single().id)
			assertEquals("file:///actual.cbz", resolver.known(key).single().url)
			assertTrue(resolver.known(key.copy(service = Service.MAL)).isEmpty())
			assertTrue(resolver.known(key.copy(kind = CatalogMediaKind.NOVEL)).isEmpty())
			database.getMangaDao().upsert(entity.copy(url = "file:///00.Novel/actual.epub"))
			assertTrue(resolver.known(key).isEmpty()); assertEquals(id, resolver.known(key.copy(kind = CatalogMediaKind.NOVEL)).single().id)
			val category = database.getFavouriteCategoriesDao().insert(org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity(0, 0, 0, "Private fixture", "NEWEST", false, false, true, 0, 1))
			database.getPrivateFavouritesDao().insert(org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity(id, category, 0, false, 0, 0))
			assertTrue(resolver.known(key.copy(kind = CatalogMediaKind.NOVEL)).isEmpty())
			for ((table, expected) in listOf("favourites" to 0, "scrobblings" to 1, "manga" to 1)) database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor -> cursor.moveToFirst(); assertEquals(expected, cursor.getInt(0)) }
		} finally { database.close() }
	}
}
