package org.koitharu.kotatsu.readerjourney.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.stats.ui.StatsActivity
import java.io.OutputStream
import javax.inject.Inject
import kotlin.math.abs

/**
 * Production-renderer visual evidence for MIYORARE_12_BADGE_IMPLEMENTATION_GUIDE_UPDATED.
 *
 * The project-owner supplied 12-badge poster is the GOLDEN REFERENCE. This test captures the
 * implemented static foundation and preview renderer for every tier, plus grayscale 09-12.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExclusiveBadgeGoldenVisualTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var settings: AppSettings

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Before
	fun setUp() {
		hiltRule.inject()
		runCatching { WorkManager.getInstance(context) }.getOrElse {
			WorkManager.initialize(context, Configuration.Builder().build())
			WorkManager.getInstance(context)
		}
		settings.isOnboardingCompleted = true
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false)
			.putBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, false)
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
			.commit()
	}

	/** Static-only audit: production Compose renderer at the three production caller sizes. */
	@Test
	fun captureV2StaticThreeContexts() {
		val activity = instrumentation.startActivitySync(
			Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as StatsActivity
		val renderCase = mutableStateOf(0 to 0)
		val composeView = ComposeView(activity)
		val contexts = listOf("large-preview", "selector-grid", "mini-profile")
		val sizes = listOf(148, 44, 34)
		instrumentation.runOnMainSync {
			composeView.setContent {
				val (index, contextIndex) = renderCase.value
				val spec = RankThemeVisualRegistry.all[index]
				val tokens = RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
				MaterialTheme {
					Box(Modifier.fillMaxSize().background(Color(0xFF050A15)), contentAlignment = Alignment.Center) {
						ReferenceRankThemeBadge(
							spec = spec,
							tokens = tokens,
							state = when (contextIndex) {
								0 -> BadgeState.PREVIEWING
								1 -> BadgeState.UNLOCKED
								else -> BadgeState.EQUIPPED
							},
							animate = false,
							qualityMode = if (contextIndex == 0) BadgeQualityMode.NORMAL else BadgeQualityMode.REDUCED,
							useThumbnail = contextIndex != 0,
							profileMode = contextIndex == 2,
							modifier = Modifier.size(sizes[contextIndex].dp),
						)
					}
				}
			}
			activity.addContentView(composeView, ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
			))
		}
		try {
			waitForLayout(composeView)
			assertEquals(12, RankThemeVisualRegistry.all.size)
			val contextsEvidence = JSONArray()
			contexts.forEachIndexed { contextIndex, contextName ->
				val captures = ArrayList<Pair<String, Bitmap>>(12)
				val badges = JSONArray()
				RankThemeVisualRegistry.all.forEachIndexed { index, spec ->
					instrumentation.runOnMainSync { renderCase.value = index to contextIndex }
					instrumentation.waitForIdleSync()
					SystemClock.sleep(350)
					val cropDp = sizes[contextIndex] + 24
					val first = captureStaticRegion(composeView, cropDp)
					SystemClock.sleep(450)
					val bitmap = captureStaticRegion(composeView, cropDp)
					val delta = normalizedPixelDelta(first, bitmap)
					assertTrue("Animation must be OFF: $contextName ${index + 1}, delta=$delta", delta < 0.001)
					val name = "%02d-%s.png".format(index + 1, contextName)
					val screen = "%02d-%s-screen.png".format(index + 1, contextName)
					writePng(name, bitmap)
					writePng(screen, checkNotNull(instrumentation.uiAutomation.takeScreenshot()))
					captures += BADGE_NAMES[index] to bitmap
					val asset = ExclusiveBadgeAssetRegistry.resolve(spec.themeId)
					val resourceId = if (contextIndex == 0) asset.fullRes else asset.thumbnailRes
					val digest = context.resources.openRawResource(resourceId).use {
						java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes())
					}.joinToString("") { "%02x".format(it.toInt() and 0xff) }
					badges.put(JSONObject().put("index", index + 1).put("name", BADGE_NAMES[index])
						.put("themeId", spec.themeId.stableId).put("render", name).put("screen", screen)
						.put("resource", context.resources.getResourceEntryName(resourceId) + ".webp")
						.put("assetSha256", digest).put("staticDelta", delta)
						.put("captureWidthPx", bitmap.width).put("captureHeightPx", bitmap.height))
				}
				val sheetName = "00-$contextName-contact-sheet.png"
				writePng(sheetName, buildNativeScaleStaticSheet(captures, "$contextName / ${sizes[contextIndex]} dp / animation OFF"))
				contextsEvidence.put(JSONObject().put("context", contextName).put("badgeSizeDp", sizes[contextIndex])
					.put("animate", false).put("useThumbnail", contextIndex != 0).put("profileMode", contextIndex == 2)
					.put("contactSheet", sheetName).put("badges", badges))
			}
			writeJson("v2-static-evidence.json", JSONObject().put("badgeCount", 12)
				.put("renderer", "ReferenceRankThemeBadge -> ExclusiveBadge")
				.put("captureMethod", "Android uiAutomation screenshot; centered production component in test harness")
				.put("contactSheetScaling", "none; native screenshot pixels")
				.put("density", context.resources.displayMetrics.density)
				.put("contexts", contextsEvidence).toString(2))
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
		}
	}

	private fun captureStaticRegion(view: ComposeView, sideDp: Int): Bitmap {
		val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
		val location = IntArray(2)
		instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
		val side = (sideDp * context.resources.displayMetrics.density).toInt()
		val left = location[0] + (view.width - side) / 2
		val top = location[1] + (view.height - side) / 2
		assertTrue(left >= 0 && top >= 0 && left + side <= screenshot.width && top + side <= screenshot.height)
		return Bitmap.createBitmap(screenshot, left, top, side, side)
	}

	private fun buildNativeScaleStaticSheet(captures: List<Pair<String, Bitmap>>, heading: String): Bitmap {
		assertEquals(12, captures.size)
		val cellWidth = maxOf(360, captures.first().second.width + 16)
		val cellHeight = captures.first().second.height + 60
		val sheet = Bitmap.createBitmap(cellWidth * 4, cellHeight * 3 + 44, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(sheet)
		canvas.drawColor(AndroidColor.rgb(5, 10, 21))
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; textSize = 19f }
		canvas.drawText(heading, 12f, 28f, paint)
		captures.forEachIndexed { index, (name, bitmap) ->
			val left = (index % 4) * cellWidth
			val top = 44 + (index / 4) * cellHeight
			canvas.drawText("%02d %s".format(index + 1, name), left + 8f, top + 25f, paint)
			// Native-size screenshot copy: no destination rectangle, stretch, or artwork crop.
			canvas.drawBitmap(bitmap, left + (cellWidth - bitmap.width) / 2f, top + 40f, null)
		}
		return sheet
	}

	@Test
	fun captureAllTwelveStaticAndPreviewBadges() {
		val activity = instrumentation.startActivitySync(
			Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as StatsActivity

		val renderCase = mutableStateOf(RenderCase(index = 0, state = BadgeState.UNLOCKED, animate = false))
		val composeView = ComposeView(activity)
		instrumentation.runOnMainSync {
			composeView.setContent {
				val case = renderCase.value
				val spec = RankThemeVisualRegistry.all[case.index]
				val tokens = RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
				MaterialTheme {
					Box(
						modifier = Modifier
							.fillMaxSize()
							.background(Color(0xFF050A15)),
						contentAlignment = Alignment.Center,
					) {
						ReferenceRankThemeBadge(
							spec = spec,
							tokens = tokens,
							state = case.state,
							animate = case.animate,
							qualityMode = BadgeQualityMode.NORMAL,
							useThumbnail = false,
							modifier = Modifier.size(220.dp),
						)
					}
				}
			}
			activity.addContentView(
				composeView,
				ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				),
			)
		}

		try {
			waitForLayout(composeView)
			val staticCaptures = ArrayList<Pair<String, Bitmap>>(12)
			val previewCaptures = ArrayList<Pair<String, Bitmap>>(12)
			val evidence = JSONArray()

			RankThemeVisualRegistry.all.forEachIndexed { index, spec ->
				instrumentation.runOnMainSync {
					renderCase.value = RenderCase(index, BadgeState.UNLOCKED, animate = false)
				}
				instrumentation.waitForIdleSync()
				SystemClock.sleep(180)
				val staticCrop = captureView(composeView)
				val staticName = "%02d-%s-static.png".format(index + 1, spec.themeId.stableId.lowercase())
				writePng(staticName, staticCrop)
				staticCaptures += spec.themeId.displayName to staticCrop

				instrumentation.runOnMainSync {
					renderCase.value = RenderCase(index, BadgeState.PREVIEWING, animate = true)
				}
				instrumentation.waitForIdleSync()
				SystemClock.sleep(460)
				val previewCrop = captureView(composeView)
				val previewName = "%02d-%s-preview.png".format(index + 1, spec.themeId.stableId.lowercase())
				writePng(previewName, previewCrop)
				previewCaptures += spec.themeId.displayName to previewCrop

				evidence.put(
					JSONObject()
						.put("index", index + 1)
						.put("themeId", spec.themeId.stableId)
						.put("badgeStyle", spec.badgeStyle.name)
						.put("static", staticName)
						.put("preview", previewName),
				)
			}

			val goldenReference = instrumentation.context.assets.open("exclusive_badge_golden_reference_sheet.webp").use {
				checkNotNull(BitmapFactory.decodeStream(it))
			}
			writePng("00-golden-reference-contact-sheet.png", goldenReference)
			writePng("00-static-contact-sheet.png", buildContactSheet(staticCaptures))
			writePng("00-preview-contact-sheet.png", buildContactSheet(previewCaptures))
			writePng(
				"00-golden-vs-static-vs-preview.png",
				buildGoldenComparisonSheet(goldenReference, staticCaptures, previewCaptures),
			)
			writePng("00-grayscale-09-12.png", buildHighTierGrayscaleSheet(staticCaptures))

			val grayscaleDifference = silhouetteDifference(staticCaptures[10].second, staticCaptures[11].second)
			assertTrue(
				"Tier 11 Prism and Tier 12 Celestial must remain structurally distinct in grayscale; diff=$grayscaleDifference",
				grayscaleDifference > 0.06,
			)

			writeJson(
				"evidence.json",
				JSONObject()
					.put("goldenReference", "MIYORARE 12 Konsep Badge Eksklusif poster supplied by project owner")
					.put("badgeCount", 12)
					.put("previewSizeDp", 220)
					.put("grayscalePrismCelestialDifference", grayscaleDifference)
					.put("badges", evidence)
					.toString(2),
			)
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
		}
	}

	@Test
	fun reduceMotionDisablesAmbientLoopButKeepsStaticPremiumBadge() {
		val activity = instrumentation.startActivitySync(
			Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as StatsActivity
		val spec = RankThemeVisualRegistry.all.last()
		val tokens = RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
		val composeView = ComposeView(activity)

		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, true)
			.commit()

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
			activity.addContentView(
				composeView,
				ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				),
			)
		}

		try {
			waitForLayout(composeView)
			SystemClock.sleep(520)
			val first = captureView(composeView)
			SystemClock.sleep(900)
			val second = captureView(composeView)
			val delta = normalizedPixelDelta(first, second)
			assertTrue("Reduce Motion must stop idle celestial sweep; delta=$delta", delta < 0.001)
			writePng("reduce-motion-celestial.png", second)
			writeJson(
				"reduce-motion-evidence.json",
				JSONObject().put("themeId", spec.themeId.stableId).put("delta", delta).toString(2),
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
		assertTrue("Badge golden ComposeView never reached a laid-out state", view.isLaidOut && view.width > 0)
	}

	private fun captureView(view: ComposeView): Bitmap {
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

	private fun buildGoldenComparisonSheet(
		golden: Bitmap,
		staticCaptures: List<Pair<String, Bitmap>>,
		previewCaptures: List<Pair<String, Bitmap>>,
	): Bitmap {
		assertEquals(12, staticCaptures.size)
		assertEquals(12, previewCaptures.size)
		val cellWidth = 310
		val rowHeight = 250
		val sheet = Bitmap.createBitmap(cellWidth * 3, rowHeight * 12, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(sheet)
		canvas.drawColor(AndroidColor.rgb(5, 10, 21))
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = AndroidColor.WHITE
			textSize = 20f
		}
		val headings = listOf("GOLDEN REFERENCE", "IMPLEMENTED STATIC", "PREVIEW / EQUIPPED")
		headings.forEachIndexed { column, heading ->
			canvas.drawText(heading, column * cellWidth + 12f, 24f, paint)
		}
		for (index in 0 until 12) {
			val rowTop = index * rowHeight
			val refColumn = index % 4
			val refRow = index / 4
			val refCellWidth = golden.width / 4
			val refCellHeight = golden.height / 3
			val referenceCrop = Bitmap.createBitmap(
				golden,
				refColumn * refCellWidth,
				refRow * refCellHeight,
				refCellWidth,
				refCellHeight,
			)
			val label = BADGE_NAMES[index]
			canvas.drawText("%02d  %s".format(index + 1, label), 12f, rowTop + 48f, paint)
			val contentTop = rowTop + 58
			val contentBottom = rowTop + rowHeight - 8
			val first = Rect(8, contentTop, cellWidth - 8, contentBottom)
			val second = Rect(cellWidth + 8, contentTop, cellWidth * 2 - 8, contentBottom)
			val third = Rect(cellWidth * 2 + 8, contentTop, cellWidth * 3 - 8, contentBottom)
			canvas.drawBitmap(referenceCrop, null, first, null)
			canvas.drawBitmap(staticCaptures[index].second, null, second, null)
			canvas.drawBitmap(previewCaptures[index].second, null, third, null)
		}
		return sheet
	}

	private fun buildContactSheet(captures: List<Pair<String, Bitmap>>): Bitmap {
		assertEquals(12, captures.size)
		val width = 900
		val cellHeight = 280
		val sheet = Bitmap.createBitmap(width, cellHeight * 6, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(sheet)
		canvas.drawColor(AndroidColor.rgb(5, 10, 21))
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = AndroidColor.WHITE
			textSize = 24f
		}
		captures.forEachIndexed { index, (name, bitmap) ->
			val column = index % 2
			val row = index / 2
			val left = column * (width / 2)
			val top = row * cellHeight
			canvas.drawText("%02d  %s".format(index + 1, name), left + 14f, top + 30f, paint)
			val target = Rect(left + 14, top + 42, left + width / 2 - 14, top + cellHeight - 10)
			canvas.drawBitmap(bitmap, null, target, null)
		}
		return sheet
	}

	private fun buildHighTierGrayscaleSheet(captures: List<Pair<String, Bitmap>>): Bitmap {
		val selected = captures.subList(8, 12)
		val width = 900
		val height = 520
		val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(sheet)
		canvas.drawColor(AndroidColor.rgb(8, 8, 8))
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = AndroidColor.WHITE
			textSize = 23f
		}
		selected.forEachIndexed { index, (name, bitmap) ->
			val column = index % 2
			val row = index / 2
			val left = column * (width / 2)
			val top = row * (height / 2)
			canvas.drawText("%02d  %s".format(index + 9, name), left + 12f, top + 28f, paint)
			val gray = bitmap.toGrayscale()
			canvas.drawBitmap(
				gray,
				null,
				Rect(left + 12, top + 40, left + width / 2 - 12, top + height / 2 - 10),
				null,
			)
		}
		return sheet
	}

	private fun Bitmap.toGrayscale(): Bitmap {
		val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		for (y in 0 until height) {
			for (x in 0 until width) {
				val c = getPixel(x, y)
				val r = AndroidColor.red(c)
				val g = AndroidColor.green(c)
				val b = AndroidColor.blue(c)
				val l = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
				out.setPixel(x, y, AndroidColor.rgb(l, l, l))
			}
		}
		return out
	}

	private fun silhouetteDifference(a: Bitmap, b: Bitmap): Double {
		val size = 96
		val aa = Bitmap.createScaledBitmap(a.toGrayscale(), size, size, true)
		val bb = Bitmap.createScaledBitmap(b.toGrayscale(), size, size, true)
		var different = 0
		var total = 0
		for (y in 0 until size) {
			for (x in 0 until size) {
				val av = AndroidColor.red(aa.getPixel(x, y)) > 46
				val bv = AndroidColor.red(bb.getPixel(x, y)) > 46
				if (av != bv) different++
				total++
			}
		}
		return different.toDouble() / total.toDouble()
	}

	private fun normalizedPixelDelta(a: Bitmap, b: Bitmap): Double {
		val width = minOf(a.width, b.width)
		val height = minOf(a.height, b.height)
		val stepX = maxOf(1, width / 64)
		val stepY = maxOf(1, height / 96)
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
		writeToDownloads(name, "application/json") { output ->
			output.write(text.toByteArray())
		}
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

	private data class RenderCase(
		val index: Int,
		val state: BadgeState,
		val animate: Boolean,
	)

	private companion object {
		val BADGE_NAMES = listOf(
			"First Page Silver",
			"First Light Blue",
			"Cyan Orbit",
			"Emerald Pulse",
			"Arcane Scholar",
			"Violet Halo",
			"Rose Nebula",
			"Crimson Ember",
			"Amber Manuscript",
			"Golden Manuscript Deluxe",
			"Eternal Library Prism",
			"Celestial Infinity",
		)
	}
}

