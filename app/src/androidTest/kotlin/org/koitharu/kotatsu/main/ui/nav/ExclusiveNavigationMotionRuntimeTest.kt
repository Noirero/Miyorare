package org.koitharu.kotatsu.main.ui.nav

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.ui.widgets.FloatingBottomNavigationView
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileStore
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveBottomNavigationRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import javax.inject.Inject

/**
 * Runtime proof that Exclusive navigation actually moves on Android.
 *
 * Golden screenshots intentionally run with animations disabled, so they cannot protect the
 * motion contract. This test keeps system animations enabled, drives the real MainActivity
 * bottom-navigation view, and asserts that intermediate frames differ from settled frames.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExclusiveNavigationMotionRuntimeTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	@Inject lateinit var database: MangaDatabase
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var profileStore: ReaderProfileStore

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Before
	fun setUp() = runBlocking {
		hiltRule.inject()
		runCatching { WorkManager.getInstance(context) }.getOrElse {
			WorkManager.initialize(context, Configuration.Builder().build())
			WorkManager.getInstance(context)
		}
		database.clearAllTables()
		database.getReaderJourneyDao().awardCompletion(
			mangaId = MOTION_MANGA_ID,
			chapterId = MOTION_CHAPTER_ID,
			isNovel = false,
			readingUnits = 100,
			baseXp = 1_000_000,
			completedAt = 1L,
		)

		settings.isOnboardingCompleted = true
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		settings.setMiyorareThemePreset(MiyorareThemePreset.MIYORARE)
		settings.mainNavItems = listOf(
			NavItem.FAVORITES,
			NavItem.EXPLORE,
			NavItem.BOOKMARKS,
			NavItem.LOCAL,
		)

		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_NAV_LEGACY, true)
			.putBoolean(AppSettings.KEY_NAV_LABELS, true)
			.putBoolean(AppSettings.KEY_RANK_THEME_ENABLED, true)
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false)
			.putBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, false)
			.commit()

		setPowerSaveMode(false)
		equipNavigation(RankThemeId.FIRST_LIGHT)
	}

	@Test
	fun productionPressChangesRenderedFrameWithoutChangingSelection() {
		equipNavigation(RankThemeId.FIRST_PAGE)
		waitForThemeChange()
		val activity = startMotionActivity()
		try {
			val nav = waitForBottomNav(activity)
			SystemClock.sleep(500)
			val selectedBefore = nav.selectedItemId
			val compose = findComposeView(nav)
			val orderedIds = listOf(
				R.id.nav_favorites,
				R.id.nav_explore,
				R.id.nav_bookmarks,
				R.id.nav_local,
			)
			val index = orderedIds.indexOf(selectedBefore).coerceAtLeast(0)
			val x = compose.width * (index + .5f) / orderedIds.size.toFloat()
			val y = compose.height * .5f
			val downTime = SystemClock.uptimeMillis()

			instrumentation.runOnMainSync {
				compose.dispatchTouchEvent(
					MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0),
				)
			}
			// Hold the press long enough for the 90 ms scale to settle, then capture through
			// UiAutomation so the hardware/compositor graphicsLayer transform is included.
			SystemClock.sleep(120)
			val pressed = captureNavFromWindow(activity)

			instrumentation.runOnMainSync {
				compose.dispatchTouchEvent(
					MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0),
				)
			}
			SystemClock.sleep(180)
			val released = captureNavFromWindow(activity)
			val pressDelta = changedPixelRatio(pressed, released)
			assertEquals("Press proof must not change navigation selection", selectedBefore, nav.selectedItemId)
			assertTrue("Press scale must change rendered production pixels, delta=$pressDelta", pressDelta > 0.0001)

			writePng("press-first-page-down.png", pressed)
			writePng("press-first-page-released.png", released)
			writeText(
				"press-evidence.json",
				JSONObject()
					.put("theme", RankThemeId.FIRST_PAGE.stableId)
					.put("pressDelta", pressDelta)
					.toString(2),
			)
		} finally {
			finishMotionActivity(activity)
		}
	}

	@Test
	fun batterySaverKeepsSelectionButStopsCyanAmbientLoop() {
		equipNavigation(RankThemeId.CYAN_CODEX)
		waitForThemeChange()
		setPowerSaveMode(true)
		var activity: MainActivity? = null
		try {
			val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
			assertTrue("Battery Saver must be active for this runtime proof", powerManager.isPowerSaveMode)

			activity = startMotionActivity()
			val nav = waitForBottomNav(activity)
			SystemClock.sleep(400)
			val targetId = if (activeNav.selectedItemId == R.id.nav_explore) R.id.nav_favorites else R.id.nav_explore
			instrumentation.runOnMainSync { activeNav.selectedItemId = targetId }
			SystemClock.sleep(55)
			val selectionMid = captureNav(activeActivity)
			SystemClock.sleep(300)
			val selectionSettled = captureNav(activeActivity)
			val selectionDelta = changedPixelRatio(selectionMid, selectionSettled)
			assertTrue(
				"Battery Saver must keep selection feedback, delta=$selectionDelta",
				selectionDelta > 0.001,
			)

			SystemClock.sleep(300)
			val ambientStart = captureNav(activity)
			SystemClock.sleep(800)
			val ambientEnd = captureNav(activity)
			val ambientDelta = changedPixelRatio(ambientStart, ambientEnd)
			assertTrue(
				"Battery Saver must stop Cyan Orbit ambient loop, delta=$ambientDelta",
				ambientDelta < 0.0001,
			)

			writePng("battery-saver-cyan-selection-mid.png", selectionMid)
			writePng("battery-saver-cyan-selection-settled.png", selectionSettled)
			writeText(
				"battery-saver-evidence.json",
				JSONObject()
					.put("theme", RankThemeId.CYAN_CODEX.stableId)
					.put("selectionDelta", selectionDelta)
					.put("ambientDelta", ambientDelta)
					.toString(2),
			)
		} finally {
			activity?.let(::finishMotionActivity)
			setPowerSaveMode(false)
		}
	}

	@Test
	fun reduceMotionKeepsShortSelectionButStopsCyanAmbientLoop() {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, true)
			.commit()
		equipNavigation(RankThemeId.CYAN_CODEX)
		waitForThemeChange()

		val activity = startMotionActivity()
		try {
			val nav = waitForBottomNav(activity)
			SystemClock.sleep(400)
			val targetId = if (nav.selectedItemId == R.id.nav_explore) R.id.nav_favorites else R.id.nav_explore
			instrumentation.runOnMainSync { nav.selectedItemId = targetId }
			SystemClock.sleep(55)
			val selectionMid = captureNav(activity)
			SystemClock.sleep(180)
			val selectionSettled = captureNav(activity)
			val selectionDelta = changedPixelRatio(selectionMid, selectionSettled)
			assertTrue(
				"Reduce Motion must keep short selection feedback, delta=$selectionDelta",
				selectionDelta > 0.001,
			)

			SystemClock.sleep(300)
			val ambientStart = captureNav(activity)
			SystemClock.sleep(800)
			val ambientEnd = captureNav(activity)
			val ambientDelta = changedPixelRatio(ambientStart, ambientEnd)
			assertTrue(
				"Reduce Motion must stop Cyan Orbit ambient loop, delta=$ambientDelta",
				ambientDelta < 0.0001,
			)

			writePng("reduce-motion-cyan-selection-mid.png", selectionMid)
			writePng("reduce-motion-cyan-selection-settled.png", selectionSettled)
			writeText(
				"reduce-motion-evidence.json",
				JSONObject()
					.put("theme", RankThemeId.CYAN_CODEX.stableId)
					.put("selectionDelta", selectionDelta)
					.put("ambientDelta", ambientDelta)
					.toString(2),
			)
		} finally {
			finishMotionActivity(activity)
		}
	}

	@Test
	fun applyingExclusiveNavigationWhileMainActivityIsRunningReachesProductionMotionRenderer() {
		val animatorScale = Settings.Global.getFloat(
			context.contentResolver,
			Settings.Global.ANIMATOR_DURATION_SCALE,
			0f,
		)
		assertTrue("Runtime motion evidence requires animator_duration_scale > 0, was $animatorScale", animatorScale > 0f)

		// This is deliberately different from the fresh-launch loop below: MainActivity is already
		// resumed when the Customizer-equivalent loadout update occurs.
		val activity = startMotionActivity()
		var activeActivity = activity
		try {
			waitForBottomNav(activity)
			SystemClock.sleep(500)
			val beforeApply = captureNav(activity)

			equipNavigation(RankThemeId.CYAN_CODEX)
			waitForThemeChange()
			SystemClock.sleep(500)
			// Applying an Exclusive theme can recreate MainActivity. Always follow the currently
			// RESUMED production instance instead of continuing with a detached pre-recreate view.
			activeActivity = waitForResumedMainActivity()
			val activeNav = waitForBottomNav(activeActivity)
			val afterApply = captureNav(activeActivity)
			val applyDelta = changedPixelRatioAllowResize(beforeApply, afterApply)
			assertTrue(
				"Applying Cyan Orbit while MainActivity is running must change the production nav, delta=$applyDelta",
				applyDelta > 0.001,
			)

			val targetId = if (activeNav.selectedItemId == R.id.nav_explore) R.id.nav_favorites else R.id.nav_explore
			instrumentation.runOnMainSync { activeNav.selectedItemId = targetId }
			SystemClock.sleep(55)
			val selectionMid = captureNav(activeActivity)
			SystemClock.sleep(300)
			val selectionSettled = captureNav(activeActivity)
			val selectionDelta = changedPixelRatio(selectionMid, selectionSettled)
			assertTrue(
				"Live-applied Cyan Orbit must keep real selection motion, delta=$selectionDelta",
				selectionDelta > 0.001,
			)

			SystemClock.sleep(500)
			val ambientStart = captureNav(activeActivity)
			SystemClock.sleep(800)
			val ambientEnd = captureNav(activeActivity)
			val ambientDelta = changedPixelRatio(ambientStart, ambientEnd)
			assertTrue(
				"Live-applied Cyan Orbit must animate on the production renderer, delta=$ambientDelta",
				ambientDelta > 0.0003,
			)

			writePng("apply-running-cyan-before.png", beforeApply)
			writePng("apply-running-cyan-after.png", afterApply)
			writePng("apply-running-cyan-selection-mid.png", selectionMid)
			writePng("apply-running-cyan-selection-settled.png", selectionSettled)
			writeText(
				"apply-runtime-evidence.json",
				JSONObject()
					.put("theme", RankThemeId.CYAN_CODEX.stableId)
					.put("applyDelta", applyDelta)
					.put("selectionDelta", selectionDelta)
					.put("ambientDelta", ambientDelta)
					.toString(2),
			)
		} finally {
			finishMotionActivity(activeActivity)
		}
	}

	@Test
	fun productionSelectionAndAmbientMotionChangeRenderedFrames() {
		val animatorScale = Settings.Global.getFloat(
			context.contentResolver,
			Settings.Global.ANIMATOR_DURATION_SCALE,
			0f,
		)
		assertTrue("Runtime motion evidence requires animator_duration_scale > 0, was $animatorScale", animatorScale > 0f)

		// Theme changes can recreate MainActivity. Test each preset from a fresh production activity
		// launched only after the loadout is already equipped so every captured nav belongs to the
		// currently RESUMED activity rather than a detached pre-recreation view.
		val selectionEvidence = linkedMapOf<String, Double>()
		for ((index, spec) in ExclusiveBottomNavigationRegistry.presets.withIndex()) {
			val theme = checkNotNull(RankThemeId.fromStableId(spec.stableId))
			equipNavigation(theme)
			waitForThemeChange()
			val activity = startMotionActivity()
			try {
				val nav = waitForBottomNav(activity)
				SystemClock.sleep(500)
				val targetId = if (nav.selectedItemId == R.id.nav_explore) {
					R.id.nav_favorites
				} else {
					R.id.nav_explore
				}
				instrumentation.runOnMainSync { nav.selectedItemId = targetId }
				assertEquals(
					theme.stableId + " must switch the production selected item before motion capture",
					targetId,
					nav.selectedItemId,
				)
				SystemClock.sleep(55)
				val mid = captureNav(activity)
				SystemClock.sleep(300)
				val settled = captureNav(activity)
				val delta = changedPixelRatio(mid, settled)
				selectionEvidence[theme.stableId] = delta
				writePng("%02d-selection-%s-mid.png".format(index + 1, theme.stableId), mid)
				writePng("%02d-selection-%s-settled.png".format(index + 1, theme.stableId), settled)
				assertTrue(
					theme.stableId + " selection must render intermediate motion, delta=" + delta,
					delta > 0.001,
				)
			} finally {
				finishMotionActivity(activity)
			}
		}

		val ambientEvidence = linkedMapOf<String, Double>()
		for (theme in listOf(
			RankThemeId.CYAN_CODEX,
			RankThemeId.IMPERIAL_AURORA,
			RankThemeId.ETERNAL_LIBRARY,
		)) {
			equipNavigation(theme)
			waitForThemeChange()
			val activity = startMotionActivity()
			try {
				waitForBottomNav(activity)
				SystemClock.sleep(if (theme == RankThemeId.CYAN_CODEX) 500 else 1_700)
				val start = captureNav(activity)
				SystemClock.sleep(800)
				val end = captureNav(activity)
				val delta = changedPixelRatio(start, end)
				ambientEvidence[theme.stableId] = delta
				writePng("ambient-" + theme.stableId + "-start.png", start)
				writePng("ambient-" + theme.stableId + "-end.png", end)
				assertTrue(
					theme.stableId + " ambient motion must change rendered pixels, delta=" + delta,
					delta > 0.0003,
				)
			} finally {
				finishMotionActivity(activity)
			}
		}

		val ambientJson = JSONObject()
		ambientEvidence.forEach { (theme, delta) -> ambientJson.put(theme, delta) }
		val selectionJson = JSONObject()
		selectionEvidence.forEach { (theme, delta) -> selectionJson.put(theme, delta) }
		writeText(
			"motion-evidence.json",
			JSONObject()
				.put("selection", selectionJson)
				.put("ambient", ambientJson)
				.toString(2),
		)
	}

	private fun findComposeView(root: View): ComposeView {
		if (root is ComposeView) return root
		if (root is ViewGroup) {
			for (index in 0 until root.childCount) {
				runCatching { return findComposeView(root.getChildAt(index)) }
			}
		}
		error("ComposeView not found in production bottom navigation")
	}

	private fun setPowerSaveMode(enabled: Boolean) {
		if (enabled) {
			instrumentation.uiAutomation.executeShellCommand("dumpsys battery unplug").close()
			instrumentation.uiAutomation.executeShellCommand("dumpsys battery set level 15").close()
		}
		instrumentation.uiAutomation.executeShellCommand(
			"cmd power set-mode " + if (enabled) "1" else "0",
		).close()

		val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
		val deadline = SystemClock.elapsedRealtime() + 5_000L
		while (SystemClock.elapsedRealtime() < deadline && powerManager.isPowerSaveMode != enabled) {
			instrumentation.waitForIdleSync()
			SystemClock.sleep(200)
		}
		if (!enabled) {
			instrumentation.uiAutomation.executeShellCommand("dumpsys battery reset").close()
		}
	}

	private fun startMotionActivity(): MainActivity {
		val activity = instrumentation.startActivitySync(
			Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as MainActivity
		instrumentation.waitForIdleSync()
		return activity
	}

	private fun finishMotionActivity(activity: MainActivity) {
		instrumentation.runOnMainSync {
			if (!activity.isFinishing) activity.finish()
		}
		instrumentation.waitForIdleSync()
		SystemClock.sleep(120)
	}

	private fun equipNavigation(theme: RankThemeId) {
		profileStore.updateCosmetics(
			ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.CUSTOM,
				selectedThemeId = RankThemeId.FIRST_PAGE.stableId,
				navigationThemeId = theme.stableId,
			),
		)
	}

	private fun waitForThemeChange() {
		instrumentation.waitForIdleSync()
		SystemClock.sleep(180)
	}

	private fun waitForBottomNav(activity: MainActivity): FloatingBottomNavigationView {
		val deadline = SystemClock.elapsedRealtime() + 20_000L
		var nav: FloatingBottomNavigationView? = null
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			instrumentation.runOnMainSync {
				nav = activity.findViewById(R.id.bottomNav)
			}
			if (nav?.isLaidOut == true && nav!!.width > 0 && nav!!.height > 0) break
			SystemClock.sleep(120)
		}
		return checkNotNull(nav).also {
			assertTrue("Bottom navigation must be laid out", it.isLaidOut && it.width > 0 && it.height > 0)
		}
	}

	private fun waitForResumedMainActivity(): MainActivity {
		val deadline = SystemClock.elapsedRealtime() + 10_000L
		var resumed: MainActivity? = null
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			instrumentation.runOnMainSync {
				resumed = ActivityLifecycleMonitorRegistry.getInstance()
					.getActivitiesInStage(Stage.RESUMED)
					.filterIsInstance<MainActivity>()
					.lastOrNull()
			}
			if (resumed != null) break
			SystemClock.sleep(120)
		}
		return checkNotNull(resumed) { "No RESUMED MainActivity after Exclusive theme application" }
	}

	private fun captureNavFromWindow(activity: MainActivity): Bitmap {
		val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
		val nav = activity.findViewById<View>(R.id.bottomNav)
		val location = IntArray(2)
		instrumentation.runOnMainSync { nav.getLocationOnScreen(location) }
		val rect = Rect(
			location[0].coerceAtLeast(0),
			location[1].coerceAtLeast(0),
			(location[0] + nav.width).coerceAtMost(screenshot.width),
			(location[1] + nav.height).coerceAtMost(screenshot.height),
		)
		check(rect.width() > 0 && rect.height() > 0) { "Bottom navigation has no visible window bounds" }
		return Bitmap.createBitmap(screenshot, rect.left, rect.top, rect.width(), rect.height())
	}

	private fun changedPixelRatioAllowResize(a: Bitmap, b: Bitmap): Double {
		val width = maxOf(a.width, b.width)
		val height = maxOf(a.height, b.height)
		var changed = 0L
		var sampled = 0L
		var y = 0
		while (y < height) {
			var x = 0
			while (x < width) {
				val ca = if (x < a.width && y < a.height) a.getPixel(x, y) else 0
				val cb = if (x < b.width && y < b.height) b.getPixel(x, y) else 0
				val dr = kotlin.math.abs(android.graphics.Color.red(ca) - android.graphics.Color.red(cb))
				val dg = kotlin.math.abs(android.graphics.Color.green(ca) - android.graphics.Color.green(cb))
				val db = kotlin.math.abs(android.graphics.Color.blue(ca) - android.graphics.Color.blue(cb))
				val da = kotlin.math.abs(android.graphics.Color.alpha(ca) - android.graphics.Color.alpha(cb))
				if (dr + dg + db + da >= 9) changed++
				sampled++
				x += 2
			}
			y += 2
		}
		return changed.toDouble() / sampled.toDouble()
	}

	private fun captureNav(activity: MainActivity): Bitmap {
		var captured: Bitmap? = null
		instrumentation.runOnMainSync {
			val nav = activity.findViewById<android.view.View>(R.id.bottomNav)
			check(nav.width > 0 && nav.height > 0) { "Bottom navigation is not laid out for motion capture" }
			captured = Bitmap.createBitmap(nav.width, nav.height, Bitmap.Config.ARGB_8888).also { bitmap ->
				nav.draw(Canvas(bitmap))
			}
		}
		return checkNotNull(captured)
	}

	private fun changedPixelRatio(a: Bitmap, b: Bitmap): Double {
		require(a.width == b.width && a.height == b.height)
		var changed = 0L
		var sampled = 0L
		var y = 0
		while (y < a.height) {
			var x = 0
			while (x < a.width) {
				val ca = a.getPixel(x, y)
				val cb = b.getPixel(x, y)
				val dr = kotlin.math.abs(android.graphics.Color.red(ca) - android.graphics.Color.red(cb))
				val dg = kotlin.math.abs(android.graphics.Color.green(ca) - android.graphics.Color.green(cb))
				val db = kotlin.math.abs(android.graphics.Color.blue(ca) - android.graphics.Color.blue(cb))
				if (dr + dg + db >= 9) changed++
				sampled++
				x += 2
			}
			y += 2
		}
		return changed.toDouble() / sampled.toDouble()
	}

	private fun writePng(name: String, bitmap: Bitmap) {
		writeToDownloads(name, "image/png") { output ->
			check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
		}
	}

	private fun writeText(name: String, text: String) {
		writeToDownloads(name, "application/json") { output ->
			output.write(text.toByteArray())
		}
	}

	private fun writeToDownloads(
		name: String,
		mimeType: String,
		write: (java.io.OutputStream) -> Unit,
	) {
		val resolver = context.contentResolver
		val relativePath = "Download/miyorare-exclusive-navigation-motion/"
		resolver.delete(
			MediaStore.Downloads.EXTERNAL_CONTENT_URI,
			"${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
			arrayOf(relativePath, name),
		)
		val values = ContentValues().apply {
			put(MediaStore.MediaColumns.DISPLAY_NAME, name)
			put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
			put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
		}
		val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
		resolver.openOutputStream(uri, "w").use { output ->
			write(checkNotNull(output))
		}
	}

	private companion object {
		const val MOTION_MANGA_ID = 990_101L
		const val MOTION_CHAPTER_ID = 1L
	}
}
