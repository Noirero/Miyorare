package org.koitharu.kotatsu.core.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import androidx.lifecycle.LifecycleOwner
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Diagnostic branch only. No query, cache mutation, lifecycle policy, GC or synchronous disk I/O. */
object DetailsDiagnostic {

	const val EXTRA_SESSION = "details_diagnostic_session"
	val enabled: Boolean get() = BuildConfig.DETAILS_DIAGNOSTIC
	private val timeline = DiagnosticTimeline()
	private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)) }
	private val nextSession = AtomicLong()
	private val active = AtomicLong()
	private val lastStopped = AtomicLong()
	private val mangaSessions = ConcurrentHashMap<Long, Long>()
	private val sourceIds = ConcurrentHashMap<Long, Int>()
	private val owners = ConcurrentHashMap<Int, Owner>()
	private val collectors = ConcurrentHashMap<Int, ConcurrentHashMap<Int, WeakReference<LifecycleOwner>>>()
	private val upstream = ConcurrentHashMap<String, AtomicInteger>()
	private val observerId = AtomicInteger()
	private var application: Application? = null
	private var imageLoader: WeakReference<ImageLoader>? = null
	private var frameHandler: Handler? = null
	private val monitors = ConcurrentHashMap<Long, Choreographer.FrameCallback>()
	private val imageActive = AtomicInteger()
	private val fetchActive = AtomicInteger()
	private val decodeActive = AtomicInteger()
	private val imageRows = ConcurrentHashMap<Long, AtomicInteger>()
	private data class Owner(val ref: WeakReference<Any>, val kind: String, val snapshot: (Any) -> Map<String, Any?>)

	fun initialize(app: Application) {
		if (!enabled) return
		application = app
		val thread = HandlerThread("DetailsDiagnosticFrames").apply { start() }
		frameHandler = Handler(thread.looper)
		app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
			override fun onActivityCreated(activity: Activity, state: Bundle?) = lifecycle(activity, "created")
			override fun onActivityStarted(activity: Activity) = lifecycle(activity, "started")
			override fun onActivityResumed(activity: Activity) {
				lifecycle(activity, "resumed")
				val session = active.get().takeIf { it != 0L } ?: lastStopped.get()
				if (sourceIds[session] == System.identityHashCode(activity) && timeline.has(session, "details_paused")) {
					mark(session, "source_returned", once = true)
					memory(session, "back")
				}
			}
			override fun onActivityPaused(activity: Activity) = lifecycle(activity, "paused")
			override fun onActivityStopped(activity: Activity) {
				lifecycle(activity, "stopped")
				val session = activity.intent.getLongExtra(EXTRA_SESSION, 0L)
				if (session != 0L) {
					stopFrames(session)
					lastStopped.set(session)
					active.compareAndSet(session, 0L)
					persist()
				}
			}
			override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
			override fun onActivityDestroyed(activity: Activity) {
				lifecycle(activity, "destroyed")
				activity.intent.getLongExtra(EXTRA_SESSION, 0L).takeIf { it != 0L }?.let(::stopFrames)
			}
		})
		mark(0, "diagnostic_process", mapOf("sdk" to Build.VERSION.SDK_INT, "device" to Build.MODEL))
	}

	fun navigate(source: Activity?, route: String, mangaId: Long, intent: Intent) {
		if (!enabled) return
		val session = nextSession.incrementAndGet()
		stopFrames(active.get())
		mangaSessions.entries.removeAll { it.value < session - 16 }
		sourceIds.keys.removeAll { it < session - 16 }
		imageRows.keys.removeAll { it < session - 16 }
		timeline.begin(DiagnosticTimeline.Session(session, mangaId, route, SystemClock.elapsedRealtimeNanos()))
		active.set(session)
		if (mangaId != 0L) mangaSessions[mangaId] = session
		if (source != null) sourceIds[session] = System.identityHashCode(source)
		intent.putExtra(EXTRA_SESSION, session)
		mark(session, "navigation", mapOf("sourceActivity" to (source?.javaClass?.simpleName ?: "unknown"),
			"mangaId" to mangaId, "route" to route), once = true)
		memory(session, "before")
		startFrames(session)
	}

	fun create(activity: Activity): Long {
		if (!enabled) return 0L
		if (!timeline.containsSession(activity.intent.getLongExtra(EXTRA_SESSION, 0L))) navigate(null, "external_or_recreated", 0L, activity.intent)
		val session = activity.intent.getLongExtra(EXTRA_SESSION, 0L)
		active.set(session)
		mark(session, "details_onCreate_entry", once = true)
		return session
	}

	fun attachWindow(activity: Activity, session: Long) {
		if (!enabled || session == 0L) return
		var emittedFrames = 0
		val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
			val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
			timeline.aggregate(session, "frames")
			timeline.aggregate(session, "frameCallbacksDropped", dropped.toLong())
			timeline.aggregate(session, "frameTotalMaxNs", total, maximum = true)
			if (total > 32_000_000L) timeline.aggregate(session, "framesOver32ms")
			if (emittedFrames < 48 && (emittedFrames < 4 || total > 32_000_000L)) {
				emittedFrames++
				mark(session, "window_frame", mapOf("totalNs" to total,
					"layoutMeasureNs" to metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
					"drawNs" to metrics.getMetric(FrameMetrics.DRAW_DURATION),
					"syncNs" to metrics.getMetric(FrameMetrics.SYNC_DURATION),
					"commandIssueNs" to metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
					"swapNs" to metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
					"unknownDelayNs" to metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
					"firstDraw" to metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME),
					"intendedVsyncMonotonicNs" to metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
					"vsyncMonotonicNs" to metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
					"callbackDropped" to dropped))
			}
		}
		activity.window.addOnFrameMetricsAvailableListener(listener, frameHandler)
		activity.window.decorView.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
			private var seen = false
			override fun onDraw() {
				if (!seen) { seen = true; mark(session, "details_view_tree_first_draw", once = true) }
			}
		})
	}

	private fun lifecycle(activity: Activity, stage: String) {
		val own = activity.intent.getLongExtra(EXTRA_SESSION, 0L)
		val current = active.get()
		val identity = System.identityHashCode(activity)
		val fields = mapOf("activity" to activity.javaClass.simpleName, "identity" to identity)
		if (own != 0L) mark(own, "details_$stage", fields)
		if (current != 0L && sourceIds[current] == identity) mark(current, "source_$stage", fields)
	}

	fun sessionFor(mangaId: Long): Long = if (enabled) mangaSessions[mangaId] ?: 0L else 0L
	fun currentSession(): Long = if (enabled) active.get() else 0L
	fun now(): Long = if (enabled) SystemClock.elapsedRealtimeNanos() else 0L

	fun mark(session: Long, stage: String, fields: Map<String, Any?> = emptyMap(), once: Boolean = false): Boolean {
		if (!enabled || (session == 0L && stage != "diagnostic_process")) return false
		val start = System.nanoTime()
		val recorded = timeline.record(DiagnosticTimeline.Event(session, stage, SystemClock.elapsedRealtimeNanos(),
			start, Process.myTid(), Thread.currentThread().name, fields, Debug.threadCpuTimeNanos()), once)
		if (recorded) {
			timeline.aggregate(session, "markerCount")
			timeline.aggregate(session, "markerMaxElapsedNs", System.nanoTime() - start, maximum = true)
		}
		return recorded
	}

	fun start(session: Long, stage: String): Long {
		if (!enabled || session == 0L) return 0L
		mark(session, "${stage}_start")
		return now()
	}

	fun end(session: Long, stage: String, start: Long, fields: Map<String, Any?> = emptyMap()) {
		if (!enabled || session == 0L || start == 0L) return
		mark(session, "${stage}_end", fields + ("durationNs" to (now() - start)))
	}

	fun publication(session: Long, count: Int, end: Boolean, mangaId: Long) {
		if (!enabled || session == 0L || count == 0) return
		mangaSessions[mangaId] = session
		val first = mark(session, if (end) "publication_end" else "publication_begin",
			mapOf("mangaId" to mangaId, "chapterCount" to count), once = true)
		if (end && first) {
			memory(session, "state_ready")
			// Exactly one conditional stack snapshot per navigation, never a tracing loop.
			frameHandler?.postDelayed({
				if (active.get() == session && !timeline.has(session, "first_chapter_draw")) {
					val started = now()
					val main = Looper.getMainLooper().thread
					val stack = runCatching { main.stackTrace.take(24).map { "${it.className}.${it.methodName}:${it.lineNumber}" } }
						.getOrElse { listOf("unknown:${it.javaClass.simpleName}") }
					mark(session, "main_stack_pending_draw", mapOf("sampleCostNs" to (now() - started),
						"javaThreadState" to main.state.name, "stack" to stack), once = true)
				}
			}, 150L)
		}
	}

	fun firstDraw(session: Long) {
		if (timeline.has(session, "first_chapter_draw")) return
		if (mark(session, "first_chapter_draw", once = true)) memory(session, "first_draw")
	}

	fun pending(session: Long, stage: String): Boolean = enabled && session != 0L && !timeline.has(session, stage)

	fun memory(session: Long, point: String) {
		if (!enabled || session == 0L) return
		val runtime = Runtime.getRuntime()
		val start = now()
		mark(session, "memory_$point", mapOf("javaTotalBytes" to runtime.totalMemory(),
			"javaFreeBytes" to runtime.freeMemory(), "javaUsedBytes" to (runtime.totalMemory() - runtime.freeMemory()),
			"javaMaxBytes" to runtime.maxMemory(), "nativeAllocatedBytes" to Debug.getNativeHeapAllocatedSize(),
			"imageMemoryCacheBytes" to (imageLoader?.get()?.memoryCache?.size ?: "unknown"),
			"imageMemoryCacheMaxBytes" to (imageLoader?.get()?.memoryCache?.maxSize ?: "unknown"),
			"activeImages" to imageActive.get(), "activeFetches" to fetchActive.get(), "activeDecodes" to decodeActive.get(),
			"artGcCount" to (Debug.getRuntimeStat("art.gc.gc-count") ?: "unknown"),
			"artGcTimeRaw" to (Debug.getRuntimeStat("art.gc.gc-time") ?: "unknown"),
			"artBlockingGcCount" to (Debug.getRuntimeStat("art.gc.blocking-gc-count") ?: "unknown"),
			"artBlockingGcTimeRaw" to (Debug.getRuntimeStat("art.gc.blocking-gc-time") ?: "unknown"),
			"artBytesAllocatedRaw" to (Debug.getRuntimeStat("art.gc.bytes-allocated") ?: "unknown"),
			"snapshotCostNs" to (now() - start)))
		// PSS and owner maps are sampled off-main. Their actual sample times and queue delay are explicit.
		scope.launch {
			val sampledAt = now()
			val state = owners.entries.mapNotNull { (id, entry) ->
				val owner = entry.ref.get() ?: return@mapNotNull null
				mapOf("ownerId" to id, "kind" to entry.kind,
					"state" to runCatching { entry.snapshot(owner) }.getOrElse { mapOf("snapshot" to "unknown", "errorType" to it.javaClass.simpleName) })
			}
			val info = Debug.MemoryInfo()
			val pssStart = now()
			Debug.getMemoryInfo(info)
			mark(session, "ownership_$point", mapOf("requestedElapsedNs" to start, "sampleElapsedNs" to sampledAt,
				"queueDelayNs" to (sampledAt - start), "pssSamplingNs" to (now() - pssStart),
				"totalPssKb" to info.totalPss, "nativePssKb" to info.nativePss, "dalvikPssKb" to info.dalvikPss,
				"owners" to state, "upstream" to upstream.mapValues { it.value.get() },
				"collectors" to collectors.mapValues { (_, subscriptions) -> subscriptions.values.mapNotNull {
					it.get()?.lifecycle?.currentState?.name
				} }))
		}
	}

	fun registerOwner(owner: Any, kind: String, snapshot: (Any) -> Map<String, Any?>) {
		if (enabled) {
			owners.entries.removeAll { it.value.ref.get() == null }
			owners[System.identityHashCode(owner)] = Owner(WeakReference(owner), kind, snapshot)
		}
	}

	fun collectorStart(flow: Any, owner: LifecycleOwner): Int {
		if (!enabled) return 0
		val token = observerId.incrementAndGet()
		collectors.getOrPut(System.identityHashCode(flow)) { ConcurrentHashMap() }[token] = WeakReference(owner)
		return token
	}

	fun collectorEnd(flow: Any, token: Int) {
		if (!enabled) return
		val key = System.identityHashCode(flow)
		collectors[key]?.let { subscriptions ->
			subscriptions.remove(token)
			if (subscriptions.isEmpty()) collectors.remove(key, subscriptions)
		}
	}
	fun collectorCount(flow: Any): Int = collectors[System.identityHashCode(flow)]?.size ?: 0

	fun <T> observePipeline(flow: Flow<T>, owner: Any, name: String): Flow<T> {
		if (!enabled) return flow
		val key = "${System.identityHashCode(owner)}:$name"
		val counter = upstream.getOrPut(key) { AtomicInteger() }
		return flow.onStart { counter.incrementAndGet() }.onCompletion { counter.decrementAndGet() }
			.onEach { timeline.aggregate(active.get(), "upstreamEmissions:$name") }
	}

	fun registerImageLoader(loader: ImageLoader) { if (enabled) imageLoader = WeakReference(loader) }
	fun imageDelta(kind: String, delta: Int) {
		when (kind) { "request" -> imageActive; "fetch" -> fetchActive; else -> decodeActive }.addAndGet(delta)
		timeline.aggregate(active.get(), "$kind:${if (delta > 0) "start" else "end"}")
	}

	fun imageEvent(stage: String, requestSession: Long, role: String, fields: Map<String, Any?> = emptyMap()) {
		val session = active.get()
		if (session == 0L) return
		timeline.aggregate(session, "image:$role:$stage")
		if (imageRows.getOrPut(session) { AtomicInteger() }.incrementAndGet() <= 64) {
			mark(session, "image_$stage", fields + mapOf("requestSession" to requestSession, "role" to role))
		}
	}

	private fun startFrames(session: Long) {
		if (Looper.myLooper() != Looper.getMainLooper()) return
		val choreographer = Choreographer.getInstance()
		val begin = now()
		var previous = 0L
		var slowRows = 0
		val callback = object : Choreographer.FrameCallback {
			override fun doFrame(frameTimeNanos: Long) {
				if (!monitors.containsKey(session)) return
				if (previous == 0L) mark(session, "main_first_frame_callback", mapOf("frameMonotonicNs" to frameTimeNanos), once = true)
				if (previous != 0L) {
					val gap = frameTimeNanos - previous
					timeline.aggregate(session, "choreographerGapMaxNs", gap, maximum = true)
					if (gap > 32_000_000L && slowRows++ < 16) mark(session, "main_frame_gap", mapOf("gapNs" to gap))
				}
				previous = frameTimeNanos
				if (now() - begin < 30_000_000_000L) choreographer.postFrameCallback(this) else monitors.remove(session)
			}
		}
		monitors[session] = callback
		choreographer.postFrameCallback(callback)
	}

	private fun stopFrames(session: Long) {
		monitors.remove(session)?.let { if (Looper.myLooper() == Looper.getMainLooper()) Choreographer.getInstance().removeFrameCallback(it) }
	}

	fun persist() {
		if (!enabled) return
		scope.launch {
			val app = application ?: return@launch
			runCatching { File(app.filesDir, "details-diagnostic").apply { mkdirs() }.resolve("latest.jsonl").writeText(exportText()) }
		}
	}

	/** Call only from an IO coroutine, outside the navigation/render path. */
	fun exportText(): String {
		val (events, summaries) = timeline.snapshot()
		return buildString {
			append(JSONObject(mapOf("type" to "metadata", "schema" to 1, "tag" to "DETAILS_DIAG",
				"headSha" to BuildConfig.DETAILS_DIAGNOSTIC_SHA, "baselineSha" to BuildConfig.DETAILS_DIAGNOSTIC_BASELINE,
				"packageId" to BuildConfig.APPLICATION_ID, "version" to BuildConfig.VERSION_NAME,
				"pid" to Process.myPid(), "sdk" to Build.VERSION.SDK_INT, "device" to Build.MODEL,
				"droppedEvents" to timeline.droppedEvents, "drawIsPresentation" to false,
				"gcPauseDurations" to "unknown", "stackSampling" to "at_most_one_conditional_sample_after_state_ready_150ms",
				"capture" to "bounded_in_process_no_forced_gc_no_query")))
			append('\n')
			for (summary in summaries) { append(JSONObject(summary)); append('\n') }
			for (event in events) {
				append(JSONObject(mapOf("type" to "event", "sessionId" to event.session, "stage" to event.stage,
					"elapsedNs" to event.elapsedNs, "monotonicNs" to event.monotonicNs,
					"tid" to event.tid, "thread" to event.thread, "threadCpuNs" to event.threadCpuNs, "fields" to event.fields)))
				append('\n')
			}
		}
	}
}
