package org.koitharu.kotatsu.stats.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsContentScope
import org.koitharu.kotatsu.stats.domain.StatsMatureMode
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.YearInReview
import java.io.OutputStream
import java.time.LocalDate

/**
 * Wave 2 static proof using the production StatsScreen/Profile Frame path at the real 136dp size.
 *
 * Motion is intentionally disabled. This validates only the static visual foundations for
 * First Light Blue, Emerald Pulse, Arcane Scholar, Violet Halo and Crimson Ember.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProfileFrameWave2ActualUiSmokeTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Test
	fun captureWaveTwoFramesOnActualReaderJourneyProfile() {
		hiltRule.inject()
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val oldReduceMotion = prefs.getBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
		val oldReduceGlow = prefs.getBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false)
		val oldMinimal = prefs.getBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, false)
		check(
			prefs.edit()
				.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, true)
				.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false)
				.putBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, false)
				.commit(),
		) { "Could not configure deterministic Wave 2 static proof" }

		val activity = instrumentation.startActivitySync(
			Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as StatsActivity
		val selectedTheme = mutableStateOf(RankThemeId.FIRST_LIGHT)
		val imageLoader = ImageLoader.Builder(context).build()
		val composeView = activity.findViewById<ComposeView>(R.id.composeView)

		instrumentation.runOnMainSync {
			composeView.setContent {
				MaterialTheme {
					val theme = selectedTheme.value
					val spec = checkNotNull(RankThemeVisualRegistry.resolve(theme))
					val profile = ReaderProfileSettings(
						displayName = "Miyorare",
						cosmetics = ReaderJourneyCosmeticLoadout(
							mode = ReaderJourneyCosmeticMode.CUSTOM,
							selectedThemeId = theme.stableId,
							selectedFrameId = spec.frameId,
						),
					)
					StatsScreen(
						stats = ReadingStats(
							period = StatsPeriod.ALL,
							scope = StatsContentScope.OVERVIEW,
							matureMode = StatsMatureMode.PRIVATE,
							lifetimeXp = 1_000_000L,
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

		val wave = listOf(
			WaveFrame(RankThemeId.FIRST_LIGHT, "frame-02-first-light-blue-runtime.png"),
			WaveFrame(RankThemeId.EMERALD_COMPASS, "frame-04-emerald-pulse-runtime.png"),
			WaveFrame(RankThemeId.VIOLET_VAULT, "frame-05-arcane-scholar-runtime.png"),
			WaveFrame(RankThemeId.ARCANE_SCHOLAR, "frame-06-violet-halo-runtime.png"),
			WaveFrame(RankThemeId.CRIMSON_LIBRARY, "frame-08-crimson-ember-runtime.png"),
		)

		try {
			waitForLayout(composeView)
			val evidence = JSONArray()
			for (item in wave) {
				instrumentation.runOnMainSync { selectedTheme.value = item.theme }
				instrumentation.waitForIdleSync()
				SystemClock.sleep(900)

				val full = captureView(composeView)
				val density = context.resources.displayMetrics.density
				val proofHeight = (380f * density).toInt().coerceAtMost(full.height)
				val proof = Bitmap.createBitmap(full, 0, 0, full.width, proofHeight)
				writePng(item.fileName, proof)

				val spec = checkNotNull(RankThemeVisualRegistry.resolve(item.theme))
				evidence.put(
					JSONObject()
						.put("themeId", item.theme.stableId)
						.put("frameId", spec.frameId)
						.put("screen", item.fileName)
						.put("productionFrameDp", 136)
						.put("containerDp", 140)
						.put("reduceMotion", true),
				)
			}

			writeJson(
				"wave2-runtime-evidence.json",
				JSONObject()
					.put("profileFrameWave", 2)
					.put("staticOnly", true)
					.put("animationTuning", false)
					.put("frameCount", wave.size)
					.put("frames", evidence)
					.toString(2),
			)
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
			prefs.edit()
				.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, oldReduceMotion)
				.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, oldReduceGlow)
				.putBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, oldMinimal)
				.commit()
		}
	}

	private fun waitForLayout(view: ComposeView) {
		val deadline = SystemClock.elapsedRealtime() + 12_000L
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			if (view.isLaidOut && view.width > 0 && view.height > 0) return
			SystemClock.sleep(100)
		}
		assertTrue("Wave 2 actual UI view never reached a laid-out state", view.isLaidOut && view.width > 0)
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
		val relativePath = "Download/miyorare-profile-frame-wave2-ui-smoke/"
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

	private data class WaveFrame(
		val theme: RankThemeId,
		val fileName: String,
	)
}
