package org.koitharu.kotatsu.settings

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceDiscovery
import org.koitharu.kotatsu.favourites.domain.NormalDownloadObservationStore
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScanCoordinator
import org.koitharu.kotatsu.favourites.domain.NormalScanOutcome
import java.io.File
import javax.inject.Inject
import javax.inject.Provider

/** Actual Downloads Settings host; HiltTestApplication does not register BaseApp observers. */
@HiltAndroidTest
class NormalDownloadScanSettingsTest {
	@get:Rule(order = 0) val hilt = HiltAndroidRule(this)
	@get:Rule(order = 1) val compose = createEmptyComposeRule()
	@Inject internal lateinit var coordinator: NormalDownloadScanCoordinator
	@Inject internal lateinit var observations: NormalDownloadObservationStore
	@Inject @BaseHttpClient lateinit var baseHttpClient: Provider<OkHttpClient>

	@Before fun setUp() {
		if (!WorkManager.isInitialized()) {
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			WorkManager.initialize(context, Configuration.Builder().build())
		}
		hilt.inject()
		runBlocking { withContext(Dispatchers.IO) { baseHttpClient.get() } }
	}

	@Test fun normalSettingsIndependentToggleAndManualSurvivesExit() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val key = NormalDownloadScanCoordinator.KEY_SCAN_ON_RESUME
		val exKey = ExhentaiPresenceDiscovery.KEY_SCAN_ON_RESUME
		val rootKey = AppSettings.KEY_LOCAL_STORAGE
		val oldNormal = prefs.all[key] as? Boolean
		val oldEx = prefs.all[exKey] as? Boolean
		val oldRoot = prefs.getString(rootKey, null)
		val fixture = File(context.cacheDir, "normal-settings-${System.nanoTime()}")
		val unavailable = File(fixture, "missing").canonicalFile
		val process = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val release = CompletableDeferred<Unit>()
		val reached = CompletableDeferred<Unit>()
		var blocker: Job? = null
		val intent = Intent(context, SettingsActivity::class.java).setAction(AppRouter.ACTION_MANAGE_DOWNLOADS)
		try {
			prefs.edit(commit = true) { remove(key); putBoolean(exKey, false); putString(rootKey, unavailable.path) }
			coordinator.start(process)
			val startup = runBlocking { withTimeout(20_000) { coordinator.state.first { !it.running && it.completedRuns > 0 } } }
			assertEquals(NormalScanOutcome.UNAVAILABLE, startup.outcome)
			ActivityScenario.launch<SettingsActivity>(intent).use {
				val toggle = compose.onNode(hasText(context.getString(R.string.normal_download_resume_scan)) and hasClickAction())
				toggle.performScrollTo()
				assertFalse("Opening Settings must not persist an unset default", prefs.contains(key))
				// The first tap must turn the default OFF into an explicit ON.
				toggle.performClick()
				compose.waitUntil(10_000) { prefs.contains(key) && prefs.getBoolean(key, false) }
				assertFalse(prefs.getBoolean(exKey, true))
			}
			ActivityScenario.launch<SettingsActivity>(intent).use {
				val toggle = compose.onNode(hasText(context.getString(R.string.normal_download_resume_scan)) and hasClickAction())
				toggle.performScrollTo().performClick()
				// Reopening Settings must honor the stored ON; the tap turns it OFF.
				compose.waitUntil(10_000) { prefs.contains(key) && !prefs.getBoolean(key, true) }
				assertFalse(prefs.getBoolean(exKey, true))
				val full = compose.onNode(hasText(context.getString(R.string.normal_download_full_scan)) and hasClickAction())
				full.performScrollTo()
				val summary = compose.onAllNodes(hasText(context.getString(R.string.normal_download_scan_unavailable)), useUnmergedTree = true)
				compose.waitUntil(10_000) { summary.fetchSemanticsNodes().isNotEmpty() }
				// Hold the store's real visibility fence before clicking; the queued manual
				// request cannot complete before Settings is destroyed.
				blocker = process.launch {
					observations.withLibraryVisibility(emptyList()) {
						reached.complete(Unit)
						release.await()
					}
				}
				runBlocking { withTimeout(20_000) { reached.await() } }
				full.performClick()
				assertEquals(startup.completedRuns, coordinator.state.value.completedRuns)
			}
			release.complete(Unit)
			val manual = runBlocking { withTimeout(20_000) {
				coordinator.state.first { !it.running && it.completedRuns > startup.completedRuns }
			} }
			assertEquals(NormalScanOutcome.UNAVAILABLE, manual.outcome)
			assertFalse(unavailable.exists())
			ActivityScenario.launch<SettingsActivity>(intent).use {
				assertTrue(prefs.contains(key))
				assertFalse(prefs.getBoolean(key, true))
				val full = compose.onNode(hasText(context.getString(R.string.normal_download_full_scan)) and hasClickAction())
				full.performScrollTo()
				compose.waitUntil(10_000) {
					compose.onAllNodes(hasText(context.getString(R.string.normal_download_scan_unavailable)), useUnmergedTree = true)
						.fetchSemanticsNodes().isNotEmpty()
				}
			}
		} finally {
			release.complete(Unit)
			runBlocking {
				blocker?.cancelAndJoin()
				process.coroutineContext[Job]?.cancelAndJoin()
			}
			prefs.edit(commit = true) {
				if (oldNormal == null) remove(key) else putBoolean(key, oldNormal)
				if (oldEx == null) remove(exKey) else putBoolean(exKey, oldEx)
				if (oldRoot == null) remove(rootKey) else putString(rootKey, oldRoot)
			}
			fixture.deleteRecursively()
		}
	}
}
