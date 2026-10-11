package org.koitharu.kotatsu.core.logs

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight opt-in performance recorder backed by the existing verbose logcat capture. */
@Singleton
class VerbosePerformanceDiagnostics @Inject constructor(
	private val appLogger: AppLogger,
) : Application.ActivityLifecycleCallbacks {

	private val sessionId = UUID.randomUUID().toString().take(8)
	private val traceSequence = AtomicLong(0)
	private val spans = ConcurrentHashMap<Long, Span>()
	private val slowOperations = ArrayDeque<SlowOperation>()
	private val fragmentCallbacks = FragmentCallbacks()
	private var currentScreen: String? = null
	private var transition: Transition? = null
	private var frameMonitor: FrameMonitor? = null

	fun isEnabled(): Boolean = appLogger.isEnabled

	fun event(category: String, name: String, vararg fields: Pair<String, Any?>) {
		if (!isEnabled()) return
		Log.d(TAG, buildMessage("EVENT", category, name, fields))
	}

	fun beginSpan(category: String, name: String, vararg fields: Pair<String, Any?>): Long {
		if (!isEnabled()) return NO_TRACE
		val id = traceSequence.incrementAndGet()
		spans[id] = Span(category, name, SystemClock.elapsedRealtime())
		Log.d(TAG, buildMessage("BEGIN", category, name, arrayOf("trace" to id, *fields)))
		return id
	}

	fun endSpan(traceId: Long, vararg fields: Pair<String, Any?>) {
		if (traceId == NO_TRACE || !isEnabled()) return
		val span = spans.remove(traceId) ?: return
		val duration = SystemClock.elapsedRealtime() - span.startedAt
		Log.d(TAG, buildMessage("END", span.category, span.name, arrayOf("trace" to traceId, "durationMs" to duration, *fields)))
		if (duration >= SLOW_OPERATION_MS) rememberSlow(span.category, span.name, duration)
	}

	fun dataReady(name: String, count: Int? = null) = event("DATA", name, "count" to count)
	fun cache(name: String, tier: String, hit: Boolean, durationMs: Long? = null) =
		event("CACHE", name, "tier" to tier, "hit" to hit, "durationMs" to durationMs)

	fun network(operation: String, durationMs: Long, success: Boolean, source: String? = null) {
		event("NETWORK", operation, "durationMs" to durationMs, "success" to success, "source" to source)
		if (durationMs >= SLOW_NETWORK_MS) rememberSlow("NETWORK", operation, durationMs)
	}

	fun work(name: String, state: String, generation: Any? = null) =
		event("WORK", name, "state" to state, "generation" to generation)

	fun dumpSummary() {
		if (!isEnabled()) return
		val snapshot = synchronized(slowOperations) { slowOperations.toList() }
		Log.d(TAG, "SUMMARY session=$sessionId slowOperations=${snapshot.size}")
		snapshot.sortedByDescending { it.durationMs }.take(MAX_SUMMARY_ENTRIES).forEachIndexed { index, item ->
			Log.d(TAG, "SUMMARY_SLOW rank=${index + 1} category=${safe(item.category)} name=${safe(item.name)} durationMs=${item.durationMs}")
		}
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		event("LIFECYCLE", "activity_created", "screen" to activity.javaClass.simpleName, "restored" to (savedInstanceState != null))
		(activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
	}

	override fun onActivityStarted(activity: Activity) = event("LIFECYCLE", "activity_started", "screen" to activity.javaClass.simpleName)
	override fun onActivityResumed(activity: Activity) {
		event("LIFECYCLE", "activity_resumed", "screen" to activity.javaClass.simpleName)
		startFrames(activity.javaClass.simpleName)
	}
	override fun onActivityPaused(activity: Activity) {
		event("LIFECYCLE", "activity_paused", "screen" to activity.javaClass.simpleName)
		stopFrames()
	}
	override fun onActivityStopped(activity: Activity) = event("LIFECYCLE", "activity_stopped", "screen" to activity.javaClass.simpleName)
	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
	override fun onActivityDestroyed(activity: Activity) {
		event("LIFECYCLE", "activity_destroyed", "screen" to activity.javaClass.simpleName)
		(activity as? FragmentActivity)?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
	}

	private inner class FragmentCallbacks : FragmentManager.FragmentLifecycleCallbacks() {
		override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) =
			event("LIFECYCLE", "fragment_created", "screen" to f.javaClass.simpleName, "restored" to (savedInstanceState != null))

		override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
			val destination = f.javaClass.simpleName
			val previous = currentScreen
			if (previous != null && previous != destination) {
				transition = Transition(traceSequence.incrementAndGet(), previous, destination, SystemClock.elapsedRealtime())
				Log.d(TAG, "TRANSITION_BEGIN session=$sessionId trace=${transition?.id} from=${safe(previous)} to=${safe(destination)}")
			}
			currentScreen = destination
			event("LIFECYCLE", "fragment_resumed", "screen" to destination)
			f.view?.post {
				val active = transition
				if (isEnabled() && active != null && active.to == destination) {
					val duration = SystemClock.elapsedRealtime() - active.startedAt
					Log.d(TAG, "TRANSITION_READY session=$sessionId trace=${active.id} from=${safe(active.from)} to=${safe(active.to)} durationMs=$duration")
					if (duration >= SLOW_TRANSITION_MS) rememberSlow("TRANSITION", "${active.from}>${active.to}", duration)
					transition = null
				}
			}
		}

		override fun onFragmentPaused(fm: FragmentManager, f: Fragment) =
			event("LIFECYCLE", "fragment_paused", "screen" to f.javaClass.simpleName)
	}

	private fun startFrames(screen: String) {
		if (!isEnabled()) return
		stopFrames()
		frameMonitor = FrameMonitor(screen).also { Choreographer.getInstance().postFrameCallback(it) }
	}

	private fun stopFrames() {
		frameMonitor?.let { Choreographer.getInstance().removeFrameCallback(it) }
		frameMonitor?.finish()
		frameMonitor = null
	}

	private inner class FrameMonitor(private val screen: String) : Choreographer.FrameCallback {
		private var lastFrameNanos = 0L
		private var frames = 0
		private var jankyFrames = 0
		private var worstFrameMs = 0L
		override fun doFrame(frameTimeNanos: Long) {
			if (!isEnabled() || frameMonitor !== this) return
			if (lastFrameNanos != 0L) {
				val durationMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L
				frames++
				worstFrameMs = maxOf(worstFrameMs, durationMs)
				if (durationMs >= JANK_FRAME_MS) {
					jankyFrames++
					Log.d(TAG, "JANK session=$sessionId screen=${safe(screen)} frameMs=$durationMs")
				}
			}
			lastFrameNanos = frameTimeNanos
			Choreographer.getInstance().postFrameCallback(this)
		}
		fun finish() {
			if (!isEnabled() || frames == 0) return
			Log.d(TAG, "FRAME_SUMMARY session=$sessionId screen=${safe(screen)} frames=$frames jankyFrames=$jankyFrames worstFrameMs=$worstFrameMs")
		}
	}

	private fun rememberSlow(category: String, name: String, durationMs: Long) = synchronized(slowOperations) {
		if (slowOperations.size >= MAX_SLOW_OPERATIONS) slowOperations.removeFirst()
		slowOperations.addLast(SlowOperation(category, name, durationMs))
	}

	private fun buildMessage(type: String, category: String, name: String, fields: Array<out Pair<String, Any?>>): String = buildString {
		append("PERF session=").append(sessionId).append(" type=").append(type)
		append(" category=").append(safe(category)).append(" name=").append(safe(name))
		fields.forEach { (key, value) -> if (value != null) append(' ').append(safe(key)).append('=').append(safe(value.toString())) }
	}

	private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.:>/-]"), "_").take(MAX_FIELD_LENGTH)
	private data class Span(val category: String, val name: String, val startedAt: Long)
	private data class Transition(val id: Long, val from: String, val to: String, val startedAt: Long)
	private data class SlowOperation(val category: String, val name: String, val durationMs: Long)

	companion object {
		private const val TAG = "MiyorarePerf"
		private const val NO_TRACE = -1L
		private const val JANK_FRAME_MS = 34L
		private const val SLOW_OPERATION_MS = 100L
		private const val SLOW_NETWORK_MS = 500L
		private const val SLOW_TRANSITION_MS = 250L
		private const val MAX_SLOW_OPERATIONS = 100
		private const val MAX_SUMMARY_ENTRIES = 20
		private const val MAX_FIELD_LENGTH = 96
	}
}
