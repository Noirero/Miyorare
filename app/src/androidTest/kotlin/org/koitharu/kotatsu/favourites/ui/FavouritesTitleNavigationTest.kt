package org.koitharu.kotatsu.favourites.ui

import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSession
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.main.ui.protect.ProtectActivity
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidTest
class FavouritesTitleNavigationTest {

	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var privateSessionProvider: Provider<PrivateFavouritesSession>

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Before fun setUp() {
		hilt.inject()
		settings.isOnboardingCompleted = true
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		// The Normal library is not the default tab: returning from Private must select it.
		settings.mainNavItems = listOf(NavItem.HISTORY, NavItem.FAVORITES, NavItem.EXPLORE, NavItem.UPDATED)
	}

	@Test fun normalTitleUsesExistingPrivateAuthentication() {
		val monitor = instrumentation.addMonitor(ProtectActivity::class.java.name, null, true)
		try {
			ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
				scenario.onActivity { activity ->
					activity.bottomNav?.selectedItemId = R.id.nav_favorites
					activity.supportFragmentManager.executePendingTransactions()
					val title = activity.findViewById<TextView>(R.id.text_favourites_title)
					assertTrue(title.isShown)
					title.performClick()
				}
				assertTrue(instrumentation.checkMonitorHit(monitor, 1))
			}
		} finally {
			instrumentation.removeMonitor(monitor)
		}
	}

	@Test fun privateTitleReturnsToNormalFavouritesNotPreviousMainTab() {
		instrumentation.runOnMainSync { privateSessionProvider.get().unlock() }
		ActivityScenario.launch<FavouritesActivity>(
			Intent(context, FavouritesActivity::class.java)
				.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue),
		).use { scenario ->
			scenario.onActivity { activity ->
				activity.supportFragmentManager.executePendingTransactions()
				val workspace = activity.supportFragmentManager.fragments
					.filterIsInstance<PrivateWorkspaceFragment>().first()
				workspace.childFragmentManager.executePendingTransactions()
				val title = activity.findViewById<TextView>(R.id.text_favourites_title)
				assertTrue(title.isShown)
				title.performClick()
			}
			var normalFavouritesVisible = false
			repeat(40) {
				instrumentation.waitForIdleSync()
				instrumentation.runOnMainSync {
					val main = ActivityLifecycleMonitorRegistry.getInstance()
						.getActivitiesInStage(Stage.RESUMED)
						.filterIsInstance<MainActivity>().firstOrNull()
					normalFavouritesVisible = main?.bottomNav?.selectedItemId == R.id.nav_favorites
				}
				if (normalFavouritesVisible) return@use
				Thread.sleep(100)
			}
			assertTrue("Private title should return to Normal Favourites", normalFavouritesVisible)
		}
	}
}
