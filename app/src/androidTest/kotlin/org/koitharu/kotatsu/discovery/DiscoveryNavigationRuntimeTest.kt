package org.koitharu.kotatsu.discovery

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
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
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.main.ui.nav.MainUtilitiesSheet
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
		override suspend fun search(details: TrackerCatalogDetails, allSources: Boolean): kotlinx.coroutines.flow.Flow<org.koitharu.kotatsu.alternatives.domain.AlternativeSearchEvent> {
			checkNotNull(knownForDetails) { "Home must not search sources" }
			return kotlinx.coroutines.flow.emptyFlow()
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
	private fun assertFavoritesRoot(activity: MainActivity) {
		activity.supportFragmentManager.executePendingTransactions()
		assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
		val favourites = activity.supportFragmentManager.findFragmentByTag(FavouritesContainerFragment::class.java.name)
		assertNotNull(favourites)
		assertFalse(favourites!!.isHidden)
		assertEquals(Lifecycle.State.RESUMED, favourites.lifecycle.currentState)
	}
	@Test fun modernRootKeepsHubViewAndScrollAcrossTabSwitchAndGearDismissal() {
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
			scenario.onActivity { assertSame(fragment, it.supportFragmentManager.findFragmentByTag(DiscoveryHubFragment::class.java.name)); it.viewBinding.buttonSettings.performClick() }
			compose.waitUntil(5000) { compose.onAllNodesWithTag("main_utilities_sheet").fetchSemanticsNodes().isNotEmpty() }
			compose.onNodeWithTag("main_utilities_sheet").assertExists()
			scenario.onActivity { activity ->
				(activity.supportFragmentManager.findFragmentByTag("main_utilities") as MainUtilitiesSheet).dismiss()
				assertEquals(R.id.nav_discovery_hub, activity.bottomNav!!.selectedItemId)
			}
			compose.onNodeWithTag("catalog_section_NOVEL_TOP_RATED").assertIsDisplayed(); assertEquals(1, calls.get())
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
	@Test fun removingActiveConfigurableSlotWhileResumedMovesFragmentAndSelectionTogether() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		launch().use { scenario ->
			scenario.onActivity { activity ->
				activity.bottomNav!!.selectedItemId = R.id.nav_history
				activity.supportFragmentManager.executePendingTransactions()
			}
			settings.mainNavItems = listOf(NavItem.FEED, NavItem.EXPLORE, NavItem.LOCAL)
			instrumentation.waitForIdleSync()
			scenario.onActivity(::assertFavoritesRoot)
		}
	}
	@Test fun removingActiveConfigurableSlotWhileStateSavedReconcilesOnResume() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		launch().use { scenario ->
			scenario.onActivity { activity ->
				activity.bottomNav!!.selectedItemId = R.id.nav_history
				activity.supportFragmentManager.executePendingTransactions()
			}
			scenario.moveToState(Lifecycle.State.CREATED)
			settings.mainNavItems = listOf(NavItem.FEED, NavItem.EXPLORE, NavItem.LOCAL)
			instrumentation.waitForIdleSync()
			scenario.moveToState(Lifecycle.State.RESUMED)
			instrumentation.waitForIdleSync()
			scenario.onActivity(::assertFavoritesRoot)
		}
	}
	@Test fun utilityActionsOpenExistingJourneyAndSettingsWithoutChangingHubRoot() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		launch().use { scenario ->
			instrumentation.waitForIdleSync()
			scenario.onActivity { it.supportFragmentManager.executePendingTransactions() }
			instrumentation.waitForIdleSync()
			scenario.onActivity { activity ->
				assertEquals(R.id.nav_favorites, activity.bottomNav!!.selectedItemId)
				activity.viewBinding.searchBar.performClick()
				assertEquals(activity.getString(R.string.close), activity.viewBinding.buttonSettings.contentDescription)
				activity.viewBinding.buttonSettings.performClick()
				assertEquals(activity.getString(R.string.main_utilities), activity.viewBinding.buttonSettings.contentDescription)
			}
			showHub(scenario)
			for ((tag, target) in listOf("utility_journey" to org.koitharu.kotatsu.stats.ui.StatsActivity::class.java, "utility_settings" to org.koitharu.kotatsu.settings.SettingsActivity::class.java)) {
				val monitor = instrumentation.addMonitor(target.name, null, false)
				try {
					scenario.onActivity { it.viewBinding.buttonSettings.performClick() }
					compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
					compose.onNodeWithTag(tag).assertIsDisplayed()
					compose.onNodeWithTag(tag).performClick()
					val opened = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
					assertNotNull("Utility must open existing ${target.simpleName}", opened)
					instrumentation.runOnMainSync { opened.finish() }
					compose.waitForIdle()
					scenario.onActivity { assertEquals(R.id.nav_discovery_hub, it.bottomNav!!.selectedItemId) }
				} finally { instrumentation.removeMonitor(monitor) }
			}
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
