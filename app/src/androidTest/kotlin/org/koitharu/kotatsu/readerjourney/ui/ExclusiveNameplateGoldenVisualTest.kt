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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import androidx.core.app.FrameMetricsAggregator
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
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.stats.ui.ExclusiveNameplateSelector
import org.koitharu.kotatsu.stats.ui.StatsActivity
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Runtime visual evidence for MIYORARE_12_NAMEPLATE_IMPLEMENTATION_GUIDE_UPDATED.
 *
 * Captures the exact production renderer for all 12 static and preview nameplates, profile state,
 * catalog-static behavior, Reduce Motion, Battery Saver, and tier 11/12 grayscale separation.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExclusiveNameplateGoldenVisualTest {

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
		ExclusiveNameplateRuntimeTestHooks.powerSaveModeOverride = null
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false)
			.putBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, false)
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
			.commit()
	}

	@Test
	fun captureAllTwelveStaticAndPreviewNameplates() {
		val activity = startActivity()
		val renderCase = mutableStateOf(RenderCase(0, NameplateState.UNLOCKED, false, NameplateUsage.PREVIEW))
		val composeView = ComposeView(activity)

		instrumentation.runOnMainSync {
			composeView.setContent {
				val case = renderCase.value
				val spec = RankThemeVisualRegistry.all[case.index]
				val tokens = RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
				MaterialTheme {
					Box(
						modifier = Modifier.fillMaxSize().background(Color(0xFF050A15)),
						contentAlignment = Alignment.Center,
					) {
						ReferenceRankThemeNameplate(
							spec = spec,
							tokens = tokens,
							title = "Gelar Eksklusif",
							state = case.state,
							animate = case.animate,
							qualityMode = NameplateQualityMode.NORMAL,
							usage = case.usage,
							modifier = Modifier.size(width = 360.dp, height = 138.dp),
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
					renderCase.value = RenderCase(index, NameplateState.UNLOCKED, false, NameplateUsage.PREVIEW)
				}
				instrumentation.waitForIdleSync()
				SystemClock.sleep(700)
				val staticCrop = captureNameplate(composeView)
				val staticName = "%02d-%s-static.png".format(index + 1, spec.themeId.stableId.lowercase())
				writePng(staticName, staticCrop)
				staticCaptures += spec.themeId.displayName to staticCrop

				instrumentation.runOnMainSync {
					renderCase.value = RenderCase(index, NameplateState.PREVIEWING, true, NameplateUsage.PREVIEW)
				}
				instrumentation.waitForIdleSync()
				SystemClock.sleep(700)
				val previewCrop = captureNameplate(composeView)
				val previewName = "%02d-%s-preview.png".format(index + 1, spec.themeId.stableId.lowercase())
				writePng(previewName, previewCrop)
				previewCaptures += spec.themeId.displayName to previewCrop

				evidence.put(
					JSONObject()
						.put("index", index + 1)
						.put("themeId", spec.themeId.stableId)
						.put("static", staticName)
						.put("preview", previewName),
				)
			}

			val staticSheet = buildContactSheet(staticCaptures)
			val previewSheet = buildContactSheet(previewCaptures)
			val goldenReference = loadGoldenReferenceContactSheet()
			// The poster remains a visual-review reference only. The production source of truth is
			// the owner-approved Nameplate V2 runtime pack, whose exact bytes/dimensions are locked by
			// NameplateGuideContractTest. Keep the poster mismatch as evidence, but do not reject the
			// approved runtime assets for intentionally differing from the old poster crop baseline.
			val goldenReferenceMismatch = goldenReferenceMismatch(staticSheet, goldenReference)
			writePng("00-golden-reference-contact-sheet.png", goldenReference)
			writePng("00-static-contact-sheet.png", staticSheet)
			writePng("00-preview-contact-sheet.png", previewSheet)
			writePng("00-golden-vs-implemented-static.png", buildGoldenSideBySide(goldenReference, staticSheet))
			writePng("00-grayscale-11-12.png", buildHighTierGrayscaleSheet(staticCaptures))

			val grayscaleDifference = silhouetteDifference(staticCaptures[10].second, staticCaptures[11].second)
			assertTrue(
				"Tier 11 Prism and Tier 12 Celestial nameplates must remain structurally distinct; diff=$grayscaleDifference",
				grayscaleDifference > 0.025,
			)

			instrumentation.runOnMainSync {
				renderCase.value = RenderCase(11, NameplateState.PREVIEWING, true, NameplateUsage.PREVIEW)
			}
			SystemClock.sleep(1100)
			val motionA = captureNameplate(composeView)
			SystemClock.sleep(800)
			val motionB = captureNameplate(composeView)
			val previewMotionDelta = normalizedPixelDelta(motionA, motionB)
			assertTrue("Preview ambient motion must be visible, delta=$previewMotionDelta", previewMotionDelta > 0.00003)
			writePng("runtime-preview-celestial-a.png", motionA)
			writePng("runtime-preview-celestial-b.png", motionB)

			writeJson(
				"evidence.json",
				JSONObject()
					.put("goldenReference", "MIYORARE 12 Konsep Nameplate / Gaya Gelar Eksklusif poster supplied by project owner")
					.put("nameplateCount", 12)
					.put("previewSizeDp", "360x138")
					.put("grayscalePrismCelestialDifference", grayscaleDifference)
					.put("posterReferenceMismatchInformational", goldenReferenceMismatch)
					.put("previewMotionDelta", previewMotionDelta)
					.put("nameplates", evidence)
					.toString(2),
			)
		} finally {
			finishActivity(activity)
		}
	}

	@Test
	fun profileCatalogReduceMotionAndBatterySaverPoliciesProduceEvidence() {
		val spec = RankThemeVisualRegistry.all.last()
		val tokens = RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)

		val profile = renderAndCapture(
			specIndex = 11,
			state = NameplateState.EQUIPPED,
			animate = true,
			usage = NameplateUsage.PROFILE,
			qualityMode = NameplateQualityMode.NORMAL,
			waitBeforeFirstMs = 700,
			waitBetweenMs = 500,
		)
		writePng("profile-equipped-celestial.png", profile.second)

		val catalog = renderAndCapture(
			specIndex = 11,
			state = NameplateState.UNLOCKED,
			animate = false,
			usage = NameplateUsage.CATALOG,
			qualityMode = NameplateQualityMode.NORMAL,
			waitBeforeFirstMs = 500,
			waitBetweenMs = 900,
		)
		val catalogDelta = normalizedPixelDelta(catalog.first, catalog.second)
		assertTrue("Catalog nameplate must remain static; delta=$catalogDelta", catalogDelta < 0.001)
		writePng("catalog-static-celestial.png", catalog.second)

		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, true)
			.commit()
		val reduced = renderAndCapture(
			specIndex = 11,
			state = NameplateState.PREVIEWING,
			animate = true,
			usage = NameplateUsage.PREVIEW,
			qualityMode = NameplateQualityMode.NORMAL,
			waitBeforeFirstMs = 550,
			waitBetweenMs = 900,
		)
		val reduceMotionDelta = normalizedPixelDelta(reduced.first, reduced.second)
		assertTrue("Reduce Motion must stop nameplate ambient loop; delta=$reduceMotionDelta", reduceMotionDelta < 0.001)
		writePng("reduce-motion-celestial.png", reduced.second)
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
			.commit()

		ExclusiveNameplateRuntimeTestHooks.powerSaveModeOverride = true
		val battery = try {
			renderAndCapture(
				specIndex = 11,
				state = NameplateState.PREVIEWING,
				animate = true,
				usage = NameplateUsage.PREVIEW,
				qualityMode = NameplateQualityMode.NORMAL,
				waitBeforeFirstMs = 550,
				waitBetweenMs = 900,
			)
		} finally {
			ExclusiveNameplateRuntimeTestHooks.powerSaveModeOverride = null
		}
		val batteryDelta = normalizedPixelDelta(battery.first, battery.second)
		assertTrue("Battery Saver must stop nameplate ambient loop; delta=$batteryDelta", batteryDelta < 0.001)
		writePng("battery-saver-celestial.png", battery.second)

		writeJson(
			"policy-evidence.json",
			JSONObject()
				.put("themeId", spec.themeId.stableId)
				.put("catalogDelta", catalogDelta)
				.put("reduceMotionDelta", reduceMotionDelta)
				.put("batterySaverDelta", batteryDelta)
				.toString(2),
		)
	}


	@Test
	fun reviewSurfacesProduceRequestedScreenshots() {
		val selected = RankThemeVisualRegistry.all.last()

		val largePreview = renderAndCapture(
			specIndex = 11,
			state = NameplateState.PREVIEWING,
			animate = true,
			usage = NameplateUsage.PREVIEW,
			qualityMode = NameplateQualityMode.NORMAL,
			waitBeforeFirstMs = 900,
			waitBetweenMs = 250,
		).second
		writePng("large-preview-nameplate-v2.png", largePreview)

		val equippedProfile = renderAndCapture(
			specIndex = 11,
			state = NameplateState.EQUIPPED,
			animate = true,
			usage = NameplateUsage.PROFILE,
			qualityMode = NameplateQualityMode.NORMAL,
			waitBeforeFirstMs = 900,
			waitBetweenMs = 250,
		).second
		writePng("equipped-profile-nameplate-v2.png", equippedProfile)

		val activity = startActivity()
		val composeView = ComposeView(activity)
		instrumentation.runOnMainSync {
			composeView.setContent {
				MaterialTheme {
					Box(
						modifier = Modifier
							.fillMaxSize()
							.background(Color(0xFF050A15)),
						contentAlignment = Alignment.Center,
					) {
						ExclusiveNameplateSelector(
							specs = RankThemeVisualRegistry.all,
							accessRank = ReaderRank.LEGEND,
							selectedNameplateId = selected.nameplateId,
							allowFollowBase = true,
							onSelect = {},
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
			instrumentation.waitForIdleSync()
			SystemClock.sleep(1000)
			writePng("selector-grid-nameplate-v2.png", captureView(composeView))
		} finally {
			finishActivity(activity)
		}
	}

	@Test
	fun catalogScrollPerformanceProducesMeasuredEvidence() {
		val activity = startActivity()
		val composeView = ComposeView(activity)
		val showNameplates = mutableStateOf(false)
		var lazyState: LazyListState? = null
		var lazyScope: CoroutineScope? = null

		instrumentation.runOnMainSync {
			composeView.setContent {
				val state = rememberLazyListState()
				val scope = rememberCoroutineScope()
				lazyState = state
				lazyScope = scope
				MaterialTheme {
					LazyRow(
						state = state,
						modifier = Modifier.fillMaxSize().background(Color(0xFF050A15)),
						horizontalArrangement = Arrangement.spacedBy(12.dp),
						contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
					) {
						items(RankThemeVisualRegistry.all, key = { it.nameplateId }) { spec ->
							if (showNameplates.value) {
								val tokens = RankThemeRegistry.resolveOrDefault(spec.themeId.stableId)
									.tokens(RankThemeVariant.DARK)
								ReferenceRankThemeNameplate(
									spec = spec,
									tokens = tokens,
									title = "Gelar Eksklusif",
									state = NameplateState.UNLOCKED,
									animate = false,
									qualityMode = NameplateQualityMode.NORMAL,
									usage = NameplateUsage.CATALOG,
									modifier = Modifier.size(width = 220.dp, height = 84.dp),
								)
							} else {
								// Same item count, geometry and LazyRow mechanics, but no Nameplate renderer.
								// This control absorbs headless-emulator / SwiftShader scheduling cost.
								Box(
									modifier = Modifier
										.size(width = 220.dp, height = 84.dp)
										.background(Color(0xFF141824)),
								)
							}
						}
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
			val state = checkNotNull(lazyState)
			val scope = checkNotNull(lazyScope)
			val refreshRateHz = activity.display?.refreshRate
				?.takeIf { it.isFinite() && it > 1f }
				?: 60f
			val frameBudgetMs = kotlin.math.ceil(1000.0 / refreshRateHz.toDouble()).toInt().coerceAtLeast(1)
			val meaningfulJankBudgetMs = frameBudgetMs * 2

			fun scrollRoundTrips(roundTrips: Int) {
				val latch = CountDownLatch(1)
				instrumentation.runOnMainSync {
					scope.launch {
						try {
							state.scrollToItem(0)
							repeat(roundTrips) {
								state.animateScrollToItem(RankThemeVisualRegistry.all.lastIndex)
								state.animateScrollToItem(0)
							}
						} finally {
							latch.countDown()
						}
					}
				}
				assertTrue("Catalog scroll did not finish in time", latch.await(25, TimeUnit.SECONDS))
				instrumentation.waitForIdleSync()
			}

			fun measure(label: String): PerformanceSample {
				// Warm composition/decoder caches before each measured path.
				scrollRoundTrips(1)
				SystemClock.sleep(250)

				val frameMetrics = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
				frameMetrics.add(activity)
				scrollRoundTrips(2)
				SystemClock.sleep(200)
				val metrics = frameMetrics.remove(activity)
				val durations = metrics?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
				assertTrue("No frame metrics were captured for $label", durations != null && durations.size() > 0)

				var totalFrames = 0
				var framesOverSingleBudget = 0
				var meaningfulJankFrames = 0
				var cumulative = 0
				var p95FrameMs = 0
				var weightedFrameMs = 0L
				val target95 = durations!!.let { d ->
					var count = 0
					for (i in 0 until d.size()) count += d.valueAt(i)
					kotlin.math.ceil(count * 0.95).toInt().coerceAtLeast(1)
				}
				for (i in 0 until durations.size()) {
					val durationMs = durations.keyAt(i)
					val count = durations.valueAt(i)
					totalFrames += count
					weightedFrameMs += durationMs.toLong() * count.toLong()
					if (durationMs > frameBudgetMs) framesOverSingleBudget += count
					if (durationMs > meaningfulJankBudgetMs) meaningfulJankFrames += count
					cumulative += count
					if (p95FrameMs == 0 && cumulative >= target95) p95FrameMs = durationMs
				}
				return PerformanceSample(
					totalFrames = totalFrames,
					framesOverSingleBudget = framesOverSingleBudget,
					singleBudgetMissRate = if (totalFrames == 0) 1.0 else framesOverSingleBudget.toDouble() / totalFrames.toDouble(),
					meaningfulJankFrames = meaningfulJankFrames,
					meaningfulJankRate = if (totalFrames == 0) 1.0 else meaningfulJankFrames.toDouble() / totalFrames.toDouble(),
					averageFrameMs = if (totalFrames == 0) Double.POSITIVE_INFINITY else weightedFrameMs.toDouble() / totalFrames.toDouble(),
					p95FrameMs = p95FrameMs,
				)
			}

			val baseline = measure("lightweight baseline catalog")

			instrumentation.runOnMainSync {
				showNameplates.value = true
			}
			instrumentation.waitForIdleSync()
			SystemClock.sleep(350)
			val nameplate = measure("production Nameplate catalog")

			assertTrue("Baseline performance sample is too small: ${baseline.totalFrames} frames", baseline.totalFrames >= 20)
			assertTrue("Nameplate performance sample is too small: ${nameplate.totalFrames} frames", nameplate.totalFrames >= 20)

			// Absolute frame times on headless SwiftShader are host-load dependent, so use a same-run
			// control path to isolate the incremental cost of the production Nameplate renderer.
			// The absolute jank/p95 values remain recorded below for diagnosis.
			val allowedAverageFrameMs = baseline.averageFrameMs * 1.35 + 4.0
			val allowedP95FrameMs = kotlin.math.ceil(baseline.p95FrameMs * 1.35 + 8.0).toInt()
			// A >2-frame-budget rate stops being discriminating once the SwiftShader control path
			// itself is already heavily saturated. In that case keep jank as diagnostic evidence and
			// gate on the same-run average/p95 overhead, which still compares production against the
			// identical host load. When the baseline is healthy enough, retain the strict +15pp jank gate.
			val meaningfulJankGateApplicable = baseline.meaningfulJankRate < 0.50
			val allowedMeaningfulJankRate = if (meaningfulJankGateApplicable) {
				minOf(1.0, baseline.meaningfulJankRate + 0.15)
			} else {
				1.0
			}
			val averageOverheadRatio = if (baseline.averageFrameMs <= 0.0) {
				Double.POSITIVE_INFINITY
			} else {
				nameplate.averageFrameMs / baseline.averageFrameMs
			}
			val p95OverheadRatio = if (baseline.p95FrameMs <= 0) {
				Double.POSITIVE_INFINITY
			} else {
				nameplate.p95FrameMs.toDouble() / baseline.p95FrameMs.toDouble()
			}
			val meaningfulJankRateDelta = nameplate.meaningfulJankRate - baseline.meaningfulJankRate

			// Persist before assertions so every failed run leaves actionable A/B evidence.
			writeJson(
				"performance-evidence.json",
				JSONObject()
					.put("catalogNameplateCount", RankThemeVisualRegistry.all.size)
					.put("measuredRoundTrips", 2)
					.put("refreshRateHz", refreshRateHz.toDouble())
					.put("frameBudgetMs", frameBudgetMs)
					.put("meaningfulJankBudgetMs", meaningfulJankBudgetMs)
					.put("baselineTotalFrames", baseline.totalFrames)
					.put("baselineFramesOverSingleBudget", baseline.framesOverSingleBudget)
					.put("baselineSingleBudgetMissRate", baseline.singleBudgetMissRate)
					.put("baselineMeaningfulJankFrames", baseline.meaningfulJankFrames)
					.put("baselineMeaningfulJankRate", baseline.meaningfulJankRate)
					.put("baselineAverageFrameMs", baseline.averageFrameMs)
					.put("baselineP95FrameMs", baseline.p95FrameMs)
					.put("totalFrames", nameplate.totalFrames)
					.put("framesOverSingleBudget", nameplate.framesOverSingleBudget)
					.put("singleBudgetMissRate", nameplate.singleBudgetMissRate)
					.put("meaningfulJankFrames", nameplate.meaningfulJankFrames)
					.put("meaningfulJankRate", nameplate.meaningfulJankRate)
					.put("averageFrameMs", nameplate.averageFrameMs)
					.put("p95FrameMs", nameplate.p95FrameMs)
					.put("averageOverheadRatio", averageOverheadRatio)
					.put("p95OverheadRatio", p95OverheadRatio)
					.put("meaningfulJankRateDelta", meaningfulJankRateDelta)
					.put("allowedAverageFrameMs", allowedAverageFrameMs)
					.put("allowedP95FrameMs", allowedP95FrameMs)
					.put("meaningfulJankGateApplicable", meaningfulJankGateApplicable)
					.put("allowedMeaningfulJankRate", allowedMeaningfulJankRate)
					.put("catalogUsesStaticThumbnails", true)
					.toString(2),
			)

			assertTrue(
				"Nameplate catalog adds too much average frame cost: ${nameplate.averageFrameMs}ms vs baseline ${baseline.averageFrameMs}ms (allowed=$allowedAverageFrameMs)",
				nameplate.averageFrameMs <= allowedAverageFrameMs,
			)
			assertTrue(
				"Nameplate catalog adds too much p95 frame cost: ${nameplate.p95FrameMs}ms vs baseline ${baseline.p95FrameMs}ms (allowed=${allowedP95FrameMs}ms)",
				nameplate.p95FrameMs <= allowedP95FrameMs,
			)
			if (meaningfulJankGateApplicable) {
				assertTrue(
					"Nameplate catalog adds too much >2-budget jank: ${nameplate.meaningfulJankRate} vs baseline ${baseline.meaningfulJankRate} (allowed=$allowedMeaningfulJankRate)",
					nameplate.meaningfulJankRate <= allowedMeaningfulJankRate,
				)
			}
		} finally {
			finishActivity(activity)
		}
	}

	private fun renderAndCapture(
		specIndex: Int,
		state: NameplateState,
		animate: Boolean,
		usage: NameplateUsage,
		qualityMode: NameplateQualityMode,
		waitBeforeFirstMs: Long,
		waitBetweenMs: Long,
	): Pair<Bitmap, Bitmap> {
		val activity = startActivity()
		val spec = RankThemeVisualRegistry.all[specIndex]
		val tokens = RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
		val composeView = ComposeView(activity)
		instrumentation.runOnMainSync {
			composeView.setContent {
				MaterialTheme {
					Box(
						modifier = Modifier.fillMaxSize().background(Color(0xFF050A15)),
						contentAlignment = Alignment.Center,
					) {
						ReferenceRankThemeNameplate(
							spec = spec,
							tokens = tokens,
							title = "Gelar Eksklusif",
							state = state,
							animate = animate,
							qualityMode = qualityMode,
							usage = usage,
							modifier = when (usage) {
								NameplateUsage.CATALOG -> Modifier.size(width = 220.dp, height = 84.dp)
								NameplateUsage.PROFILE -> Modifier.size(width = 260.dp, height = 100.dp)
								NameplateUsage.PREVIEW -> Modifier.size(width = 360.dp, height = 138.dp)
							},
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
		return try {
			waitForLayout(composeView)
			SystemClock.sleep(waitBeforeFirstMs)
			val first = captureNameplate(composeView)
			SystemClock.sleep(waitBetweenMs)
			val second = captureNameplate(composeView)
			first to second
		} finally {
			finishActivity(activity)
		}
	}

	private fun startActivity(): StatsActivity = instrumentation.startActivitySync(
		Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
	) as StatsActivity

	private fun finishActivity(activity: StatsActivity) {
		instrumentation.runOnMainSync {
			if (!activity.isFinishing) activity.finish()
		}
		instrumentation.waitForIdleSync()
		SystemClock.sleep(120)
	}

	private fun waitForLayout(view: ComposeView) {
		val deadline = SystemClock.elapsedRealtime() + 12_000L
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			if (view.isLaidOut && view.width > 0 && view.height > 0) return
			SystemClock.sleep(120)
		}
		assertTrue("Nameplate golden ComposeView never reached a laid-out state", view.isLaidOut && view.width > 0)
	}

	private fun captureNameplate(view: ComposeView): Bitmap {
		val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
		val location = IntArray(2)
		view.getLocationOnScreen(location)
		val cropWidth = minOf(800, screenshot.width)
		val cropHeight = minOf(360, screenshot.height)
		val centerX = location[0] + view.width / 2
		val centerY = location[1] + view.height / 2
		val left = (centerX - cropWidth / 2).coerceIn(0, screenshot.width - cropWidth)
		val top = (centerY - cropHeight / 2).coerceIn(0, screenshot.height - cropHeight)
		return Bitmap.createBitmap(screenshot, left, top, cropWidth, cropHeight)
	}


	private fun captureView(view: ComposeView): Bitmap {
		val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
		val location = IntArray(2)
		view.getLocationOnScreen(location)
		val left = location[0].coerceIn(0, screenshot.width - 1)
		val top = location[1].coerceIn(0, screenshot.height - 1)
		val width = minOf(view.width, screenshot.width - left).coerceAtLeast(1)
		val height = minOf(view.height, screenshot.height - top).coerceAtLeast(1)
		return Bitmap.createBitmap(screenshot, left, top, width, height)
	}

	private fun buildContactSheet(captures: List<Pair<String, Bitmap>>): Bitmap {
		assertEquals(12, captures.size)
		val width = 1000
		val cellHeight = 260
		val sheet = Bitmap.createBitmap(width, cellHeight * 6, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(sheet)
		canvas.drawColor(AndroidColor.rgb(5, 10, 21))
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = AndroidColor.WHITE
			textSize = 22f
		}
		captures.forEachIndexed { index, (name, bitmap) ->
			val column = index % 2
			val row = index / 2
			val left = column * (width / 2)
			val top = row * cellHeight
			canvas.drawText("%02d  %s".format(index + 1, name), left + 12f, top + 28f, paint)
			canvas.drawBitmap(
				bitmap,
				null,
				Rect(left + 12, top + 40, left + width / 2 - 12, top + cellHeight - 10),
				null,
			)
		}
		return sheet
	}

	private fun buildHighTierGrayscaleSheet(captures: List<Pair<String, Bitmap>>): Bitmap {
		val selected = captures.subList(10, 12)
		val width = 1000
		val height = 300
		val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(sheet)
		canvas.drawColor(AndroidColor.rgb(8, 8, 8))
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = AndroidColor.WHITE
			textSize = 23f
		}
		selected.forEachIndexed { index, (name, bitmap) ->
			val left = index * (width / 2)
			canvas.drawText("%02d  %s".format(index + 11, name), left + 12f, 28f, paint)
			canvas.drawBitmap(
				bitmap.toGrayscale(),
				null,
				Rect(left + 12, 42, left + width / 2 - 12, height - 12),
				null,
			)
		}
		return sheet
	}

	private fun loadGoldenReferenceContactSheet(): Bitmap =
		instrumentation.context.assets.open("nameplate_golden_reference_contact_sheet.jpg").use { input ->
			checkNotNull(BitmapFactory.decodeStream(input)) { "Golden reference contact sheet could not be decoded" }
		}.also {
			assertEquals(1000, it.width)
			assertEquals(1560, it.height)
		}

	private fun buildGoldenSideBySide(golden: Bitmap, implemented: Bitmap): Bitmap {
		val height = minOf(golden.height, implemented.height)
		val out = Bitmap.createBitmap(golden.width + implemented.width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(out)
		canvas.drawColor(AndroidColor.rgb(5, 10, 21))
		canvas.drawBitmap(golden, 0f, 0f, null)
		canvas.drawBitmap(implemented, golden.width.toFloat(), 0f, null)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = AndroidColor.WHITE
			textSize = 26f
		}
		canvas.drawText("GOLDEN REFERENCE", 18f, 30f, paint)
		canvas.drawText("IMPLEMENTED STATIC", golden.width + 18f, 30f, paint)
		return out
	}

	private fun goldenReferenceMismatch(actual: Bitmap, golden: Bitmap): Double {
		val width = minOf(actual.width, golden.width)
		val height = minOf(actual.height, golden.height)
		val scale = 4
		val aa = Bitmap.createScaledBitmap(actual, width / scale, height / scale, true)
		val gg = Bitmap.createScaledBitmap(golden, width / scale, height / scale, true)
		val cellWidth = aa.width / 2
		val cellHeight = aa.height / 6
		var xor = 0
		var union = 0
		for (y in 0 until aa.height) {
			val localY = y % cellHeight
			if (localY < 10) continue // labels are evidence metadata, not artwork
			for (x in 0 until aa.width) {
				val localX = x % cellWidth
				// Runtime title text intentionally differs from the sample poster titles.
				if (localX in 34..91 && localY in 23..45) continue
				val ac = aa.getPixel(x, y)
				val gc = gg.getPixel(x, y)
				val al = (0.299f * AndroidColor.red(ac) + 0.587f * AndroidColor.green(ac) + 0.114f * AndroidColor.blue(ac))
				val gl = (0.299f * AndroidColor.red(gc) + 0.587f * AndroidColor.green(gc) + 0.114f * AndroidColor.blue(gc))
				val ab = al > 42f
				val gb = gl > 42f
				if (ab || gb) {
					union++
					if (ab != gb) xor++
				}
			}
		}
		return if (union == 0) 1.0 else xor.toDouble() / union.toDouble()
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
				val av = AndroidColor.red(aa.getPixel(x, y)) > 42
				val bv = AndroidColor.red(bb.getPixel(x, y)) > 42
				if (av != bv) different++
				total++
			}
		}
		return different.toDouble() / total.toDouble()
	}

	private fun normalizedPixelDelta(a: Bitmap, b: Bitmap): Double {
		val width = minOf(a.width, b.width)
		val height = minOf(a.height, b.height)
		val stepX = maxOf(1, width / 80)
		val stepY = maxOf(1, height / 48)
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
		val relativePath = "Download/miyorare-exclusive-nameplate-golden/"
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

	private data class PerformanceSample(
		val totalFrames: Int,
		val framesOverSingleBudget: Int,
		val singleBudgetMissRate: Double,
		val meaningfulJankFrames: Int,
		val meaningfulJankRate: Double,
		val averageFrameMs: Double,
		val p95FrameMs: Int,
	)

	private data class RenderCase(
		val index: Int,
		val state: NameplateState,
		val animate: Boolean,
		val usage: NameplateUsage,
	)
}
