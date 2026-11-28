package org.koitharu.kotatsu.favourites.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteHeadingPreferences
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

	private var originalHeadingCollapsed: Boolean? = null
	private val headingPrefs get() = PreferenceManager.getDefaultSharedPreferences(context)

	@Before fun setUp() {
		hilt.inject()
		originalHeadingCollapsed = if (headingPrefs.contains(FavouriteHeadingPreferences.KEY_COLLAPSED)) {
			headingPrefs.getBoolean(FavouriteHeadingPreferences.KEY_COLLAPSED, false)
		} else null
		check(headingPrefs.edit().putBoolean(FavouriteHeadingPreferences.KEY_COLLAPSED, false).commit())
		settings.isOnboardingCompleted = true
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		// The Normal library is not the default tab: returning from Private must select it.
		settings.mainNavItems = listOf(NavItem.HISTORY, NavItem.FAVORITES, NavItem.EXPLORE, NavItem.UPDATED)
	}

	@After fun restoreHeadingPreference() {
		val edit = headingPrefs.edit()
		val previous = originalHeadingCollapsed
		if (previous == null) edit.remove(FavouriteHeadingPreferences.KEY_COLLAPSED)
		else edit.putBoolean(FavouriteHeadingPreferences.KEY_COLLAPSED, previous)
		check(edit.commit())
	}

	@Test fun headingCollapsePersistsAndChevronDoesNotSelectContent() {
		ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
			scenario.onActivity { activity ->
				activity.bottomNav?.selectedItemId = R.id.nav_favorites
				activity.supportFragmentManager.executePendingTransactions()
			}
			fun await(message: String, condition: (MainActivity) -> Boolean) {
				val deadline = SystemClock.uptimeMillis() + 8_000
				do {
					var ready = false
					scenario.onActivity { ready = condition(it) }
					if (ready) return
					SystemClock.sleep(40)
				} while (SystemClock.uptimeMillis() < deadline)
				error(message)
			}
			await("Expanded control must be visible") {
				it.findViewById<View>(R.id.button_favourites_heading_hide)?.let { v -> v.isShown && v.alpha > .99f } == true
			}
			scenario.onActivity { activity ->
				activity.findViewById<View>(R.id.button_favourites_heading_hide).performClick()
			}
			await("Collapsed heading and visible reveal control") {
				!it.findViewById<View>(R.id.favourites_heading_copy).isShown &&
					it.findViewById<View>(R.id.button_favourites_heading_show).alpha > .99f
			}
			scenario.onActivity { activity ->
				assertTrue(FavouriteHeadingPreferences.isCollapsed(activity))
				val group = activity.findViewById<MaterialButtonToggleGroup>(R.id.toggle_content_type)
				val show = activity.findViewById<View>(R.id.button_favourites_heading_show)
				val novel = activity.findViewById<View>(R.id.button_content_novel)
				val manga = activity.findViewById<View>(R.id.button_content_manga)
				val showRect = Rect()
				val novelRect = Rect()
				val mangaRect = Rect()
				assertTrue(show.getGlobalVisibleRect(showRect))
				assertTrue(novel.getGlobalVisibleRect(novelRect))
				assertTrue(manga.getGlobalVisibleRect(mangaRect))
				assertFalse(Rect.intersects(showRect, novelRect))
				assertFalse(Rect.intersects(showRect, mangaRect))
				assertEquals(2, group.childCount)
				novel.performClick()
				assertEquals(R.id.button_content_novel, group.checkedButtonId)
				assertTrue(FavouriteHeadingPreferences.isCollapsed(activity))
				manga.performClick()
				assertEquals(R.id.button_content_manga, group.checkedButtonId)
				assertTrue(FavouriteHeadingPreferences.isCollapsed(activity))
			}
			await("Chevron must fade without removing its touch target") {
				val control = it.findViewById<View>(R.id.button_favourites_heading_show)
				control.isShown && control.alpha == 0f && control.width > 0
			}
			scenario.onActivity { activity ->
				val control = activity.findViewById<View>(R.id.button_favourites_heading_show)
				control.performClick()
				assertTrue(FavouriteHeadingPreferences.isCollapsed(activity))
			}
			await("First click reveals only") {
				it.findViewById<View>(R.id.button_favourites_heading_show).alpha > .99f
			}
			scenario.onActivity { activity ->
				activity.findViewById<View>(R.id.button_favourites_heading_show).performClick()
			}
			await("Second click expands the heading") {
				it.findViewById<View>(R.id.favourites_heading_copy).let { v -> v.isShown && v.alpha > .99f }
			}
			scenario.onActivity { activity ->
				assertFalse(FavouriteHeadingPreferences.isCollapsed(activity))
				activity.findViewById<View>(R.id.button_favourites_heading_hide).performClick()
			}
			await("Collapsed before recreation") {
				!it.findViewById<View>(R.id.favourites_heading_copy).isShown
			}
			scenario.recreate()
			await("Collapsed preference survives recreation") {
				it.findViewById<View>(R.id.favourites_heading_copy)?.isShown == false &&
					it.findViewById<View>(R.id.button_favourites_heading_show)?.isShown == true &&
					FavouriteHeadingPreferences.isCollapsed(it)
			}
		}
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
