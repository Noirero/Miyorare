package org.koitharu.kotatsu.stats.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.preference.PreferenceManager
import coil3.ImageLoader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileSettings
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsContentScope
import org.koitharu.kotatsu.stats.domain.StatsMatureMode
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.YearInReview
import java.io.OutputStream
import java.time.LocalDate
import kotlin.math.abs

/**
 * Final V2 smoke evidence using the actual production customization selector and StatsScreen.
 *
 * This intentionally keeps badge animation OFF. It validates the real UI containers after the
 * standalone V2 assets were approved, before Animation V2 is allowed to proceed.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BadgeV2ActualUiSmokeTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Test
	fun captureActualSelectorLargePreviewAndEquippedProfileWithAnimationOff() {
		hiltRule.inject()
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val oldReduceMotion = prefs.getBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
		check(prefs.edit().putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, true).commit()) {
			"Could not force Reduce Motion for deterministic static UI smoke"
		}
		val activity = instrumentation.startActivitySync(
			Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as StatsActivity
		val lastSpec = RankThemeVisualRegistry.all.last()
		val mode = mutableStateOf(SmokeMode.SELECTOR)
		val imageLoader = ImageLoader.Builder(context).build()
		val composeView = activity.findViewById<ComposeView>(org.koitharu.kotatsu.R.id.composeView)

		instrumentation.runOnMainSync {
			composeView.setContent {
				MaterialTheme {
					when (mode.value) {
						SmokeMode.SELECTOR -> Box(
							modifier = Modifier
								.fillMaxSize()
								.background(Color(0xFF050A15))
								.padding(horizontal = 18.dp, vertical = 28.dp),
							contentAlignment = Alignment.TopCenter,
						) {
							ExclusiveBadgeSelector(
								specs = RankThemeVisualRegistry.all,
								selectedBadgeId = lastSpec.badgeId,
								onSelect = {},
								animatePreview = false,
							)
						}
						SmokeMode.PROFILE -> {
							val profile = ReaderProfileSettings(
								displayName = "Miyorare",
								cosmetics = ReaderJourneyCosmeticLoadout(
									mode = ReaderJourneyCosmeticMode.CUSTOM,
									selectedThemeId = lastSpec.themeId.stableId,
									selectedBadgeId = lastSpec.badgeId,
								),
							)
							StatsScreen(
								stats = ReadingStats(
									period = StatsPeriod.ALL,
									scope = StatsContentScope.OVERVIEW,
									matureMode = StatsMatureMode.PRIVATE,
									lifetimeXp = 999_999L,
									titleCount = 1,
									isJourneyEnabled = true,
								),
								isLoading = false,
								period = StatsPeriod.ALL,
								scope = StatsContentScope.OVERVIEW,
								matureMode = StatsMatureMode.PRIVATE,
								categories = emptyList(),
								selectedCategories = emptySet(),
								imageLoader = imageLoader,
								profile = profile,
								yearInReview = YearInReview(LocalDate.now().year),
								bottomInset = 0.dp,
								onPeriodChange = {},
								onScopeChange = {},
								onMatureModeChange = {},
								onCategoryToggle = {},
								onCategoriesClear = {},
								onProfileUpdate = { _, _, _ -> },
								onCosmeticsUpdate = {},
								onWeeklyReroll = {},
								onShareReaderProfile = {},
								onShareYearInReview = {},
								onMangaClick = {},
							)
						}
					}
				}
			}
		}

		try {
			waitForLayout(composeView)
			SystemClock.sleep(450)
			val selectorA = captureView(composeView)
			SystemClock.sleep(900)
			val selectorB = captureView(composeView)
			val selectorDelta = normalizedPixelDelta(selectorA, selectorB)
			assertTrue("Actual badge selector must remain static with animation OFF; delta=$selectorDelta", selectorDelta < 0.001)
			writePng("actual-badge-selector-static.png", selectorB)

			val density = context.resources.displayMetrics.density
			val previewHeight = (230f * density).toInt().coerceAtMost(selectorB.height)
			val previewWidth = (360f * density).toInt().coerceAtMost(selectorB.width)
			val previewLeft = ((selectorB.width - previewWidth) / 2).coerceAtLeast(0)
			val preview = Bitmap.createBitmap(selectorB, previewLeft, 0, previewWidth, previewHeight)
			writePng("actual-large-preview-static.png", preview)

			instrumentation.runOnMainSync { mode.value = SmokeMode.PROFILE }
			instrumentation.waitForIdleSync()
			SystemClock.sleep(500)
			SystemClock.sleep(1_200)
			val profileA = captureView(composeView)
			SystemClock.sleep(1_100)
			val profileB = captureView(composeView)
			val profileFullDelta = normalizedPixelDelta(profileA, profileB)
			val badgeRegionA = cropActualProfileBadgeRegion(profileA, density)
			val badgeRegionB = cropActualProfileBadgeRegion(profileB, density)
			val profileBadgeDelta = normalizedPixelDelta(badgeRegionA, badgeRegionB)
			// Gate the actual equipped badge region rather than the whole StatsScreen. The screen
			// contains host/profile content outside the badge that may settle independently; any
			// badge clipping/shift/motion is still captured inside this production-layout region.
			assertTrue(
				"Actual equipped profile badge region must remain static with animation OFF; delta=$profileBadgeDelta",
				profileBadgeDelta < 0.003,
			)
			val profileHeight = (330f * density).toInt().coerceAtMost(profileB.height)
			val profile = Bitmap.createBitmap(profileB, 0, 0, profileB.width, profileHeight)
			writePng("actual-profile-equipped-static.png", profile)
			writePng("actual-profile-badge-region-static.png", badgeRegionB)

			writeJson(
				"actual-ui-smoke.json",
				JSONObject()
					.put("badge", lastSpec.themeId.stableId)
					.put("asset", lastSpec.badgeId)
					.put("animation", false)
					.put("selectorStaticDelta", selectorDelta)
					.put("profileFullDiagnosticDelta", profileFullDelta)
					.put("profileBadgeStaticDelta", profileBadgeDelta)
					.put(
						"screens",
						JSONArray()
							.put("actual-badge-selector-static.png")
							.put("actual-large-preview-static.png")
							.put("actual-profile-equipped-static.png"),
					)
					.toString(2),
			)
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
			prefs.edit().putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, oldReduceMotion).commit()
		}
	}

	private fun waitForLayout(view: ComposeView) {
		val deadline = SystemClock.elapsedRealtime() + 12_000L
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			if (view.isLaidOut && view.width > 0 && view.height > 0) return
			SystemClock.sleep(100)
		}
		assertTrue("Actual UI smoke view never reached a laid-out state", view.isLaidOut && view.width > 0)
	}

	private fun captureView(view: ComposeView): Bitmap {
		val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
		val location = IntArray(2)
		view.getLocationOnScreen(location)
		val left = location[0].coerceIn(0, screenshot.width - 1)
		val top = location[1].coerceIn(0, screenshot.height - 1)
		val width = view.width.coerceAtMost(screenshot.width - left)
		val height = view.height.coerceAtMost(screenshot.height - top)
		return Bitmap.createBitmap(screenshot, left, top, width, height)
	}

	private fun cropActualProfileBadgeRegion(bitmap: Bitmap, density: Float): Bitmap {
		// StatsScreen production geometry: 20dp outer stats padding, 10dp profile inner padding,
		// centered 140dp avatar/frame box, 34dp badge aligned TopEnd. Keep a 10dp guard band so
		// clipping or layout shift around the badge is visible to the delta gate.
		val outer = 20f * density
		val inner = 10f * density
		val avatar = 140f * density
		val badge = 34f * density
		val guard = 10f * density
		val contentWidth = bitmap.width - (outer + inner) * 2f
		val avatarLeft = outer + inner + (contentWidth - avatar) / 2f
		val badgeLeft = avatarLeft + avatar - badge
		val top = (10f + 8f) * density
		val leftPx = (badgeLeft - guard).toInt().coerceAtLeast(0)
		val topPx = (top - guard).toInt().coerceAtLeast(0)
		val sizePx = (badge + guard * 2f).toInt()
		val width = sizePx.coerceAtMost(bitmap.width - leftPx)
		val height = sizePx.coerceAtMost(bitmap.height - topPx)
		return Bitmap.createBitmap(bitmap, leftPx, topPx, width, height)
	}

	private fun normalizedPixelDelta(a: Bitmap, b: Bitmap): Double {
		val width = minOf(a.width, b.width)
		val height = minOf(a.height, b.height)
		val stepX = maxOf(1, width / 72)
		val stepY = maxOf(1, height / 112)
		var sum = 0.0
		var count = 0
		var y = 0
		while (y < height) {
			var x = 0
			while (x < width) {
				val ca = a.getPixel(x, y)
				val cb = b.getPixel(x, y)
				sum += abs(AndroidColor.red(ca) - AndroidColor.red(cb))
				sum += abs(AndroidColor.green(ca) - AndroidColor.green(cb))
				sum += abs(AndroidColor.blue(ca) - AndroidColor.blue(cb))
				count += 3
				x += stepX
			}
			y += stepY
		}
		return if (count == 0) 0.0 else sum / (count * 255.0)
	}

	private fun writePng(name: String, bitmap: Bitmap) {
		writeToDownloads(name, "image/png") { output ->
			assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
		}
	}

	private fun writeJson(name: String, text: String) {
		writeToDownloads(name, "application/json") { output -> output.write(text.toByteArray()) }
	}

	private fun writeToDownloads(name: String, mimeType: String, write: (OutputStream) -> Unit) {
		val resolver = context.contentResolver
		val relativePath = "Download/miyorare-badge-v2-ui-smoke/"
		resolver.delete(
			MediaStore.Downloads.EXTERNAL_CONTENT_URI,
			MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
			arrayOf(relativePath, name),
		)
		val values = ContentValues().apply {
			put(MediaStore.MediaColumns.DISPLAY_NAME, name)
			put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
			put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
		}
		val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
		resolver.openOutputStream(uri, "w").use { output -> write(checkNotNull(output)) }
	}

	private enum class SmokeMode {
		SELECTOR,
		PROFILE,
	}
}
