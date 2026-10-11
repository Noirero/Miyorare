package org.koitharu.kotatsu.core.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Bounded experiment recorder. No extra database subscription/query, stack sampling or frame loop. */
object DetailsDiagnostic {
	const val EXTRA_SESSION = "details_diagnostic_session"
	val enabled: Boolean get() = BuildConfig.DETAILS_DIAGNOSTIC
	private val timeline = DiagnosticTimeline()
	private val nextSession = AtomicLong()
	private val active = AtomicLong()
	private val sourceIds = ConcurrentHashMap<Long, Int>()
	private val owners = ConcurrentHashMap<Int, Owner>()
	private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)) }
	private var application: Application? = null
	private data class Owner(val ref: WeakReference<Any>, val snapshot: (Any) -> Map<String, Any?>)

	fun initialize(app: Application) {
		if (!enabled) return
		application = app
		app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = lifecycle(activity, "created")
			override fun onActivityStarted(activity: Activity) = lifecycle(activity, "started")
			override fun onActivityResumed(activity: Activity) = lifecycle(activity, "resumed")
			override fun onActivityPaused(activity: Activity) = lifecycle(activity, "paused")
			override fun onActivityStopped(activity: Activity) {
				lifecycle(activity, "stopped")
				val session = activity.intent.getLongExtra(EXTRA_SESSION, 0L)
				if (session != 0L) {
					active.compareAndSet(session, 0L)
					persist()
				}
			}
			override fun onActivityDestroyed(activity: Activity) = lifecycle(activity, "destroyed")
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
		})
	}

	fun navigate(source: Activity?, route: String, mangaId: Long, intent: Intent) {
		if (!enabled) return
		val session = nextSession.incrementAndGet()
		sourceIds.keys.removeAll { it < session - 16 }
		timeline.begin(DiagnosticTimeline.Session(session, mangaId, route, now()))
		active.set(session)
		if (source != null) sourceIds[session] = System.identityHashCode(source)
		intent.putExtra(EXTRA_SESSION, session)
		mark(session, "navigation", mapOf("mangaId" to mangaId, "route" to route,
			"sourceActivity" to (source?.javaClass?.simpleName ?: "unknown")), once = true)
		workload(session, "before")
		memory(session, "before")
	}

	fun create(activity: Activity): Long {
		if (!enabled) return 0L
		if (!timeline.containsSession(activity.intent.getLongExtra(EXTRA_SESSION, 0L))) {
			navigate(null, "external_or_recreated", 0L, activity.intent)
		}
		val session = activity.intent.getLongExtra(EXTRA_SESSION, 0L)
		active.set(session)
		mark(session, "details_onCreate_entry", once = true)
		return session
	}

	fun attachWindow(activity: Activity, session: Long) {
		if (!enabled) return
		activity.window.decorView.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
			private var seen = false
			override fun onDraw() {
				if (!seen) { seen = true; mark(session, "details_view_tree_first_draw", once = true) }
			}
		})
	}

	private fun lifecycle(activity: Activity, stage: String) {
		val own = activity.intent.getLongExtra(EXTRA_SESSION, 0L)
		val session = active.get()
		val fields = mapOf("activity" to activity.javaClass.simpleName)
		if (own != 0L) mark(own, "details_$stage", fields)
		if (session != 0L && sourceIds[session] == System.identityHashCode(activity)) {
			mark(session, "source_$stage", fields)
			if (stage == "resumed" && timeline.has(session, "details_paused")) memory(session, "back")
		}
	}

	fun currentSession(): Long = if (enabled) active.get() else 0L
	fun now(): Long = if (enabled) SystemClock.elapsedRealtimeNanos() else 0L
	fun pending(session: Long, stage: String): Boolean = enabled && session != 0L && !timeline.has(session, stage)

	fun mark(session: Long, stage: String, fields: Map<String, Any?> = emptyMap(), once: Boolean = false): Boolean {
		if (!enabled || session == 0L) return false
		val started = System.nanoTime()
		val result = timeline.record(DiagnosticTimeline.Event(session, stage, now(), started,
			Process.myTid(), Thread.currentThread().name, fields, Debug.threadCpuTimeNanos()), once)
		if (result) {
			timeline.aggregate(session, "markerCount")
			timeline.aggregate(session, "markerMaxElapsedNs", System.nanoTime() - started, maximum = true)
		}
		return result
	}

	fun start(session: Long, stage: String): Long {
		if (!enabled || session == 0L) return 0L
		if (stage == "dao_find") workload(session, "dao_start")
		mark(session, "${stage}_start")
		return now()
	}

	fun end(session: Long, stage: String, start: Long, fields: Map<String, Any?> = emptyMap()) {
		if (!enabled || session == 0L || start == 0L) return
		mark(session, "${stage}_end", fields + ("durationNs" to (now() - start)))
		if (stage == "dao_find") workload(session, "dao_end")
	}

	fun publication(session: Long, count: Int, end: Boolean, mangaId: Long) {
		if (!enabled || count == 0) return
		if (mark(session, if (end) "publication_end" else "publication_begin",
			mapOf("mangaId" to mangaId, "chapterCount" to count), once = true) && end) {
			workload(session, "state_ready")
			memory(session, "state_ready")
		}
	}

	fun firstDraw(session: Long, chapterId: Long) {
		if (mark(session, "first_chapter_draw", mapOf("chapterId" to chapterId), once = true)) {
			workload(session, "first_draw")
			memory(session, "first_draw")
		}
	}

	fun registerListOwner(owner: Any, snapshot: (Any) -> Map<String, Any?>) {
		if (enabled) owners[System.identityHashCode(owner)] = Owner(WeakReference(owner), snapshot)
	}
	fun unregisterListOwner(owner: Any) { owners.remove(System.identityHashCode(owner)) }

	private fun workload(session: Long, point: String) {
		val started = now()
		owners.entries.removeAll { it.value.ref.get() == null }
		val state = owners.mapNotNull { (id, provider) ->
			val owner = provider.ref.get() ?: return@mapNotNull null
			val snapshot = runCatching { provider.snapshot(owner) }
				.getOrElse { mapOf("state" to "unknown", "snapshotError" to it.javaClass.simpleName) }
			mapOf("ownerId" to id, "state" to snapshot)
		}
		mark(session, "workload_$point", FavouritesContentionExperiment.snapshot() +
			mapOf("retainedListState" to state, "snapshotCostNs" to (now() - started)))
	}

	private fun memory(session: Long, point: String) {
		val started = now()
		val runtime = Runtime.getRuntime()
		val total = runtime.totalMemory()
		val free = runtime.freeMemory()
		mark(session, "memory_$point", mapOf("javaTotalBytes" to total, "javaFreeBytes" to free,
			"javaUsedBytes" to total - free, "javaMaxBytes" to runtime.maxMemory(),
			"nativeAllocatedBytes" to Debug.getNativeHeapAllocatedSize(), "snapshotCostNs" to now() - started))
	}

	private fun persist() {
		val app = application ?: return
		scope.launch {
			val text = exportText()
			runCatching { File(app.filesDir, "dao-contention/latest.jsonl").also { it.parentFile?.mkdirs() }.writeText(text) }.onFailure { android.util.Log.w("DETAILS_DIAG", "Cannot persist experiment log", it) }
		}
	}

	/** Existing About export calls this on IO, never synchronously during navigation/rendering. */
	fun exportText(): String {
		val (events, summaries) = timeline.snapshot()
		return buildString {
			append(JSONObject(mapOf("type" to "metadata", "schema" to 2, "tag" to "DETAILS_DIAG",
				"headSha" to BuildConfig.DETAILS_DIAGNOSTIC_SHA, "baselineSha" to BuildConfig.DETAILS_DIAGNOSTIC_BASELINE,
				"packageId" to BuildConfig.APPLICATION_ID, "version" to BuildConfig.VERSION_NAME,
				"pid" to Process.myPid(), "sdk" to Build.VERSION.SDK_INT, "device" to Build.MODEL,
				"experiment" to "pause_background_favourites_list_refresh",
				"droppedEvents" to timeline.droppedEvents, "drawIsPresentation" to false,
				"gcPauseDurations" to "unknown", "queryExecutionVsQueueWait" to "unknown",
				"capture" to "bounded_markers_no_frame_loop_no_stack_sampling_no_extra_query")))
			append('\n')
			for (summary in summaries) { append(JSONObject(summary)); append('\n') }
			for (event in events) {
				append(JSONObject(mapOf("type" to "event", "sessionId" to event.session, "stage" to event.stage,
					"elapsedNs" to event.elapsedNs, "monotonicNs" to event.monotonicNs, "tid" to event.tid,
					"thread" to event.thread, "threadCpuNs" to event.threadCpuNs, "fields" to event.fields)))
				append('\n')
			}
		}
	}
}
