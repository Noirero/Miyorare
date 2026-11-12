package org.koitharu.kotatsu.history.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.history.ui.util.ReadingProgressView
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.adapter.MangaDetailsClickListener
import org.koitharu.kotatsu.list.ui.adapter.listHeaderAD
import org.koitharu.kotatsu.list.ui.adapter.mangaGridItemAD
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.roundToInt

/**
 * Diagnostic-only in-UI scrolling workload, not a production performance gate.
 *
 * Eight Today–7-days headers, 240 manga cards, 80 unique local JPEG covers,
 * two item geometries, real production grid/header delegates and RecyclerView recycling.
 * Reports numbers as evidence: no hardcoded jank threshold or fake two-second stall.
 *
 * A/B still changes the legacy indicator/progress path as well as the overlay.
 * It therefore cannot attribute an improvement solely to overlay onMeasure().
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HistoryScrollBenchmarkTest {

	@get:Rule val hiltRule = HiltAndroidRule(this)
	@Inject lateinit var imageLoaderProvider: Provider<coil3.ImageLoader>

	@Before fun inject() = hiltRule.inject()

	@Test fun scrollTodayThroughSevenDaysWithCoverABBA() {
		assertTrue(BuildConfig.DEBUG)
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val context = instrumentation.targetContext
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val previousStyle = prefs.getString(MiyorareAppearance.KEY_DESIGN_STYLE, null)
		val coverFiles = makeCovers(context.cacheDir)
		try {
			prefs.edit().putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name).commit()
			val loader = imageLoaderProvider.get()
			// Decode deterministic local files before sampling, so A/B sees a similar warm-cache pool.
			val decoded = runBlocking {
				coverFiles.count { file ->
					loader.execute(ImageRequest.Builder(context).data(Uri.fromFile(file)).build()) is SuccessResult
				}
			}
			assertEquals("All offline covers must be decodable, or image conditions differ", coverFiles.size, decoded)
			Log.i(TAG, "setup groups=8 cards=240 unique_covers=${coverFiles.size} predecoded=$decoded source=local_file network=none")

			ActivityScenario.launch(
				org.koitharu.kotatsu.scrobbling.mangaupdates.ui.MangaUpdatesAuthActivity::class.java,
			).use { scenario ->
				lateinit var probe: FrameProbe
				scenario.onActivity { activity -> probe = FrameProbe(activity.window, activity.windowManager.defaultDisplay.refreshRate) }
				try {
					for ((mode, columns, hideTitle) in listOf(
						Triple("grid", 3, false),
						Triple("cover", 2, true),
					)) {
						val rows = makeRows(coverFiles, hideTitle)
						var bypass = false
						lateinit var recycler: RecyclerView
						lateinit var adapter: ListDelegationAdapter<List<ListModel>>
						scenario.onActivity { activity ->
							val resolver = object : ItemSizeResolver {
								override val cellWidth = (activity.resources.displayMetrics.widthPixels / columns)
								override fun attachToView(view: View, textView: TextView?, progressView: ReadingProgressView?) = Unit
							}
							val click = object : MangaDetailsClickListener {
								override fun onReadClick(manga: Manga, view: View) = Unit
								override fun onTagClick(manga: Manga, tag: MangaTag, view: View) = Unit
								override fun onItemClick(item: MangaListModel, view: View) = Unit
							}
							adapter = ListDelegationAdapter<List<ListModel>>(
								mangaGridItemAD(resolver, click, coverContext = ModernCoverContext.BROWSING,
									debugHistoryOverlayBypass = { bypass }),
								listHeaderAD(null, rowClickable = true),
							)
							adapter.items = rows
							recycler = RecyclerView(activity).apply {
								layoutParams = android.view.ViewGroup.LayoutParams(
									android.view.ViewGroup.LayoutParams.MATCH_PARENT,
									android.view.ViewGroup.LayoutParams.MATCH_PARENT,
								)
								itemAnimator = null
								setHasFixedSize(true)
								layoutManager = GridLayoutManager(activity, columns).apply {
									spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
										override fun getSpanSize(position: Int) =
											if (rows.getOrNull(position) is ListHeader) columns else 1
									}
								}
								this.adapter = adapter
								addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
									override fun onChildViewAttachedToWindow(view: View) { probe.recordAttachment() }
									override fun onChildViewDetachedFromWindow(view: View) = Unit
								})
							}
							activity.findViewById<android.view.ViewGroup>(android.R.id.content).apply {
								removeAllViews()
								addView(recycler)
							}
						}
						instrumentation.waitForIdleSync()
						// Counterbalance cache/order effects: A1 -> B1 -> B2 -> A2, both item sizes.
						for ((label, off) in listOf("A1" to false, "B1" to true, "B2" to true, "A2" to false)) {
							scenario.onActivity {
								bypass = off
								adapter.notifyDataSetChanged()
								recycler.scrollToPosition(0)
							}
							instrumentation.waitForIdleSync()
							Thread.sleep(250) // Exclude immediate layout/rebind after variant toggle.
							scenario.onActivity {
								probe.begin()
								recycler.smoothScrollBy(0, 8500, LinearInterpolator(), 1700)
							}
							Thread.sleep(1950)
							scenario.onActivity { recycler.smoothScrollBy(0, -8500, LinearInterpolator(), 1700) }
							Thread.sleep(1950)
							instrumentation.waitForIdleSync()
							scenario.onActivity { Log.i(TAG, "mode=$mode variant=$label " + probe.finish()) }
						}
					}
				} finally {
					scenario.onActivity { probe.close() }
				}
			}
		} finally {
			prefs.edit().apply {
				if (previousStyle == null) remove(MiyorareAppearance.KEY_DESIGN_STYLE)
				else putString(MiyorareAppearance.KEY_DESIGN_STYLE, previousStyle)
			}.commit()
			coverFiles.forEach { it.delete() }
		}
	}

	private fun makeRows(covers: List<File>, hideTitle: Boolean): List<ListModel> = buildList {
		var index = 0
		for (day in 0..7) {
			add(ListHeader(if (day == 0) "Today" else "$day days ago", itemKey = "history-day-$day"))
			repeat(30) {
				val id = 950_000L + index
				val manga = SampleData.manga.copy(
					id = id,
					title = "History benchmark manga $index",
					source = UnknownMangaSource,
					coverUrl = Uri.fromFile(covers[index % covers.size]).toString(),
				)
				add(MangaGridModel(
					manga = manga, override = null, counter = index % 17,
					progress = if (index % 4 == 0) ReadingProgress(0.5f, 20, ProgressIndicatorMode.PERCENT_READ) else null,
					isFavorite = index % 3 == 0, isSaved = index % 4 == 0,
					isPinned = index % 13 == 0, isLocalSource = index % 8 == 0,
					unreadCount = if (index % 5 == 0) 13 else null,
					isTitleOverCover = true, isTitleHidden = hideTitle,
					showContinueReading = index % 6 == 0,
					languageLabel = if (index % 7 == 0) "EN" else null,
				))
				index++
			}
		}
	}

	private fun makeCovers(dir: File): List<File> {
		val base = File(dir, "history-scroll-bench").apply { mkdirs() }
		return (0 until 80).map { i ->
			File(base, "cover-${i.toString().padStart(3, '0')}.jpg").also { file ->
				val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
				try {
					val canvas = Canvas(bitmap)
					val paint = Paint().apply { isAntiAlias = true }
					canvas.drawColor(Color.rgb((i * 37) % 200 + 35, (i * 71) % 200 + 35, (i * 19) % 200 + 35))
					for (stripe in 0 until 10) {
						paint.color = Color.argb(200, (i * 13 + stripe * 17) % 255,
							(i * 43 + stripe * 21) % 255, (i * 61 + stripe * 7) % 255)
						canvas.drawRect(0f, stripe * 48f, 320f, stripe * 48f + 20, paint)
					}
					file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
				} finally { bitmap.recycle() }
			}
		}
	}

	private class FrameProbe(private val window: Window, refreshRate: Float) {
		private val frameBudget = (1_000_000_000.0 / refreshRate.coerceAtLeast(30f)).toLong()
		private val thread = HandlerThread("history-frame-probe").apply { start() }
		private val worker = Handler(thread.looper)
		private val main = Handler(Looper.getMainLooper())
		private val active = AtomicReference<Phase?>(null)
		private val pending = AtomicLong(0L)
		@Volatile private var closed = false

		private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
			active.get()?.addFrame(
				metrics.getMetric(FrameMetrics.TOTAL_DURATION),
				metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
				metrics.getMetric(FrameMetrics.DRAW_DURATION),
				if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else frameBudget,
				dropped,
			)
		}
		private val heartbeat = object : Runnable {
			override fun run() {
				if (closed) return
				val phase = active.get()
				if (phase != null && pending.compareAndSet(0L, SystemClock.uptimeMillis())) {
					main.post {
						val sent = pending.getAndSet(0L)
						if (sent != 0L && active.get() === phase) phase.addHeartbeat(SystemClock.uptimeMillis() - sent)
					}
				}
				worker.postDelayed(this, 100)
			}
		}

		init {
			window.addOnFrameMetricsAvailableListener(listener, worker)
			worker.post(heartbeat)
		}

		fun recordAttachment() { active.get()?.attachments?.incrementAndGet() }
		fun begin() { check(active.compareAndSet(null, Phase(frameBudget))) }
		fun finish(): String {
			val phase = requireNotNull(active.getAndSet(null))
			val result = phase.report()
			check(phase.sampleCount() >= 20 && phase.attachments.get() >= 10) {
				"Invalid scroll measurement: $result"
			}
			return result
		}
		fun close() {
			closed = true
			active.set(null)
			window.removeOnFrameMetricsAvailableListener(listener)
			worker.removeCallbacksAndMessages(null)
			thread.quitSafely()
		}

		private class Phase(private val defaultBudget: Long) {
			private val frames = Collections.synchronizedList(mutableListOf<Long>())
			private val layout = Collections.synchronizedList(mutableListOf<Long>())
			private val draw = Collections.synchronizedList(mutableListOf<Long>())
			val attachments = AtomicInteger()
			private val janky = AtomicInteger()
			private val dropped = AtomicInteger()
			private val stalls = AtomicInteger()
			private val maxHeartbeat = AtomicLong()

			fun addFrame(total: Long, layoutNs: Long, drawNs: Long, deadlineNs: Long, droppedCount: Int) {
				if (total <= 0L) return
				frames.add(total)
				layout.add(layoutNs.coerceAtLeast(0L))
				draw.add(drawNs.coerceAtLeast(0L))
				if (total > (if (deadlineNs > 0L) deadlineNs else defaultBudget)) janky.incrementAndGet()
				dropped.addAndGet(droppedCount.coerceAtLeast(0))
			}
			fun addHeartbeat(delayMs: Long) {
				if (delayMs >= 250) stalls.incrementAndGet()
				maxHeartbeat.accumulateAndGet(delayMs) { prior, next -> maxOf(prior, next) }
			}
			private fun percentile(values: List<Long>, p: Double): Long {
				if (values.isEmpty()) return 0L
				val sorted = values.sorted()
				return sorted[((sorted.size - 1) * p).toInt()]
			}
			fun sampleCount() = frames.size
			fun report(): String {
				val a = synchronized(frames) { frames.toList() }
				val b = synchronized(layout) { layout.toList() }
				val c = synchronized(draw) { draw.toList() }
				fun ms(ns: Long) = "%.2f".format(java.util.Locale.US, ns / 1_000_000.0)
				val percent = if (a.isNotEmpty()) 100.0 * janky.get() / a.size else 0.0
				return "frames=${a.size} janky=${janky.get()} jank_pct=${"%.1f".format(java.util.Locale.US, percent)} " +
					"p95_ms=${ms(percentile(a, .95))} p99_ms=${ms(percentile(a, .99))} max_ms=${ms(a.maxOrNull() ?: 0L)} " +
					"layout_p95_ms=${ms(percentile(b, .95))} draw_p95_ms=${ms(percentile(c, .95))} " +
					"heartbeat_stalls_250ms=${stalls.get()} heartbeat_max_ms=${maxHeartbeat.get()} " +
					"attached=${attachments.get()} dropped=${dropped.get()}"
			}
		}
	}

	private companion object { const val TAG = "HistoryScrollBench" }
}
