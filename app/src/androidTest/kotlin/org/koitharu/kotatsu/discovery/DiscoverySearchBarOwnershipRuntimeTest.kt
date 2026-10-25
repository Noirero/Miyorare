package org.koitharu.kotatsu.discovery

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.AlternativeSearchEvent
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.discovery.data.CatalogSnapshotStore
import org.koitharu.kotatsu.discovery.data.TrackerCatalogRepository
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.discovery.ui.CatalogArguments
import org.koitharu.kotatsu.discovery.ui.CatalogScreenMode
import org.koitharu.kotatsu.discovery.ui.TrackerCatalogActivity
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@HiltAndroidTest
@UninstallModules(DiscoveryCatalogModule::class)
class DiscoverySearchBarOwnershipRuntimeTest {
	@get:Rule(order = 0) val hilt = HiltAndroidRule(this)
	@get:Rule(order = 1) val compose = createEmptyComposeRule()

	@Inject lateinit var settings: AppSettings

	private val providers = listOf(ScrobblerService.ANILIST, ScrobblerService.MAL, ScrobblerService.KITSU).map { service ->
		object : TrackerCatalogProvider {
			override val catalogService = service
			override val catalogCapabilities = CatalogCapability.entries.toSet()
			override suspend fun home(filter: CatalogFilter, allowAdult: Boolean): TrackerCatalogHome = TrackerCatalogHome(
				filter.kinds.map { kind ->
					TrackerCatalogSection(
						CatalogSectionKey(service, kind, CatalogSectionType.TRENDING),
						(1..4).map { id -> TrackerCatalogItem(TrackerCatalogKey(service, kind, id.toString()), "$service $kind $id", adult = false) },
					)
				},
			)
			override suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int) = TrackerCatalogPage(emptyList())
			override suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean) = TrackerCatalogDetails(TrackerCatalogItem(key, "Details", adult = false))
			override suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int) = TrackerCatalogPage(emptyList())
		}
	}

	@BindValue @JvmField val repository = TrackerCatalogRepository(providers.toSet(), object : CatalogSnapshotStore {
		override fun read(key: CatalogHomeRequest, now: Long): TrackerCatalogHome? = null
		override fun write(key: CatalogHomeRequest, home: TrackerCatalogHome) = true
	})

	@BindValue @JvmField val sourceResolution: CatalogSourceResolution = object : CatalogSourceResolution {
		override suspend fun known(key: TrackerCatalogKey): List<Manga> = emptyList()
		override suspend fun search(details: TrackerCatalogDetails, allSources: Boolean): Flow<AlternativeSearchEvent> = emptyFlow()
		override suspend fun canOpen(manga: Manga, kind: CatalogMediaKind): Boolean = false
	}

	private val app = InstrumentationRegistry.getInstrumentation().targetContext
	private var previous: Map<String, *> = emptyMap<String, Any>()

	@Before fun setup() {
		hilt.inject()
		previous = PreferenceManager.getDefaultSharedPreferences(app).all
		runCatching { WorkManager.getInstance(app) }.getOrElse { WorkManager.initialize(app, Configuration.Builder().build()) }
		settings.isOnboardingCompleted = true
		settings.mainNavItems = listOf(NavItem.HISTORY, NavItem.EXPLORE, NavItem.LOCAL)
		settings.discoveryProvider = ScrobblerService.ANILIST.name
		settings.discoveryFilter = CatalogFilter.ALL.name
		PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.KEY_TRACKER_ENABLED, true).commit()
	}

	@After fun cleanup() {
		val edit = PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
		for ((key, value) in previous) when (value) {
			is String -> edit.putString(key, value)
			is Boolean -> edit.putBoolean(key, value)
			is Int -> edit.putInt(key, value)
			is Long -> edit.putLong(key, value)
			is Float -> edit.putFloat(key, value)
			is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
		}
		edit.commit()
	}

	@Test fun homeOwnsSharedSearchOnlyWhileActiveAndScopeTracksProvider() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
			scenario.onActivity { it.bottomNav!!.selectedItemId = R.id.nav_discovery_hub }
			compose.waitUntil(10000) { compose.onAllNodesWithTag("catalog_spotlight").fetchSemanticsNodes().isNotEmpty() }
			compose.onNodeWithTag("catalog_search_action").assertDoesNotExist()
			scenario.onActivity { activity ->
				assertEquals(activity.getString(R.string.hub_search_hint_provider, "AniList"), activity.viewBinding.searchBar.hint.toString())
			}

			assertTrackerSearchIntent(instrumentation, scenario, ScrobblerService.ANILIST, CatalogFilter.ALL)

			compose.onNodeWithTag("catalog_provider_selector").performClick()
			compose.onNodeWithTag("catalog_provider_KITSU").performClick()
			compose.waitUntil(10000) {
				var updated = false
				scenario.onActivity { updated = it.viewBinding.searchBar.hint.toString().contains("Kitsu") }
				updated
			}
			assertTrackerSearchIntent(instrumentation, scenario, ScrobblerService.KITSU, CatalogFilter.ALL)

			scenario.onActivity { it.bottomNav!!.selectedItemId = R.id.nav_history }
			instrumentation.waitForIdleSync()
			scenario.onActivity { activity ->
				assertEquals(activity.getString(R.string.search_manga), activity.viewBinding.searchBar.hint.toString())
				activity.viewBinding.searchBar.performClick()
				assertTrue(activity.viewBinding.searchView.isShowing)
				activity.viewBinding.searchView.hide()
			}

			scenario.onActivity { it.bottomNav!!.selectedItemId = R.id.nav_favorites }
			instrumentation.waitForIdleSync()
			scenario.onActivity { activity ->
				activity.viewBinding.searchBar.performClick()
				assertEquals(activity.getString(R.string.close), activity.viewBinding.buttonSettings.contentDescription)
			}

			scenario.onActivity { it.bottomNav!!.selectedItemId = R.id.nav_discovery_hub }
			compose.waitUntil(10000) { compose.onAllNodesWithTag("catalog_spotlight").fetchSemanticsNodes().isNotEmpty() }
			scenario.onActivity { activity ->
				assertTrue(activity.viewBinding.searchBar.hint.toString().contains("Kitsu"))
				assertFalse(activity.viewBinding.searchView.isShowing)
			}
		}
	}

	private fun assertTrackerSearchIntent(
		instrumentation: Instrumentation,
		scenario: ActivityScenario<MainActivity>,
		service: ScrobblerService,
		filter: CatalogFilter,
	) {
		val captured = AtomicReference<Intent?>(null)
		val monitor = object : Instrumentation.ActivityMonitor() {
			override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
				if (intent.component?.className != TrackerCatalogActivity::class.java.name) return null
				captured.set(Intent(intent))
				return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
			}
		}
		instrumentation.addMonitor(monitor)
		try {
			scenario.onActivity { it.viewBinding.searchBar.performClick() }
			compose.waitUntil(5000) { captured.get() != null }
			val intent = checkNotNull(captured.get())
			assertEquals(CatalogScreenMode.SEARCH.name, intent.getStringExtra(CatalogArguments.MODE))
			assertEquals(service.name, intent.getStringExtra(CatalogArguments.SERVICE))
			assertEquals(filter.name, intent.getStringExtra(CatalogArguments.FILTER))
		} finally {
			instrumentation.removeMonitor(monitor)
		}
	}
}
