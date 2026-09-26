package org.koitharu.kotatsu.main.ui.nav

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

		equipNavigation(RankThemeId.FIRST_LIGHT)
	}

	@Test
	fun productionSelectionAndAmbientMotionChangeRenderedFrames() {
		AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("id-ID"))
		val activity = instrumentation.startActivitySync(
			Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as MainActivity

		try {
			val nav = waitForBottomNav(activity)
			SystemClock.sleep(600)

			// Prove the authored selected-state choreography for every Exclusive navigation.
			// Each theme is activated, then the real production tab selection is changed and an
			// intermediate frame is compared with the same tab after its 160–240ms reveal settles.
			val selectionEvidence = linkedMapOf<String, Double>()
			var targetId = R.id.nav_explore
			for ((index, spec) in ExclusiveBottomNavigationRegistry.presets.withIndex()) {
				val theme = checkNotNull(RankThemeId.fromStableId(spec.stableId))
				equipNavigation(theme)
				waitForThemeChange()
				targetId = if (targetId == R.id.nav_explore) R.id.nav_favorites else R.id.nav_explore
				instrumentation.runOnMainSync { nav.selectedItemId = targetId }
				SystemClock.sleep(55)
				val mid = captureNav(activity)
				SystemClock.sleep(300)
				val settled = captureNav(activity)
				val delta = changedPixelRatio(mid, settled)
				selectionEvidence[theme.stableId] = delta
				assertTrue(
					theme.stableId + " selection must render intermediate motion, delta=" + delta,
					delta > 0.001,
				)
				writePng("%02d-selection-%s-mid.png".format(index + 1, theme.stableId), mid)
				writePng("%02d-selection-%s-settled.png".format(index + 1, theme.stableId), settled)
			}

			val ambientEvidence = linkedMapOf<String, Double>()
			for (theme in listOf(
				RankThemeId.CYAN_CODEX,
				RankThemeId.IMPERIAL_AURORA,
				RankThemeId.ETERNAL_LIBRARY,
			)) {
				equipNavigation(theme)
				waitForThemeChange()
				// Let the one-shot selection choreography complete before measuring idle motion.
				SystemClock.sleep(if (theme == RankThemeId.CYAN_CODEX) 500 else 1_700)
				val start = captureNav(activity)
				SystemClock.sleep(800)
				val end = captureNav(activity)
				val delta = changedPixelRatio(start, end)
				ambientEvidence[theme.stableId] = delta
				assertTrue(
					theme.stableId + " ambient motion must change rendered pixels, delta=" + delta,
					delta > 0.0003,
				)
				writePng("ambient-" + theme.stableId + "-start.png", start)
				writePng("ambient-" + theme.stableId + "-end.png", end)
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
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
			AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
		}
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

	private fun captureNav(activity: MainActivity): Bitmap {
		val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
		val rect = activity.findViewById<android.view.View>(R.id.bottomNav).screenRect().expand(
			horizontal = 12,
			vertical = 12,
			maxWidth = screenshot.width,
			maxHeight = screenshot.height,
		)
		return Bitmap.createBitmap(screenshot, rect.left, rect.top, rect.width(), rect.height())
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

	private fun android.view.View.screenRect(): Rect {
		val location = IntArray(2)
		getLocationOnScreen(location)
		return Rect(location[0], location[1], location[0] + width, location[1] + height)
	}

	private fun Rect.expand(horizontal: Int, vertical: Int, maxWidth: Int, maxHeight: Int): Rect = Rect(
		(left - horizontal).coerceAtLeast(0),
		(top - vertical).coerceAtLeast(0),
		(right + horizontal).coerceAtMost(maxWidth),
		(bottom + vertical).coerceAtMost(maxHeight),
	)

	private companion object {
		const val MOTION_MANGA_ID = 990_101L
		const val MOTION_CHAPTER_ID = 1L
	}
}
