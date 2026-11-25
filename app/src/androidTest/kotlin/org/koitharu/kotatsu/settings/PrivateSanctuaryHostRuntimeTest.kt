package org.koitharu.kotatsu.settings

import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
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
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import org.koitharu.kotatsu.core.ui.MiyorarePrivateFavouritesHeaderDrawable
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.favourites.ui.PrivateWorkspaceFragment
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesProtection
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSession
import javax.inject.Inject
import javax.inject.Provider

/** Actual hosts, synthetic test-session state only. Never relax window capture to collect evidence. */
@HiltAndroidTest
class PrivateSanctuaryHostRuntimeTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var security: PrivateFavouritesSecurityStore
	@Inject lateinit var session: PrivateFavouritesSession
	@Inject @BaseHttpClient lateinit var baseHttpClient: Provider<OkHttpClient>

	private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Before
	fun setUp() {
		if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().build())
		instrumentation.runOnMainSync {
			hilt.inject()
		}
		runBlocking { withContext(Dispatchers.IO) { baseHttpClient.get() } }
	}

	@Test
	fun settingsHeaderDoesNotLeakAcrossDestinationsOrFollowNormal() = withPrivateFixture {
		val intent = Intent(context, FavouritesActivity::class.java)
			.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue)
		ActivityScenario.launch<FavouritesActivity>(intent).use { scenario ->
			awaitWorkspace(scenario, settings = false, fade = false)
			navigate(scenario, R.id.private_nav_settings)
			awaitWorkspace(scenario, settings = true, fade = true)
			scenario.onActivity {
				selectTheme(PrivateFavouritesThemePreset.AMBER)
				it.findViewById<BottomNavigationView>(R.id.private_workspace_navigation).selectedItemId = R.id.private_nav_explore
			}
			awaitWorkspace(scenario, settings = false, fade = false)
			navigate(scenario, R.id.private_nav_history)
			awaitWorkspace(scenario, settings = false, fade = false)
			navigate(scenario, R.id.private_nav_settings)
			awaitWorkspace(scenario, settings = true, fade = true)
			scenario.onActivity { selectTheme(PrivateFavouritesThemePreset.FOLLOW_NORMAL) }
			awaitWorkspace(scenario, settings = true, fade = false)
			scenario.onActivity { selectTheme(PrivateFavouritesThemePreset.SAKURA) }
			awaitWorkspace(scenario, settings = true, fade = true)
			scenario.recreate()
			awaitWorkspace(scenario, settings = true, fade = true)
			scenario.onActivity {
				assertTrue(session.isUnlocked.value)
				assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
			}
		}
	}

	@Test
	fun dedicatedSettingsRouteRemainsPrivateAndSecureOnThemeSwitch() = withPrivateFixture {
		val intent = Intent(context, SettingsActivity::class.java).setAction(AppRouter.ACTION_PRIVATE_FAVOURITES_SETTINGS)
		ActivityScenario.launch<SettingsActivity>(intent).use { scenario ->
			await {
				var ready = false
				scenario.onActivity {
					ready = it.supportFragmentManager.findFragmentById(R.id.container) is PrivateFavouritesSettingsFragment
				}
				ready
			}
			scenario.onActivity { selectTheme(PrivateFavouritesThemePreset.CYAN) }
			instrumentation.waitForIdleSync()
			scenario.onActivity {
				assertTrue(it.supportFragmentManager.findFragmentById(R.id.container) is PrivateFavouritesSettingsFragment)
				assertTrue(session.isUnlocked.value)
				assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
			}
		}
	}

	private fun selectTheme(theme: PrivateFavouritesThemePreset) {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putString(MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME, theme.name).apply()
	}

	private fun navigate(scenario: ActivityScenario<FavouritesActivity>, id: Int) {
		scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.private_workspace_navigation).selectedItemId = id }
	}

	private fun awaitWorkspace(scenario: ActivityScenario<FavouritesActivity>, settings: Boolean, fade: Boolean) {
		await {
			var matches = false
			scenario.onActivity {
				val workspace = it.supportFragmentManager.findFragmentById(R.id.container) as? PrivateWorkspaceFragment
				val current = workspace?.childFragmentManager?.primaryNavigationFragment
				val actualFade = (it.appBar.background as? MiyorarePrivateFavouritesHeaderDrawable)?.hasSanctuaryFade == true
				matches = current?.view != null && (current is PrivateFavouritesSettingsFragment) == settings && actualFade == fade
			}
			matches
		}
	}

	private fun await(predicate: () -> Boolean) {
		val deadline = SystemClock.elapsedRealtime() + 15_000
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			if (predicate()) return
			SystemClock.sleep(50)
		}
		assertTrue("Expected active destination and header state were not reached", predicate())
	}

	private fun withPrivateFixture(block: () -> Unit) {
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val keys = listOf(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME)
		val old = keys.associateWith { prefs.getString(it, null) }
		val oldProtection = security.protection
		val oldScreenshots = security.allowPrivateScreenshots
		val oldUnlocked = session.isUnlocked.value
		try {
			security.protection = PrivateFavouritesProtection.BIOMETRIC
			security.allowPrivateScreenshots = false
			session.unlock()
			prefs.edit().putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name)
				.putString(MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME, PrivateFavouritesThemePreset.MIYORARE.name).commit()
			block()
		} finally {
			prefs.edit().apply { old.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
			security.protection = oldProtection
			security.allowPrivateScreenshots = oldScreenshots
			if (oldUnlocked) session.unlock() else session.lock()
		}
	}
}
