package org.koitharu.kotatsu.settings.about.whatsnew

import android.content.Intent
import android.util.AtomicFile
import androidx.lifecycle.Lifecycle
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.main.ui.MainActivity
import java.io.File
import javax.inject.Inject
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.about.WhatsNewFragment
import org.koitharu.kotatsu.settings.nav.NavigationTransitionSettingsFragment

@HiltAndroidTest
class WhatsNewRuntimeTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Inject lateinit var settings: AppSettings

    @Before fun setup() { hilt.inject() }

    @Test fun earlierInstallationShowsOnePopupAndAcknowledgementSurvivesRecreation() {
        val stateFile = AtomicFile(File(context.noBackupFilesDir, "whats-new-state.json"))
        val original = runCatching { stateFile.readFully() }.getOrNull()
        val store = WhatsNewStateStore(context)
        val onboarding = settings.isOnboardingCompleted
        val protection = settings.isAppProtectionEnabled
        val current = ReleaseIdentity(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        val previous = ReleaseIdentity("0.0.1", current.code - 1)
        try {
            stateFile.delete()
            assertTrue(store.write(previous))
            settings.isOnboardingCompleted = true
            settings.isAppProtectionEnabled = false
            runCatching { WorkManager.getInstance(context) }.getOrElse {
                WorkManager.initialize(context, Configuration.Builder().build())
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                fun awaitPopup() {
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithTag("whats_new_close").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onAllNodesWithTag("whats_new_close").assertCountEquals(1)
                }
                awaitPopup()
                assertEquals(previous, store.read())
                scenario.recreate()
                awaitPopup()
                assertEquals(previous, store.read())
                compose.onNodeWithTag("whats_new_close").performScrollTo().performClick()
                compose.waitUntil(10_000) { store.read() == current }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                compose.waitForIdle()
                compose.onAllNodesWithTag("whats_new_close").assertCountEquals(0)
                scenario.onActivity {
                    assertNull(it.supportFragmentManager.findFragmentByTag(WhatsNewDialogFragment.TAG))
                }
                assertEquals(current, store.read())
            }
        } finally {
            settings.isOnboardingCompleted = onboarding
            settings.isAppProtectionEnabled = protection
            stateFile.delete()
            if (original != null) {
                val stream = stateFile.startWrite()
                stream.write(original)
                stateFile.finishWrite(stream)
            }
        }
    }

    @Test fun debugCatalogAndManualFeatureLinkNavigateToRealSettingsAndBack() {
        val raw = context.assets.open(ReleaseCatalogParser.ASSET).bufferedReader().use { it.readText() }
        val release = requireNotNull(ReleaseCatalogParser.parse(raw, BuildConfig.VERSION_NAME, true))
        assertTrue(release.fixture)
        assertNull(ReleaseCatalogParser.parse(raw, BuildConfig.VERSION_NAME, false))
        val intent = Intent(context, SettingsActivity::class.java).apply {
            action = WhatsNewDestinationRouter.ACTION_OPEN
            putExtra(WhatsNewDestinationRouter.EXTRA_DESTINATION, FeatureDestination.WHATS_NEW.name)
        }
        ActivityScenario.launch<SettingsActivity>(intent).use { scenario ->
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("whats_new_entry_transitions").fetchSemanticsNodes().isNotEmpty()
            }
            scenario.onActivity {
                assertTrue(it.supportFragmentManager.findFragmentById(R.id.container) is WhatsNewFragment)
            }
            compose.onNodeWithTag("whats_new_entry_fixture-fix").assertHasNoClickAction()
            compose.onNodeWithTag("whats_new_entry_transitions").performScrollTo().assertHasClickAction().performClick()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(it.supportFragmentManager.findFragmentById(R.id.container) is NavigationTransitionSettingsFragment)
                it.onBackPressedDispatcher.onBackPressed()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("whats_new_entry_transitions").fetchSemanticsNodes().isNotEmpty()
            }
            scenario.onActivity {
                assertTrue(it.supportFragmentManager.findFragmentById(R.id.container) is WhatsNewFragment)
            }
        }
    }
}
