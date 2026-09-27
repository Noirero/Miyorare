package org.koitharu.kotatsu.stats.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
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
 * Review-only Animation V2 evidence.
 *
 * Production composables are used directly. Static badge artwork is never modified here.
 * The workflow records these screens with adb screenrecord and uploads the videos before merge.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BadgeV2AnimationEvidenceTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext
	private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

	@Test
	fun largePreviewSignatureSequence() {
		hiltRule.inject()
		setMotionPrefs(reduceMotion = false, minimal = false)
		val specs = RankThemeVisualRegistry.all
		val selectedId = mutableStateOf(specs.first().badgeId)
		val label = mutableStateOf("01 / 12  ${specs.first().badgeId}")
		val activity = startStatsActivity()
		val composeView = activity.findViewById<ComposeView>(R.id.composeView)

		instrumentation.runOnMainSync {
			composeView.setContent {
				MaterialTheme {
					Column(
						modifier = Modifier
							.fillMaxSize()
							.background(Color(0xFF050A15))
							.padding(horizontal = 18.dp, vertical = 24.dp),
					) {
						Text(
							text = label.value,
							color = Color.White.copy(alpha = 0.82f),
							style = MaterialTheme.typography.labelLarge,
							modifier = Modifier.padding(bottom = 8.dp),
						)
						ExclusiveBadgeSelector(
							specs = specs,
							selectedBadgeId = selectedId.value,
							onSelect = {},
							animatePreview = true,
						)
					}
				}
			}
		}

		try {
			waitForLayout(composeView)
			SystemClock.sleep(2_000)
			specs.forEachIndexed { index, spec ->
				instrumentation.runOnMainSync {
					selectedId.value = spec.badgeId
					label.value = "%02d / 12  %s".format(index + 1, spec.badgeId)
				}
				instrumentation.waitForIdleSync()
				SystemClock.sleep(5_500)
			}
			writeJson(
				"large-preview-sequence.json",
				JSONObject()
					.put("surface", "production ExclusiveBadgeSelector large preview")
					.put("animation", true)
					.put("secondsPerBadge", 5.5)
					.put("selectorGridAnimation", false)
					.put("order", JSONArray(specs.map { it.badgeId }))
					.toString(2),
			)
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
		}
	}

	@Test
	fun equippedProfileIdleEvidence() {
		hiltRule.inject()
		setMotionPrefs(reduceMotion = false, minimal = false)
		val spec = RankThemeVisualRegistry.all.last()
		val activity = startStatsActivity()
		val composeView = activity.findViewById<ComposeView>(R.id.composeView)
		val imageLoader = ImageLoader.Builder(context).build()
		val profile = ReaderProfileSettings(
			displayName = "Miyorare",
			cosmetics = ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.CUSTOM,
				selectedThemeId = spec.themeId.stableId,
				selectedBadgeId = spec.badgeId,
			),
		)

		instrumentation.runOnMainSync {
			composeView.setContent {
				MaterialTheme {
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

		try {
			waitForLayout(composeView)
			SystemClock.sleep(2_000)
			SystemClock.sleep(12_000)
			writeJson(
				"profile-equipped.json",
				JSONObject()
					.put("surface", "production StatsScreen profile")
					.put("badge", spec.badgeId)
					.put("state", "EQUIPPED")
					.put("animation", true)
					.put("profileMode", true)
					.put("intent", "very subtle idle")
					.toString(2),
			)
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
		}
	}

	@Test
	fun reduceMotionEvidence() {
		hiltRule.inject()
		val old = prefs.getBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
		setMotionPrefs(reduceMotion = true, minimal = false)
		try {
			val result = captureSuppressedAmbientEvidence("reduce-motion", requireBatterySaver = false)
			assertTrue("Reduce Motion must suppress ambient badge motion after reveal; delta=${result.delta}", result.delta < 0.003)
		} finally {
			prefs.edit().putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, old).commit()
		}
	}

	@Test
	fun batterySaverEvidence() {
		hiltRule.inject()
		setMotionPrefs(reduceMotion = false, minimal = false)
		val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
		assertTrue("Workflow must enable Android Battery Saver before this evidence test", powerManager.isPowerSaveMode)
		val result = captureSuppressedAmbientEvidence("battery-saver", requireBatterySaver = true)
		assertTrue("Battery Saver must suppress ambient badge motion after reveal; delta=${result.delta}", result.delta < 0.003)
	}

	private fun captureSuppressedAmbientEvidence(prefix: String, requireBatterySaver: Boolean): DeltaResult {
		val spec = RankThemeVisualRegistry.all.last()
		val activity = startStatsActivity()
		val composeView = activity.findViewById<ComposeView>(R.id.composeView)
		instrumentation.runOnMainSync {
			composeView.setContent {
				MaterialTheme {
					Column(
						modifier = Modifier
							.fillMaxSize()
							.background(Color(0xFF050A15))
							.padding(horizontal = 18.dp, vertical = 24.dp),
					) {
						Text(
							text = if (requireBatterySaver) "BATTERY SAVER — 12 / 12" else "REDUCE MOTION — 12 / 12",
							color = Color.White.copy(alpha = 0.82f),
							style = MaterialTheme.typography.labelLarge,
							modifier = Modifier.padding(bottom = 8.dp),
						)
						ExclusiveBadgeSelector(
							specs = RankThemeVisualRegistry.all,
							selectedBadgeId = spec.badgeId,
							onSelect = {},
							animatePreview = true,
						)
					}
				}
			}
		}
		return try {
			waitForLayout(composeView)
			SystemClock.sleep(2_200)
			val a = captureView(composeView)
			SystemClock.sleep(3_500)
			val b = captureView(composeView)
			val delta = normalizedPixelDelta(a, b)
			writePng("${prefix}-frame-a.png", a)
			writePng("${prefix}-frame-b.png", b)
			writeJson(
				"${prefix}-metrics.json",
				JSONObject()
					.put("badge", spec.badgeId)
					.put("animationRequested", true)
					.put("reduceMotion", prefs.getBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false))
					.put("batterySaver", (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode)
					.put("postRevealPixelDelta", delta)
					.toString(2),
			)
			SystemClock.sleep(2_000)
			DeltaResult(delta)
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
		}
	}

	private fun setMotionPrefs(reduceMotion: Boolean, minimal: Boolean) {
		check(
			prefs.edit()
				.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, reduceMotion)
				.putBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, minimal)
				.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false)
				.commit(),
		)
	}

	private fun startStatsActivity(): StatsActivity =
		instrumentation.startActivitySync(
			Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as StatsActivity

	private fun waitForLayout(view: ComposeView) {
		val deadline = SystemClock.elapsedRealtime() + 12_000L
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			if (view.isLaidOut && view.width > 0 && view.height > 0) return
			SystemClock.sleep(100)
		}
		assertTrue("Animation evidence view never reached a laid-out state", view.isLaidOut && view.width > 0)
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
		val relativePath = "Download/miyorare-badge-v2-animation/"
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

	private data class DeltaResult(val delta: Double)
}
