package org.koitharu.kotatsu.readerjourney.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.stats.ui.StatsActivity
import java.io.OutputStream
import javax.inject.Inject
import kotlin.math.abs

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExclusiveBadgeBatterySaverTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var settings: AppSettings

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Before
	fun setUp() {
		hiltRule.inject()
		settings.isOnboardingCompleted = true
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false)
			.putBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, false)
			.commit()
	}

	@Test
	fun batterySaverStopsPrismAmbientButKeepsStaticIdentity() {
		val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
		assertTrue("CI must enable Android Battery Saver before this test", powerManager.isPowerSaveMode)

		val activity = instrumentation.startActivitySync(
			Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as StatsActivity
		val spec = checkNotNull(RankThemeVisualRegistry.resolve(RankThemeId.IMPERIAL_AURORA))
		val tokens = RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
		val composeView = ComposeView(activity)

		instrumentation.runOnMainSync {
			composeView.setContent {
				MaterialTheme {
					Box(
						modifier = Modifier.fillMaxSize().background(Color(0xFF050A15)),
						contentAlignment = Alignment.Center,
					) {
						ReferenceRankThemeBadge(
							spec = spec,
							tokens = tokens,
							state = BadgeState.PREVIEWING,
							animate = true,
							qualityMode = BadgeQualityMode.NORMAL,
							modifier = Modifier.size(220.dp),
						)
					}
				}
			}
			activity.setContentView(composeView)
		}

		try {
			waitForLayout(composeView)
			SystemClock.sleep(540)
			val first = captureCenter(composeView)
			SystemClock.sleep(900)
			val second = captureCenter(composeView)
			val delta = normalizedPixelDelta(first, second)
			val luminance = averageLuminance(second)
			assertTrue("Battery Saver must stop prism shimmer/orbit; delta=$delta", delta < 0.001)
			assertTrue("Static prism must remain visibly premium/legible in Battery Saver; luminance=$luminance", luminance > 22.0)
			writePng("battery-saver-prism.png", second)
			writeJson(
				"battery-saver-evidence.json",
				JSONObject()
					.put("themeId", spec.themeId.stableId)
					.put("ambientDelta", delta)
					.put("averageLuminance", luminance)
					.toString(2),
			)
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
		}
	}

	private fun waitForLayout(view: ComposeView) {
		val deadline = SystemClock.elapsedRealtime() + 12_000L
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			if (view.isLaidOut && view.width > 0 && view.height > 0) return
			SystemClock.sleep(120)
		}
		assertTrue("Battery saver badge view never laid out", view.isLaidOut && view.width > 0)
	}

	private fun captureCenter(view: ComposeView): Bitmap {
		val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
		val location = IntArray(2)
		view.getLocationOnScreen(location)
		val cropSize = minOf(view.width, view.height, 560)
		val centerX = location[0] + view.width / 2
		val centerY = location[1] + view.height / 2
		val left = (centerX - cropSize / 2).coerceIn(0, screenshot.width - cropSize)
		val top = (centerY - cropSize / 2).coerceIn(0, screenshot.height - cropSize)
		return Bitmap.createBitmap(screenshot, left, top, cropSize, cropSize)
	}

	private fun normalizedPixelDelta(a: Bitmap, b: Bitmap): Double {
		val step = 8
		var sum = 0.0
		var count = 0
		var y = 0
		while (y < minOf(a.height, b.height)) {
			var x = 0
			while (x < minOf(a.width, b.width)) {
				val ca = a.getPixel(x, y)
				val cb = b.getPixel(x, y)
				sum += abs(AndroidColor.red(ca) - AndroidColor.red(cb))
				sum += abs(AndroidColor.green(ca) - AndroidColor.green(cb))
				sum += abs(AndroidColor.blue(ca) - AndroidColor.blue(cb))
				count += 3
				x += step
			}
			y += step
		}
		return if (count == 0) 0.0 else sum / (count * 255.0)
	}

	private fun averageLuminance(bitmap: Bitmap): Double {
		val step = 8
		var sum = 0.0
		var count = 0
		var y = 0
		while (y < bitmap.height) {
			var x = 0
			while (x < bitmap.width) {
				val c = bitmap.getPixel(x, y)
				sum += 0.299 * AndroidColor.red(c) + 0.587 * AndroidColor.green(c) + 0.114 * AndroidColor.blue(c)
				count++
				x += step
			}
			y += step
		}
		return if (count == 0) 0.0 else sum / count
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
		val relativePath = "Download/miyorare-exclusive-badge-golden/"
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
}
