package org.koitharu.kotatsu.settings

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalStorageManager
import java.io.File
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidTest
class DownloadDestinationSettingsTest {

	@get:Rule(order = 0) val hilt = HiltAndroidRule(this)
	@get:Rule(order = 1) val compose = createEmptyComposeRule()
	@Inject lateinit var destinations: DownloadDestinationStore
	@Inject lateinit var storage: LocalStorageManager
	@Inject @BaseHttpClient lateinit var baseHttpClient: Provider<OkHttpClient>

	@Before fun setUp() {
		// HiltTestApplication replaces the production Configuration.Provider.
		if (!WorkManager.isInitialized()) {
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			WorkManager.initialize(context, Configuration.Builder().build())
		}
		hilt.inject()
		// Production warms the network singleton off-main; HiltTestApplication does not.
		runBlocking { withContext(Dispatchers.IO) { baseHttpClient.get() } }
	}

	@Test fun configuredDestinationRefreshesAndRemainsVisibleAfterReopenWhenUnavailable() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val key = AppSettings.KEY_LOCAL_STORAGE
		val previous = prefs.getString(key, null)
		val fixture = File(context.cacheDir, "download-settings-${System.nanoTime()}")
		val available = File(fixture, "available")
		val changed = File(fixture, "changed")
		val intent = Intent(context, SettingsActivity::class.java).setAction(AppRouter.ACTION_MANAGE_DOWNLOADS)
		try {
			check(available.mkdirs())
			check(changed.mkdirs())
			prefs.edit(commit = true) { putString(key, available.path) }
			val availableSummary = runBlocking { storage.getDirectoryDisplayName(available, isFullPath = true) }
			val changedSummary = context.getString(R.string.download_destination_summary_unavailable, changed.path)
			ActivityScenario.launch<SettingsActivity>(intent).use {
				awaitSummary(availableSummary)
				assertEquals(available, destinations.effectiveRoot(FavouriteSpace.NORMAL))
				// Simulate a selected directory becoming unavailable, without a real SD card.
				check(changed.delete())
				// Write the same destination preference used by Settings; do not exercise the OS picker.
				prefs.edit(commit = true) { putString(key, changed.path) }
				awaitSummary(changedSummary)
				assertEquals(changed, destinations.effectiveRoot(FavouriteSpace.NORMAL))
			}
			ActivityScenario.launch<SettingsActivity>(intent).use {
				awaitSummary(changedSummary)
				assertEquals(changed, destinations.effectiveRoot(FavouriteSpace.NORMAL))
			}
		} finally {
			prefs.edit(commit = true) { if (previous == null) remove(key) else putString(key, previous) }
			fixture.deleteRecursively()
		}
	}

	private fun awaitSummary(expected: String) {
		val summary = compose.onAllNodes(hasText(expected), useUnmergedTree = true)
		compose.waitUntil(timeoutMillis = 10_000) { summary.fetchSemanticsNodes().isNotEmpty() }
	}
}
