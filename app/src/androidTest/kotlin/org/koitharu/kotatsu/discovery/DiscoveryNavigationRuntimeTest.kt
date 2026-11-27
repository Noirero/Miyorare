package org.koitharu.kotatsu.discovery

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration as AndroidConfiguration
import org.koitharu.kotatsu.core.ui.widgets.DragNavigationRailView
import org.koitharu.kotatsu.core.ui.widgets.NavigationTabDrag
import org.koitharu.kotatsu.core.ui.widgets.FloatingBottomNavigationView
import org.koitharu.kotatsu.core.ui.widgets.DirectionalSlidePresentation
import org.koitharu.kotatsu.core.ui.widgets.InteractiveTabSlidePresentation
import org.koitharu.kotatsu.core.ui.widgets.TabSlideFrame
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Rect as SlotRect
import android.graphics.Rect
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import org.koitharu.kotatsu.local.ui.LocalListFragment
import org.koitharu.kotatsu.local.ui.LocalListViewModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalPanelModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalCollectionHeaderModel
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
import org.koitharu.kotatsu.settings.nav.NavigationTransitionPreview
import org.koitharu.kotatsu.settings.nav.NavigationTransitionSettingsFragment
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

	@Test fun elasticFloatingDragPreservesPreviewAndCommitLifecycle() {
		NavigationTransitionPreferences(app).setMotion(NavigationMotion.ELASTIC_GLIDE)
		assertTabDragPreviewsCustomTabAndHome(legacy = false)
	}

	@Test fun fluidLegacyDragPreservesPreviewAndCommitLifecycle() {
		NavigationTransitionPreferences(app).setMotion(NavigationMotion.FLUID_LENS)
		assertTabDragPreviewsCustomTabAndHome(legacy = true)
	}

	@Test fun rapidUserTabSelectionsKeepLastDestinationWithoutExtraBackStack() {
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		settings.mainNavItems = listOf(NavItem.LOCAL, NavItem.HISTORY, NavItem.EXPLORE)
		launch().use { scenario ->
			compose.waitForIdle()
			scenario.onActivity { activity ->
				val bar = checkNotNull(activity.bottomNav as? FloatingBottomNavigationView)
				bar.selectFromUser(R.id.nav_discovery_hub)
				bar.selectFromUser(R.id.nav_local)
				bar.selectFromUser(R.id.nav_favorites)
				bar.selectFromUser(R.id.nav_discovery_hub)
			}
			compose.waitForIdle()
			scenario.onActivity { activity ->
				val fm = activity.supportFragmentManager
				val active = fm.fragments.filter { it.id == R.id.container && it.isAdded && !it.isHidden }
				assertEquals(1, active.size)
				assertTrue(active.single() is DiscoveryHubFragment)
				assertEquals(Lifecycle.State.RESUMED, active.single().lifecycle.currentState)
				assertEquals(R.id.nav_discovery_hub, activity.bottomNav!!.selectedItemId)
				assertEquals(0, fm.backStackEntryCount)
			}
		}
	}

	@Test fun navigationPreferenceFallbackAndResetAreIsolated() {
		val raw = PreferenceManager.getDefaultSharedPreferences(app)
		val prefs = NavigationTransitionPreferences(app)
		val originalLabels = raw.getBoolean(AppSettings.KEY_NAV_LABELS, true)
		raw.edit().putInt(NavigationTransitionPreferences.KEY_MOTION, 42)
			.putString(NavigationTransitionPreferences.KEY_TRANSITION, "unsupported").commit()
		assertEquals(NavigationMotion.CLASSIC_GLIDE, prefs.motion)
		assertEquals(ScreenTransition.DIRECTIONAL_SLIDE, prefs.transition)
		prefs.setMotion(NavigationMotion.FLUID_LENS)
		assertEquals(NavigationMotion.FLUID_LENS, NavigationTransitionPreferences(app).motion)
		prefs.reset()
		assertEquals(NavigationMotion.CLASSIC_GLIDE, prefs.motion)
		assertEquals(ScreenTransition.DIRECTIONAL_SLIDE, prefs.transition)
		assertEquals(originalLabels, raw.getBoolean(AppSettings.KEY_NAV_LABELS, true))
	}

	@Test fun directionalPresentationCleanupRestoresOriginalTranslationsAndVisibility() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			val parent = FrameLayout(app)
			val first = View(app).apply { translationX = 3f }
			val second = View(app).apply { translationX = 5f; visibility = View.INVISIBLE }
			val third = View(app).apply { translationX = 7f; visibility = View.INVISIBLE }
			for (child in listOf(first, second, third)) parent.addView(child, FrameLayout.LayoutParams(300, 100))
			parent.layout(0, 0, 300, 100)
			val presentation = DirectionalSlidePresentation()
			presentation.start(first, second, 1, View.INVISIBLE)
			presentation.start(second, third, 1, View.INVISIBLE)
			presentation.finish()
			assertEquals(View.INVISIBLE, first.visibility)
			assertEquals(View.INVISIBLE, second.visibility)
			assertEquals(View.VISIBLE, third.visibility)
			assertEquals(3f, first.translationX, 0.01f)
			assertEquals(5f, second.translationX, 0.01f)
			assertEquals(7f, third.translationX, 0.01f)
		}
	}

	@Test fun interactiveSlideFollowsProgressAndReversalWithoutTimeDrivenAnimation() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			val parent = FrameLayout(app)
			val first = View(app).apply { translationX = 3f }
			val second = View(app).apply { translationX = 5f; visibility = View.GONE }
			val third = View(app).apply { translationX = 7f; visibility = View.GONE }
			val pages = mapOf(1 to first, 2 to second, 3 to third)
			for (child in pages.values) parent.addView(child, FrameLayout.LayoutParams(300, 100))
			parent.layout(0, 0, 300, 100)
			val slide = InteractiveTabSlidePresentation()
			fun seek(frame: TabSlideFrame) = assertTrue(slide.seek(frame, 1, first) { pages[it] })
			seek(TabSlideFrame(1, 2, 0.25f))
			assertEquals(-72f, first.translationX, 0.01f)
			assertEquals(230f, second.translationX, 0.01f)
			seek(TabSlideFrame(1, 2, 0.75f))
			assertEquals(-222f, first.translationX, 0.01f)
			seek(TabSlideFrame(1, 2, 0.25f))
			assertEquals(-72f, first.translationX, 0.01f)
			val back = checkNotNull(slide.captureHandoff(1))
			assertEquals(-75f, back.incomingOffset, 0.01f)
			assertEquals(-300f, back.distance, 0.01f)
			seek(TabSlideFrame.single(2))
			assertEquals(5f, second.translationX, 0.01f)
			seek(TabSlideFrame(2, 3, 0.4f))
			assertEquals(View.INVISIBLE, first.visibility)
			assertEquals(-115f, second.translationX, 0.01f)
			assertEquals(187f, third.translationX, 0.01f)
			slide.finish()
			assertEquals(View.VISIBLE, first.visibility)
			assertEquals(View.GONE, second.visibility)
			assertEquals(View.GONE, third.visibility)
			assertEquals(3f, first.translationX, 0.01f)
			assertEquals(5f, second.translationX, 0.01f)
			assertEquals(7f, third.translationX, 0.01f)
		}
	}

	/**
	 * The general emulator workflow disables animations. Exercise the real enabled path
	 * explicitly and restore the setting before the next test. Screenshots contain only
	 * CI fixture data and are collected by the existing runtime evidence artifact.
	 */
	@Test fun animationEnabledGestureFramesAndOwnerVisualEvidence() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
			instrumentation.uiAutomation.executeShellCommand(command),
		).bufferedReader().use { it.readText().trim() }
		val previousScale = shell("settings get global animator_duration_scale")
		val previousNight = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
		try {
			shell("settings put global animator_duration_scale 1")
			assertEquals(1f, android.provider.Settings.Global.getFloat(app.contentResolver,
				android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f), 0.01f)
			settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
			settings.mainNavItems = listOf(NavItem.LOCAL, NavItem.HISTORY, NavItem.EXPLORE)
			PreferenceManager.getDefaultSharedPreferences(app).edit()
				.putBoolean(AppSettings.KEY_NAV_LEGACY, false)
				.putBoolean(AppSettings.KEY_NAV_LABELS, true).commit()
			for (night in listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
				androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)) {
				instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(night) }
				for (motion in NavigationMotion.entries) {
					NavigationTransitionPreferences(app).setMotion(motion)
					captureNavigationGesture(motion, night)
				}
			}
		} finally {
			instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(previousNight) }
			if (previousScale == "null" || previousScale.isBlank()) shell("settings delete global animator_duration_scale")
			else shell("settings put global animator_duration_scale $previousScale")
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

	@Test fun coldLocalTabTapBuildsContentAndRespondsToQuery() {
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.KEY_NAV_LEGACY, false).commit()
		launch().use { scenario ->
			compose.onNodeWithContentDescription(app.getString(NavItem.LOCAL.navTitle)).performClick()
			assertLocalContentReady(scenario)
			scenario.onActivity { activity ->
				val local = activity.supportFragmentManager.fragments.filterIsInstance<LocalListFragment>().single()
				assertFalse(local.isHidden)
				assertEquals(Lifecycle.State.RESUMED, local.lifecycle.currentState)
				local.localContentModel().setLocalQuery("B01 fixture query")
			}
			assertLocalContentReady(scenario, "B01 fixture query")
			scenario.onActivity { activity ->
				activity.supportFragmentManager.fragments.filterIsInstance<LocalListFragment>().single().localContentModel().setLocalQuery("")
			}
			assertLocalContentReady(scenario)
		}
	}

	/** Diagnostic samples, not a device-independent performance gate. No screenshot capture in measured windows. */
	@Test fun navigationMotionFrameTimingAndRapidSelectionEvidence() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
			instrumentation.uiAutomation.executeShellCommand(command),
		).bufferedReader().use { it.readText().trim() }
		val previousScale = shell("settings get global animator_duration_scale")
		val previousNight = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
		try {
			shell("settings put global animator_duration_scale 1")
			assertEquals(1f, android.provider.Settings.Global.getFloat(app.contentResolver,
				android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f), 0.01f)
			settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
			settings.mainNavItems = listOf(NavItem.LOCAL, NavItem.HISTORY, NavItem.EXPLORE)
			PreferenceManager.getDefaultSharedPreferences(app).edit()
				.putBoolean(AppSettings.KEY_NAV_LEGACY, false)
				.putBoolean(AppSettings.KEY_NAV_LABELS, true).commit()
			for (night in listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
				androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)) {
				instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(night) }
				for (motion in NavigationMotion.entries) {
					NavigationTransitionPreferences(app).setMotion(motion)
					launch().use { scenario ->
						fun select(id: Int) = scenario.onActivity { activity ->
							checkNotNull(activity.bottomNav as? FloatingBottomNavigationView).selectFromUser(id)
						}
						fun settled(id: Int) {
							compose.waitForIdle()
							scenario.onActivity { activity ->
								assertEquals(id, activity.bottomNav!!.selectedItemId)
								val fm = activity.supportFragmentManager
								assertEquals(0, fm.backStackEntryCount)
								val active = fm.fragments.filter { it.id == R.id.container && it.isAdded && !it.isHidden }
								assertEquals(1, active.size)
								assertEquals(Lifecycle.State.RESUMED, active.single().lifecycle.currentState)
								assertEquals(0f, active.single().requireView().translationX, 0.1f)
								assertTrue(active.single().requireView().visibility == View.VISIBLE)
								assertTrue(when (id) {
									R.id.nav_local -> active.single() is LocalListFragment
									R.id.nav_favorites -> active.single() is FavouritesContainerFragment
									R.id.nav_discovery_hub -> active.single() is DiscoveryHubFragment
									else -> false
								})
							}
						}
						// Warm retained tabs and their data once, before recording.
						select(R.id.nav_local)
						assertLocalContentReady(scenario)
						select(R.id.nav_discovery_hub)
						compose.waitUntil(10000) { compose.onAllNodesWithTag("catalog_spotlight").fetchSemanticsNodes().isNotEmpty() }
						select(R.id.nav_favorites)
						SystemClock.sleep(600)
						settled(R.id.nav_favorites)
						lateinit var window: Window
						var refreshHz = 0f
						var actualNight = 0
						scenario.onActivity {
							window = it.window
							refreshHz = it.window.decorView.display?.refreshRate ?: 0f
							actualNight = it.resources.configuration.uiMode and AndroidConfiguration.UI_MODE_NIGHT_MASK
						}
						assertTrue("A measured display refresh rate is required", refreshHz > 0f)
						val prefix = "motion=${motion.storedValue} requested_night=$night actual_night=$actualNight refresh_hz=$refreshHz"
						val budgetNs = (1_000_000_000.0 / refreshHz).toLong()
						repeat(3) { repeatIndex ->
							fun sample(name: String, lastId: Int, block: () -> Unit) {
								measureNavigationFrames(scenario, window, budgetNs,
									"$prefix scenario=$name repeat=${repeatIndex + 1}", block)
								settled(lastId)
							}
							sample("adjacent_roundtrip", R.id.nav_favorites) {
								select(R.id.nav_local); SystemClock.sleep(500)
								select(R.id.nav_favorites); SystemClock.sleep(500)
							}
							sample("nonadjacent_roundtrip", R.id.nav_favorites) {
								select(R.id.nav_discovery_hub); SystemClock.sleep(500)
								select(R.id.nav_favorites); SystemClock.sleep(500)
							}
							sample("rapid_redirect", R.id.nav_favorites) {
								select(R.id.nav_local); SystemClock.sleep(48)
								select(R.id.nav_discovery_hub); SystemClock.sleep(48)
								select(R.id.nav_favorites); SystemClock.sleep(500)
							}
							val from = compose.onNodeWithContentDescription(app.getString(NavItem.FAVORITES.navTitle))
								.fetchSemanticsNode().boundsInWindow.center
							val to = compose.onNodeWithContentDescription(app.getString(NavItem.LOCAL.navTitle))
								.fetchSemanticsNode().boundsInWindow.center
							sample("drag_reverse_release", R.id.nav_local) {
								val down = SystemClock.uptimeMillis()
								fun send(action: Int, fraction: Float) = scenario.onActivity { activity ->
									val bar = checkNotNull(activity.bottomNav)
									val origin = IntArray(2).also { bar.getLocationInWindow(it) }
									val point = from + (to - from) * fraction
									val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
										point.x - origin[0], point.y - origin[1], 0)
									try { assertTrue(bar.dispatchTouchEvent(event)) } finally { event.recycle() }
								}
								var released = false
								try {
									send(MotionEvent.ACTION_DOWN, 0f)
									for (fraction in listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f, 0.65f, 0.5f, 0.35f, 0.5f, 0.65f, 0.8f, 1f)) {
										SystemClock.sleep(16)
										send(MotionEvent.ACTION_MOVE, fraction)
									}
									send(MotionEvent.ACTION_UP, 1f)
									released = true
									SystemClock.sleep(500)
								} finally {
									if (!released) send(MotionEvent.ACTION_CANCEL, 0f)
								}
							}
							// Return outside the measured drag window.
							select(R.id.nav_favorites)
							SystemClock.sleep(500)
							settled(R.id.nav_favorites)
						}
					}
				}
			}
		} finally {
			instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(previousNight) }
			if (previousScale == "null" || previousScale.isBlank()) shell("settings delete global animator_duration_scale")
			else shell("settings put global animator_duration_scale $previousScale")
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

	@Test fun reducedMotionDragCancelAndPreferencePersistence() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
			instrumentation.uiAutomation.executeShellCommand(command),
		).bufferedReader().use { it.readText().trim() }
		val previousScale = shell("settings get global animator_duration_scale")
		try {
			shell("settings put global animator_duration_scale 0")
			assertEquals(0f, android.provider.Settings.Global.getFloat(app.contentResolver,
				android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f), 0.01f)
			settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
			settings.mainNavItems = listOf(NavItem.LOCAL, NavItem.HISTORY, NavItem.EXPLORE)
			PreferenceManager.getDefaultSharedPreferences(app).edit()
				.putBoolean(AppSettings.KEY_NAV_LEGACY, false).commit()
			for (motion in NavigationMotion.entries) {
				val prefs = NavigationTransitionPreferences(app)
				prefs.setMotion(motion)
				launch().use { scenario ->
					compose.waitForIdle()
					scenario.onActivity { activity ->
						checkNotNull(activity.bottomNav as? FloatingBottomNavigationView).selectFromUser(R.id.nav_favorites)
					}
					compose.waitForIdle()
					val from = compose.onNodeWithContentDescription(app.getString(NavItem.FAVORITES.navTitle))
						.fetchSemanticsNode().boundsInWindow.center
					val to = compose.onNodeWithContentDescription(app.getString(NavItem.LOCAL.navTitle))
						.fetchSemanticsNode().boundsInWindow.center
					val down = SystemClock.uptimeMillis()
					fun send(action: Int, point: Offset, elapsed: Long) = scenario.onActivity { activity ->
						val bar = checkNotNull(activity.bottomNav)
						val origin = IntArray(2).also { bar.getLocationInWindow(it) }
						val event = MotionEvent.obtain(down, down + elapsed, action, point.x - origin[0], point.y - origin[1], 0)
						try { bar.dispatchTouchEvent(event) } finally { event.recycle() }
					}
					send(MotionEvent.ACTION_DOWN, from, 0)
					try {
						send(MotionEvent.ACTION_MOVE, to, 32)
						scenario.onActivity { activity ->
							val target = activity.supportFragmentManager.fragments.filterIsInstance<LocalListFragment>().single()
							assertTrue(target.isHidden)
							assertEquals(Lifecycle.State.STARTED, target.lifecycle.currentState)
							assertEquals(View.VISIBLE, target.requireView().visibility)
							assertEquals(0f, target.requireView().translationX, 0.01f)
							assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
						}
					} finally { send(MotionEvent.ACTION_CANCEL, to, 64) }
					scenario.onActivity { activity ->
						val fm = activity.supportFragmentManager
						assertEquals(View.GONE, fm.fragments.filterIsInstance<LocalListFragment>().single().requireView().visibility)
						assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
						checkNotNull(activity.bottomNav as? FloatingBottomNavigationView).selectFromUser(R.id.nav_local)
					}
					assertLocalContentReady(scenario)
					scenario.recreate()
					compose.waitForIdle()
					scenario.onActivity { activity ->
						assertEquals(R.id.nav_local, activity.bottomNav!!.selectedItemId)
						val local = activity.supportFragmentManager.fragments.filterIsInstance<LocalListFragment>().single()
						assertFalse(local.isHidden)
						assertEquals(Lifecycle.State.RESUMED, local.lifecycle.currentState)
						assertEquals(0f, local.requireView().translationX, 0.01f)
					}
					assertEquals(motion, NavigationTransitionPreferences(app).motion)
					assertEquals(ScreenTransition.DIRECTIONAL_SLIDE, prefs.transition)
				}
			}
		} finally {
			if (previousScale == "null" || previousScale.isBlank()) shell("settings delete global animator_duration_scale")
			else shell("settings put global animator_duration_scale $previousScale")
		}
	}

	/** Counterbalanced same-window control for the saturated emulator measurements; not an Amain2 baseline. */
	@Test fun adjacentFrameTimingAnimationScaleControl() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
			instrumentation.uiAutomation.executeShellCommand(command),
		).bufferedReader().use { it.readText().trim() }
		val previousScale = shell("settings get global animator_duration_scale")
		val previousNight = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
		try {
			instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) }
			settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
			settings.mainNavItems = listOf(NavItem.LOCAL, NavItem.HISTORY, NavItem.EXPLORE)
			PreferenceManager.getDefaultSharedPreferences(app).edit()
				.putBoolean(AppSettings.KEY_NAV_LEGACY, false).putBoolean(AppSettings.KEY_NAV_LABELS, true).commit()
			for (motion in NavigationMotion.entries) {
				NavigationTransitionPreferences(app).setMotion(motion)
				shell("settings put global animator_duration_scale 1")
				launch().use { scenario ->
					fun select(id: Int) = scenario.onActivity {
						checkNotNull(it.bottomNav as? FloatingBottomNavigationView).selectFromUser(id)
					}
					select(R.id.nav_local)
					assertLocalContentReady(scenario)
					select(R.id.nav_favorites)
					SystemClock.sleep(600)
					compose.waitForIdle()
					lateinit var window: Window
					var hz = 0f
					var night = 0
					scenario.onActivity {
						window = it.window
						hz = window.decorView.display?.refreshRate ?: 0f
						night = it.resources.configuration.uiMode and AndroidConfiguration.UI_MODE_NIGHT_MASK
					}
					assertTrue(hz > 0f)
					val budget = (1_000_000_000.0 / hz).toLong()
					repeat(3) { index ->
						for (scale in if (index % 2 == 0) listOf(1, 0) else listOf(0, 1)) {
							shell("settings put global animator_duration_scale $scale")
							assertEquals(scale.toFloat(), android.provider.Settings.Global.getFloat(app.contentResolver,
								android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, -1f), 0.01f)
							SystemClock.sleep(300)
							compose.waitForIdle()
							measureNavigationFrames(scenario, window, budget,
								"motion=${motion.storedValue} actual_night=$night refresh_hz=$hz scenario=adjacent_scale_control scale=$scale repeat=${index + 1}") {
								// Three identical roundtrips per window provide more frames at scale zero.
								repeat(3) {
									select(R.id.nav_local); SystemClock.sleep(500)
									select(R.id.nav_favorites); SystemClock.sleep(500)
								}
							}
							compose.waitForIdle()
							scenario.onActivity {
								assertEquals(R.id.nav_favorites, it.bottomNav!!.selectedItemId)
								val active = it.supportFragmentManager.fragments.filter { f -> f.id == R.id.container && f.isAdded && !f.isHidden }
								assertTrue(active.single() is FavouritesContainerFragment)
								assertEquals(Lifecycle.State.RESUMED, active.single().lifecycle.currentState)
							}
						}
					}
				}
			}
		} finally {
			instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(previousNight) }
			if (previousScale == "null" || previousScale.isBlank()) shell("settings delete global animator_duration_scale")
			else shell("settings put global animator_duration_scale $previousScale")
		}
	}

	private fun findNavigationPreview(view: View): NavigationTransitionPreview? {
		if (view is NavigationTransitionPreview) return view
		if (view is ViewGroup) for (index in 0 until view.childCount) {
			findNavigationPreview(view.getChildAt(index))?.let { return it }
		}
		return null
	}

	@Test fun navigationSettingsPreviewLocalGestureAndReset() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
			instrumentation.uiAutomation.executeShellCommand(command),
		).bufferedReader().use { it.readText().trim() }
		val previousScale = shell("settings get global animator_duration_scale")
		val prefs = NavigationTransitionPreferences(app)
		try {
			shell("settings put global animator_duration_scale 1")
			settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
			val raw = PreferenceManager.getDefaultSharedPreferences(app)
			raw.edit().putBoolean(AppSettings.KEY_NAV_LEGACY, false).putBoolean(AppSettings.KEY_NAV_LABELS, true).commit()
			prefs.reset()
			val originalCalls = calls.get()
			ActivityScenario.launch<SettingsActivity>(Intent(app, SettingsActivity::class.java)).use { scenario ->
				scenario.onActivity { it.openFragment(NavigationTransitionSettingsFragment::class.java, null, false) }
				compose.waitUntil(10000) { compose.onAllNodesWithText(app.getString(R.string.navigation_motion_title)).fetchSemanticsNodes().isNotEmpty() }
				compose.waitForIdle()
				lateinit var preview: NavigationTransitionPreview
				lateinit var pages: FrameLayout
				lateinit var bar: FloatingBottomNavigationView
				var stack = 0
				scenario.onActivity {
					preview = checkNotNull(findNavigationPreview(it.window.decorView))
					pages = preview.getChildAt(0) as FrameLayout
					bar = preview.getChildAt(1) as FloatingBottomNavigationView
					stack = it.supportFragmentManager.backStackEntryCount
					assertEquals(3, pages.childCount)
				}
				fun settled(id: Int, page: Int) {
					compose.waitUntil(10000) {
						var ready = false
						scenario.onActivity {
							ready = bar.selectedItemId == id && (0 until pages.childCount).all { index ->
								val v = pages.getChildAt(index)
								kotlin.math.abs(v.translationX) < 0.1f &&
									(v.visibility == View.VISIBLE) == (index == page)
							}
						}
						ready
					}
					scenario.onActivity {
						assertTrue(it.supportFragmentManager.findFragmentById(R.id.container) is NavigationTransitionSettingsFragment)
						assertEquals(stack, it.supportFragmentManager.backStackEntryCount)
					}
				}
				for ((motion, option) in listOf(NavigationMotion.ELASTIC_GLIDE to R.string.navigation_motion_elastic,
					NavigationMotion.FLUID_LENS to R.string.navigation_motion_fluid,
					NavigationMotion.CLASSIC_GLIDE to R.string.navigation_motion_classic)) {
					compose.onNodeWithText(app.getString(R.string.navigation_motion_title)).performScrollTo().performClick()
					compose.onNodeWithText(app.getString(option)).performClick()
					compose.waitUntil(5000) { prefs.motion == motion }
					assertEquals(ScreenTransition.DIRECTIONAL_SLIDE, prefs.transition)
					// Use the actual preview bar's selection callback; no app route.
					scenario.onActivity { bar.selectFromUser(R.id.nav_discovery_hub) }
					settled(R.id.nav_discovery_hub, 1)
					scenario.onActivity { bar.selectFromUser(R.id.nav_favorites) }
					settled(R.id.nav_favorites, 0)
				}
				// Direct gesture dispatch to the real nested preview bar, not the main runtime bar.
				compose.onNodeWithText(app.getString(R.string.navigation_transition_preview)).performScrollTo()
				compose.waitForIdle()
				val from = compose.onNodeWithContentDescription(app.getString(NavItem.FAVORITES.navTitle)).fetchSemanticsNode().boundsInWindow.center
				val to = compose.onNodeWithContentDescription(app.getString(NavItem.DISCOVERY_HUB.navTitle)).fetchSemanticsNode().boundsInWindow.center
				for (scale in listOf(1, 0)) {
					shell("settings put global animator_duration_scale $scale")
					assertEquals(scale.toFloat(), android.provider.Settings.Global.getFloat(app.contentResolver,
						android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, -1f), 0.01f)
					val down = SystemClock.uptimeMillis()
					fun send(action: Int, fraction: Float, elapsed: Long) = scenario.onActivity {
						val origin = IntArray(2).also { bar.getLocationInWindow(it) }
						val pt = from + (to - from) * fraction
						val ev = MotionEvent.obtain(down, down + elapsed, action, pt.x - origin[0], pt.y - origin[1], 0)
						try { bar.dispatchTouchEvent(ev) } finally { ev.recycle() }
					}
					send(MotionEvent.ACTION_DOWN, 0f, 0)
					try {
						send(MotionEvent.ACTION_MOVE, if (scale == 1) 0.5f else 1f, 32)
						scenario.onActivity {
							assertEquals(R.id.nav_favorites, bar.selectedItemId)
							val incoming = pages.getChildAt(1)
							assertEquals(View.VISIBLE, incoming.visibility)
							val sign = if (to.x > from.x) 1f else -1f
							assertEquals(if (scale == 1) sign * pages.width * 0.5f else 0f, incoming.translationX, 2f)
						}
					} finally { send(MotionEvent.ACTION_CANCEL, 1f, 64) }
					settled(R.id.nav_favorites, 0)
				}
				prefs.setMotion(NavigationMotion.FLUID_LENS)
				compose.waitForIdle()
				compose.onNodeWithText(app.getString(R.string.navigation_transition_reset)).performScrollTo().performClick()
				compose.waitUntil(5000) { prefs.motion == NavigationMotion.CLASSIC_GLIDE }
				assertEquals(ScreenTransition.DIRECTIONAL_SLIDE, prefs.transition)
				assertFalse(raw.getBoolean(AppSettings.KEY_NAV_LEGACY, true))
				assertTrue(raw.getBoolean(AppSettings.KEY_NAV_LABELS, false))
				assertEquals(originalCalls, calls.get())
			}
		} finally {
			if (previousScale == "null" || previousScale.isBlank()) shell("settings delete global animator_duration_scale")
			else shell("settings put global animator_duration_scale $previousScale")
		}
	}

	private fun captureNavigationGesture(motion: NavigationMotion, night: Int) {
		launch().use { scenario ->
			compose.waitForIdle()
			var actualNight = 0
			scenario.onActivity { actualNight = it.resources.configuration.uiMode and AndroidConfiguration.UI_MODE_NIGHT_MASK }
			val prefix = "${motion.name.lowercase()}-requested-$night-actual-$actualNight"
			fun capture(stage: String) {
				compose.waitForIdle()
				writeNavigationCapture("$prefix-$stage")
			}
			val from = compose.onNodeWithContentDescription(app.getString(NavItem.FAVORITES.navTitle))
				.fetchSemanticsNode().boundsInWindow.center
			val to = compose.onNodeWithContentDescription(app.getString(NavItem.LOCAL.navTitle))
				.fetchSemanticsNode().boundsInWindow.center
			val direction = if (to.x > from.x) 1f else -1f
			var down = 0L
			fun point(fraction: Float) = Offset(from.x + (to.x - from.x) * fraction, from.y)
			fun send(action: Int, fraction: Float, elapsed: Long) = scenario.onActivity { activity ->
				val bar = checkNotNull(activity.bottomNav)
				val origin = IntArray(2).also { bar.getLocationInWindow(it) }
				val position = point(fraction)
				val event = MotionEvent.obtain(down, down + elapsed, action,
					position.x - origin[0], position.y - origin[1], 0)
				try {
					val handled = bar.dispatchTouchEvent(event)
					if (action != MotionEvent.ACTION_CANCEL) assertTrue(handled)
				} finally { event.recycle() }
			}
			fun assertProgress(fraction: Float) = scenario.onActivity { activity ->
				val fragments = activity.supportFragmentManager.fragments
				val current = fragments.filterIsInstance<FavouritesContainerFragment>().single()
				val target = fragments.filterIsInstance<LocalListFragment>().single()
				val source = current.requireView()
				val incoming = target.requireView()
				val width = (source.parent as View).width.toFloat()
				assertTrue(width > 0f)
				assertEquals(-direction * width * fraction, source.translationX, 2f)
				assertEquals(direction * width * (1f - fraction), incoming.translationX, 2f)
				assertEquals(View.VISIBLE, source.visibility)
				assertEquals(View.VISIBLE, incoming.visibility)
				assertEquals(Lifecycle.State.RESUMED, current.lifecycle.currentState)
				assertEquals(Lifecycle.State.STARTED, target.lifecycle.currentState)
				assertTrue(target.isHidden)
				assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
			}
			capture("idle")
			down = SystemClock.uptimeMillis()
			var released = false
			try {
				// Capture the drag before waiting for screenshots. Holding DOWN on Favorites
				// until a screenshot finishes could invoke its existing Private long-click.
				send(MotionEvent.ACTION_DOWN, 0f, 0)
				send(MotionEvent.ACTION_MOVE, 0.35f, 32)
				capture("drag-35")
				assertProgress(0.35f)
				assertLocalContentReady(scenario)
				capture("paused-35")
				assertProgress(0.35f)
				send(MotionEvent.ACTION_MOVE, 0.65f, 64)
				capture("drag-65")
				assertProgress(0.65f)
				send(MotionEvent.ACTION_MOVE, 0.35f, 96)
				capture("reverse-35")
				assertProgress(0.35f)
				send(MotionEvent.ACTION_MOVE, 0.75f, 128)
				send(MotionEvent.ACTION_UP, 0.75f, 160)
				released = true
				compose.waitUntil(5000) {
					var settled = false
					scenario.onActivity { activity ->
						val fragments = activity.supportFragmentManager.fragments
						val current = fragments.filterIsInstance<FavouritesContainerFragment>().single()
						val target = fragments.filterIsInstance<LocalListFragment>().single()
						settled = activity.bottomNav!!.selectedItemId == R.id.nav_local &&
							current.isHidden && current.requireView().visibility == View.GONE &&
							target.lifecycle.currentState == Lifecycle.State.RESUMED &&
							target.requireView().visibility == View.VISIBLE &&
							kotlin.math.abs(current.requireView().translationX) < 0.01f &&
							kotlin.math.abs(target.requireView().translationX) < 0.01f
					}
					settled
				}
				assertLocalContentReady(scenario)
				capture("settled")
			} finally {
				if (!released) send(MotionEvent.ACTION_CANCEL, 0f, 192)
			}
		}
	}

	private fun writeNavigationCapture(name: String) {
		if (android.os.Build.VERSION.SDK_INT < 29) return
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		instrumentation.waitForIdleSync()
		val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
		try {
			val resolver = app.contentResolver
			val path = "Download/miyorare-navigation-motion/"
			val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
			resolver.delete(collection, "relative_path=? AND _display_name=?", arrayOf(path, "$name.png"))
			val values = android.content.ContentValues().apply {
				put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
				put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
				put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, path)
			}
			val uri = checkNotNull(resolver.insert(collection, values))
			resolver.openOutputStream(uri, "w").use {
				assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, checkNotNull(it)))
			}
		} finally {
			bitmap.recycle()
		}
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
			assertLocalContentReady(scenario)
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
