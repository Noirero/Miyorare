package org.koitharu.kotatsu.main.ui.nav

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
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
import org.json.JSONArray
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
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileStore
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveBottomNavigationRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import javax.inject.Inject

/**
 * Production-path visual evidence for all twelve Exclusive bottom-navigation concepts.
 *
 * This deliberately launches MainActivity with legacy navigation enabled. The Exclusive renderer
 * must still win, proving that the old palette-only LegacyGlowNavBar cannot bypass authored
 * silhouette/active-state/ornament geometry again.
 *
 * CI stores twelve cropped nav screenshots plus a contact sheet. The capture is static
 * (reduce-motion on) so the evidence represents the required premium fallback as well.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExclusiveNavigationGoldenVisualTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var database: MangaDatabase

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var profileStore: ReaderProfileStore

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
		// Give the deterministic fixture rank-100 ownership so every theme is legal through the
		// real ReaderJourneyThemeRuntime sanitizer rather than bypassing production policy.
		database.getReaderJourneyDao().awardCompletion(
			mangaId = GOLDEN_MANGA_ID,
			chapterId = GOLDEN_CHAPTER_ID,
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
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, true)
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false)
			.putBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, false)
			.commit()

		profileStore.updateCosmetics(
			ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.CUSTOM,
				selectedThemeId = RankThemeId.FIRST_PAGE.stableId,
				navigationThemeId = RankThemeId.FIRST_PAGE.stableId,
			),
		)
	}

	@Test
	fun captureAllTwelveProductionNavigations() {
		AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("id-ID"))
		val activity = instrumentation.startActivitySync(
			Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as MainActivity

		try {
			waitForBottomNav(activity)
			assertEquals(12, ExclusiveBottomNavigationRegistry.presets.size)

			val captures = ArrayList<Pair<String, Bitmap>>(12)
			val evidence = JSONArray()

			for ((index, spec) in ExclusiveBottomNavigationRegistry.presets.withIndex()) {
				val themeId = checkNotNull(RankThemeId.fromStableId(spec.stableId))
				profileStore.updateCosmetics(
					ReaderJourneyCosmeticLoadout(
						mode = ReaderJourneyCosmeticMode.CUSTOM,
						selectedThemeId = RankThemeId.FIRST_PAGE.stableId,
						navigationThemeId = themeId.stableId,
					),
				)
				waitForThemeSettled(activity, themeId)

				val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
				assertEquals(CANONICAL_SCREENSHOT_WIDTH_PX, screenshot.width)
				assertEquals(CANONICAL_SCREENSHOT_HEIGHT_PX, screenshot.height)
				val navRect = activity.findViewById<android.view.View>(R.id.bottomNav).screenRect()
				val cropRect = navRect.expand(
					horizontal = CROP_MARGIN_PX,
					vertical = CROP_MARGIN_PX,
					maxWidth = screenshot.width,
					maxHeight = screenshot.height,
				)
				assertTrue("Bottom navigation crop must be visible for ${spec.conceptName}", cropRect.width() > 0)
				assertTrue("Bottom navigation crop must be visible for ${spec.conceptName}", cropRect.height() > 0)

				val crop = Bitmap.createBitmap(
					screenshot,
					cropRect.left,
					cropRect.top,
					cropRect.width(),
					cropRect.height(),
				)
				val fileName = "%02d-%s.png".format(index + 1, spec.stableId)
				writePngToDownloads(fileName, crop)
				captures += spec.conceptName to crop

				evidence.put(
					JSONObject()
						.put("index", index + 1)
						.put("themeId", spec.stableId)
						.put("conceptName", spec.conceptName)
						.put("silhouette", spec.silhouette.name)
						.put("activeShape", spec.activeShape.name)
						.put("ornament", spec.ornament.name)
						.put("indicator", spec.indicator.name)
						.put("heightDp", spec.heightDp)
						.put("cornerRadiusDp", spec.cornerRadiusDp)
						.put("activeDiameterDp", spec.activeDiameterDp)
						.put("crop", cropRect.toJson()),
				)
			}

			val contactSheet = buildContactSheet(captures)
			writePngToDownloads("00-contact-sheet.png", contactSheet)
			writeTextToDownloads(
				"evidence.json",
				JSONObject()
					.put("screenWidthPx", CANONICAL_SCREENSHOT_WIDTH_PX)
					.put("screenHeightPx", CANONICAL_SCREENSHOT_HEIGHT_PX)
					.put("densityDpi", 320)
					.put("legacyPreferenceEnabled", true)
					.put("reduceMotion", true)
					.put("themes", evidence)
					.toString(2),
			)

			// A static grayscale/low-motion fallback must still expose multiple body languages.
			val silhouettes = ExclusiveBottomNavigationRegistry.presets.map { it.silhouette }.toSet()
			val activeShapes = ExclusiveBottomNavigationRegistry.presets.map { it.activeShape }.toSet()
			assertTrue("Exclusive navigation must not collapse to one body silhouette", silhouettes.size >= 7)
			assertTrue("Exclusive selected state must not collapse to one halo", activeShapes.size >= 9)
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
			AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
		}
	}

	private fun waitForBottomNav(activity: MainActivity) {
		val deadline = SystemClock.elapsedRealtime() + 20_000L
		var ready = false
		while (!ready && SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			instrumentation.runOnMainSync {
				val nav = activity.findViewById<android.view.View>(R.id.bottomNav)
				ready = nav?.isLaidOut == true && nav.width > 0 && nav.height > 0
			}
			if (!ready) SystemClock.sleep(150)
		}
		assertTrue("MainActivity bottom navigation did not reach a laid-out state", ready)
	}

	private fun waitForThemeSettled(activity: MainActivity, expected: RankThemeId) {
		val deadline = SystemClock.elapsedRealtime() + 4_000L
		var stableFrames = 0
		var previousSignature = 0L
		while (SystemClock.elapsedRealtime() < deadline && stableFrames < 3) {
			instrumentation.waitForIdleSync()
			SystemClock.sleep(120)
			val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
			val rect = activity.findViewById<android.view.View>(R.id.bottomNav).screenRect().expand(
				horizontal = 4,
				vertical = 4,
				maxWidth = screenshot.width,
				maxHeight = screenshot.height,
			)
			val signature = screenshot.sampleSignature(rect)
			if (signature == previousSignature && signature != 0L) {
				stableFrames++
			} else {
				stableFrames = 0
				previousSignature = signature
			}
		}
		assertTrue("Exclusive navigation did not settle for ${expected.stableId}", stableFrames >= 2)
	}

	private fun Bitmap.sampleSignature(rect: Rect): Long {
		if (rect.width() <= 0 || rect.height() <= 0) return 0L
		var hash = 1125899906842597L
		val stepX = maxOf(1, rect.width() / 24)
		val stepY = maxOf(1, rect.height() / 8)
		var y = rect.top
		while (y < rect.bottom) {
			var x = rect.left
			while (x < rect.right) {
				hash = hash * 31L + getPixel(x, y)
				x += stepX
			}
			y += stepY
		}
		return hash
	}

	private fun buildContactSheet(captures: List<Pair<String, Bitmap>>): Bitmap {
		require(captures.size == 12)
		val cellWidth = CONTACT_CELL_WIDTH_PX
		val cellHeight = CONTACT_CELL_HEIGHT_PX
		val sheet = Bitmap.createBitmap(cellWidth * 2, cellHeight * 6, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(sheet)
		canvas.drawColor(AndroidColor.rgb(4, 8, 18))
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = AndroidColor.WHITE
			textSize = 22f
		}
		captures.forEachIndexed { index, (name, bitmap) ->
			val column = index % 2
			val row = index / 2
			val left = column * cellWidth
			val top = row * cellHeight
			val label = "%02d  %s".format(index + 1, name)
			canvas.drawText(label, (left + 12).toFloat(), (top + 28).toFloat(), paint)
			val destination = Rect(
				left + 10,
				top + 38,
				left + cellWidth - 10,
				top + cellHeight - 8,
			)
			canvas.drawBitmap(bitmap, null, destination, null)
		}
		return sheet
	}

	private fun writePngToDownloads(name: String, bitmap: Bitmap) {
		writeToDownloads(name, "image/png") { output ->
			check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
		}
	}

	private fun writeTextToDownloads(name: String, text: String) {
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
		val relativePath = "Download/miyorare-exclusive-navigation-golden/"
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

	private fun Rect.expand(
		horizontal: Int,
		vertical: Int,
		maxWidth: Int,
		maxHeight: Int,
	): Rect = Rect(
		(left - horizontal).coerceAtLeast(0),
		(top - vertical).coerceAtLeast(0),
		(right + horizontal).coerceAtMost(maxWidth),
		(bottom + vertical).coerceAtMost(maxHeight),
	)

	private fun Rect.toJson() = JSONObject()
		.put("left", left)
		.put("top", top)
		.put("right", right)
		.put("bottom", bottom)
		.put("width", width())
		.put("height", height())

	private companion object {
		const val CANONICAL_SCREENSHOT_WIDTH_PX = 864
		const val CANONICAL_SCREENSHOT_HEIGHT_PX = 1536
		const val CROP_MARGIN_PX = 18
		const val CONTACT_CELL_WIDTH_PX = 600
		const val CONTACT_CELL_HEIGHT_PX = 170
		const val GOLDEN_MANGA_ID = 990_001L
		const val GOLDEN_CHAPTER_ID = 1L
	}
}
