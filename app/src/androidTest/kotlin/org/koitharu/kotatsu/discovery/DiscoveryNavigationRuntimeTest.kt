package org.koitharu.kotatsu.discovery

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import org.koitharu.kotatsu.local.ui.LocalListFragment
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
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
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		settings.mainNavItems = listOf(NavItem.LOCAL, NavItem.HISTORY, NavItem.EXPLORE)
		PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.KEY_NAV_LEGACY, false).commit()
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
			compose.waitUntil(10000) { compose.onAllNodesWithTag("catalog_spotlight").fetchSemanticsNodes().isNotEmpty() }
			compose.onNodeWithTag("catalog_home_list").assertIsDisplayed()
			scenario.onActivity { activity ->
				val preview = activity.supportFragmentManager.fragments.filterIsInstance<DiscoveryHubFragment>().single()
				assertEquals(View.VISIBLE, preview.requireView().visibility)
				assertTrue(preview.isHidden)
				assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
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
		}
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
