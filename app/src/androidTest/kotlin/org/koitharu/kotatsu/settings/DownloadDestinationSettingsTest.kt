package org.koitharu.kotatsu.settings

import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesProtection
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSession
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.settings.storage.MangaDirectorySelectDialog
import org.koitharu.kotatsu.settings.storage.MangaDirectorySelectViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidTest
class DownloadDestinationSettingsTest {

	@get:Rule(order = 0) val hilt = HiltAndroidRule(this)
	@get:Rule(order = 1) val compose = createEmptyComposeRule()
	@Inject lateinit var destinations: DownloadDestinationStore
	@Inject lateinit var storage: LocalStorageManager
	@Inject lateinit var security: PrivateFavouritesSecurityStore
	@Inject lateinit var session: PrivateFavouritesSession
	@Inject @BaseHttpClient lateinit var baseHttpClient: Provider<OkHttpClient>

	@Before fun setUp() {
		// HiltTestApplication replaces the production Configuration.Provider.
		if (!WorkManager.isInitialized()) {
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			WorkManager.initialize(context, Configuration.Builder().build())
		}
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			hilt.inject()
		}
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

	@Test fun privateSettingsOpensPrivateDestinationAndRefreshesWhenFollowingNormal() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val normalKey = AppSettings.KEY_LOCAL_STORAGE
		val privateKey = DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT
		val legacyKey = "private_download_legacy_roots"
		val previous = listOf(normalKey, privateKey).associateWith { prefs.getString(it, null) }
		val previousLegacy = prefs.getStringSet(legacyKey, null)?.toSet()
		val previousProtection = security.protection
		val previousScreenshots = security.allowPrivateScreenshots
		val wasUnlocked = session.isUnlocked.value
		val fixture = File(context.cacheDir, "private-download-settings-${System.nanoTime()}")
		val normal = File(fixture, "normal")
		val privateRoot = File(fixture, "unavailable-private")
		val intent = Intent(context, SettingsActivity::class.java).setAction(AppRouter.ACTION_PRIVATE_FAVOURITES_SETTINGS)
		try {
			check(normal.mkdirs())
			security.protection = PrivateFavouritesProtection.BIOMETRIC
			security.allowPrivateScreenshots = false
			session.unlock()
			prefs.edit(commit = true) {
				putString(normalKey, normal.path)
				putString(privateKey, privateRoot.path)
			}
			val privateSummary = context.getString(R.string.download_destination_summary_unavailable, privateRoot.path)
			val normalSummary = runBlocking { storage.getDirectoryDisplayName(normal, isFullPath = true) }
			ActivityScenario.launch<SettingsActivity>(intent).use { scenario ->
				awaitSummary(privateSummary)
				compose.onNodeWithText(context.getString(R.string.manga_save_location)).performScrollTo().performClick()
				awaitSummary(context.getString(R.string.download_destination_preview, privateRoot.path))
				scenario.onActivity {
					val settings = it.supportFragmentManager.findFragmentById(R.id.container) as PrivateFavouritesSettingsFragment
					val dialog = settings.childFragmentManager.fragments.filterIsInstance<MangaDirectorySelectDialog>().single()
					assertTrue(checkNotNull(dialog.dialog?.window).attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
				}
				compose.onNodeWithText(context.getString(R.string.download_destination_normal)).performClick()
				awaitSummary(context.getString(R.string.download_destination_preview, normal.path))
				assertEquals(privateRoot, destinations.effectiveRoot(FavouriteSpace.PRIVATE))
				compose.onNodeWithText(context.getString(R.string.download_destination_private)).performClick()
				awaitSummary(context.getString(R.string.download_destination_preview, privateRoot.path))
				// Inspect the actual dialog-owned ViewModel, not a second instance or a mock.
				lateinit var model: MangaDirectorySelectViewModel
				scenario.onActivity {
					val settings = it.supportFragmentManager.findFragmentById(R.id.container) as PrivateFavouritesSettingsFragment
					val dialog = settings.childFragmentManager.fragments.filterIsInstance<MangaDirectorySelectDialog>().single()
					model = ViewModelProvider(dialog)[MangaDirectorySelectViewModel::class.java]
				}
				val actionLabel = context.getString(R.string.download_destination_private_use_normal)
				val hint = context.getString(R.string.download_destination_private_follows_normal)
				val button = compose.onNodeWithText(actionLabel)
				val loadingSeen = AtomicBoolean(false)
				val mutationSeen = AtomicBoolean(false)
				val observerScope = CoroutineScope(Dispatchers.Main.immediate + Job())
				val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
					if (key == privateKey) mutationSeen.set(true)
				}
				fun snapshot(): String {
					// Compare only synthetic fixture roots; never dump arbitrary paths or Private UI text.
					val state = "space=${model.selectedSpace.value}, " +
						"configuredPrivatePresent=${destinations.configuredRoot(FavouriteSpace.PRIVATE) != null}, " +
						"configuredPrivateIsFixture=${destinations.configuredRoot(FavouriteSpace.PRIVATE) == privateRoot}, " +
						"normalUnchanged=${destinations.effectiveRoot(FavouriteSpace.NORMAL) == normal}, " +
						"effectivePrivateIsNormal=${destinations.effectiveRoot(FavouriteSpace.PRIVATE) == normal}, " +
						"storeOwnRoot=${destinations.privateUsesOwnRoot()}, modelOwnRoot=${model.privateUsesOwnRoot.value}, " +
						"checkedRootIsNormal=${model.items.value.firstOrNull { it.isChecked }?.file == normal}, " +
						"loading=${model.isLoading.value}, loadingSeen=${loadingSeen.get()}, mutationSeen=${mutationSeen.get()}"
					val semantics = runCatching {
						"buttonActions=${compose.onAllNodes(hasText(actionLabel) and hasClickAction()).fetchSemanticsNodes().size}, " +
							"buttonDisplayed=${runCatching { button.assertIsDisplayed() }.isSuccess}, " +
							"buttonBounds=${runCatching { button.fetchSemanticsNode().boundsInRoot }.getOrNull()}, " +
							"hintNodes=${compose.onAllNodes(hasText(hint), useUnmergedTree = true).fetchSemanticsNodes().size}"
					}.getOrElse { "semanticsError=${it.javaClass.simpleName}" }
					return "$state; $semantics"
				}
				prefs.registerOnSharedPreferenceChangeListener(listener)
				try {
					compose.runOnIdle {
						observerScope.launch { model.isLoading.collect { if (it) loadingSeen.set(true) } }
					}
					val before = snapshot()
					Log.i("FollowNormalDiagnostic", "before UI click: $before")
					try {
						button.performClick()
						awaitSummary(hint)
						Log.i("FollowNormalDiagnostic", "after UI click: ${snapshot()}")
					} catch (failure: Throwable) {
						val after = runCatching { snapshot() }.getOrElse { "snapshotError=${it.javaClass.simpleName}" }
						// A controlled downstream probe is allowed only when the original UI action left
						// the fixture untouched and no mutation is in flight. It can never rescue the test.
						val probe = if (destinations.configuredRoot(FavouriteSpace.PRIVATE) == privateRoot &&
							model.selectedSpace.value == FavouriteSpace.PRIVATE && !model.isLoading.value &&
							!loadingSeen.get() && !mutationSeen.get()
						) {
							val result = runCatching {
								compose.runOnIdle { model.useNormalDestinationForPrivate() }
								awaitSummary(hint)
							}
							"directModelProbe=${if (result.isSuccess) "hint reached" else result.exceptionOrNull()?.javaClass?.simpleName}; " +
								runCatching { snapshot() }.getOrElse { "snapshotError=${it.javaClass.simpleName}" }
						} else "directModelProbe=not run (mutation or loading observed)"
						val evidence = "Follow Normal UI action failed; before={$before}; after={$after}; $probe"
						Log.e("FollowNormalDiagnostic", evidence)
						throw AssertionError(evidence, failure)
					}
				} finally {
					observerScope.cancel()
					prefs.unregisterOnSharedPreferenceChangeListener(listener)
				}
				compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
				awaitSummary(normalSummary)
				compose.onNodeWithText(privateSummary).assertDoesNotExist()
				assertEquals(normal, destinations.effectiveRoot(FavouriteSpace.NORMAL))
				assertEquals(normal, destinations.effectiveRoot(FavouriteSpace.PRIVATE))
				assertEquals(null, destinations.configuredRoot(FavouriteSpace.PRIVATE))
			}
		} finally {
			prefs.edit(commit = true) {
				previous.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
				if (previousLegacy == null) remove(legacyKey) else putStringSet(legacyKey, previousLegacy)
			}
			security.protection = previousProtection
			security.allowPrivateScreenshots = previousScreenshots
			if (wasUnlocked) session.unlock() else session.lock()
			fixture.deleteRecursively()
		}
	}

	private fun awaitSummary(expected: String) {
		val summary = compose.onAllNodes(hasText(expected), useUnmergedTree = true)
		compose.waitUntil(timeoutMillis = 10_000) { summary.fetchSemanticsNodes().isNotEmpty() }
	}
}
