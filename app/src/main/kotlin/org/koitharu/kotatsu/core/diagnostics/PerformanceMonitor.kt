package org.koitharu.kotatsu.core.diagnostics

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** Platform metrics only: no per-frame log, stack sampling, or change to rendering/lifecycle. */
internal class PerformanceMonitor(private val session: DiagnosticsSession) {
	private val thread = HandlerThread("verbose-performance").apply { start() }
	private val worker = Handler(thread.looper)
	private val main = Handler(Looper.getMainLooper())
	private val windows = WeakHashMap<Activity, Window.OnFrameMetricsAvailableListener>()
	@Volatile private var foreground = false
	@Volatile private var stopped = false
	private val pendingPing = AtomicLong(0)
	private val heartbeat = object : Runnable {
		override fun run() {
			if (stopped) return
			if (foreground && pendingPing.compareAndSet(0, SystemClock.uptimeMillis())) {
				val trace = PerformanceDiagnostics.currentTrace
				main.post {
					val sent = pendingPing.getAndSet(0)
					if (!stopped && sent != 0L) session.stall(trace, SystemClock.uptimeMillis() - sent)
				}
			}
			worker.postDelayed(this, 200)
		}
	}

	init { worker.post(heartbeat) }

	fun attach(activity: Activity) {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			main.post { attach(activity) }
			return
		}
		if (stopped || windows.containsKey(activity)) return
		@Suppress("DEPRECATION")
		val rate = activity.windowManager.defaultDisplay.refreshRate.coerceAtLeast(30f)
		val nominalBudget = (1_000_000_000 / rate).toLong()
		val owner = WeakReference(activity)
		val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
			if (!stopped) {
				val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else 0L
				session.frame(owner.get()?.let(PerformanceDiagnostics::ownerTrace), metrics.getMetric(FrameMetrics.TOTAL_DURATION),
					if (deadline > 0) deadline else nominalBudget, dropped)
			}
		}
		runCatching {
			activity.window.addOnFrameMetricsAvailableListener(listener, worker)
			windows[activity] = listener
			foreground = true
		}.onFailure { session.event("monitor", PerformanceDiagnostics.currentTrace, "frame_listener_unavailable") }
	}

	fun detach(activity: Activity) {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			main.post { detach(activity) }
			return
		}
		windows.remove(activity)?.let { activity.window.removeOnFrameMetricsAvailableListener(it) }
		foreground = windows.isNotEmpty()
		session.flushFrames(PerformanceDiagnostics.ownerTrace(activity))
	}

	fun stop() {
		stopped = true
		val sent = pendingPing.getAndSet(0)
		if (sent != 0L) session.stall(PerformanceDiagnostics.currentTrace, SystemClock.uptimeMillis() - sent, "in_progress_at_stop")
		worker.removeCallbacksAndMessages(null)
		thread.quitSafely()
		main.removeCallbacksAndMessages(null)
		main.post {
			windows.forEach { (activity, listener) -> activity.window.removeOnFrameMetricsAvailableListener(listener) }
			windows.clear()
		}
	}
}
