package org.koitharu.kotatsu.core.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koitharu.kotatsu.BuildConfig
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap

/** The disabled fast path is one volatile read, with no clocks, jobs or listeners. */
internal object PerformanceDiagnostics {
	const val TRACE_EXTRA = "performance_trace"
	@Volatile private var session: DiagnosticsSession? = null
	@Volatile var currentTrace: DiagnosticsSession.Trace? = null
		private set
	private val owners = WeakHashMap<Any, DiagnosticsSession.Trace>()
	@Volatile private var monitor: PerformanceMonitor? = null
	var lifecycle: PerformanceLifecycle? = null
	val enabled: Boolean get() = session != null

	class Operation internal constructor(internal val session: DiagnosticsSession, internal val span: DiagnosticsSession.Span)

	@Synchronized fun start(context: Context) {
		if (session != null) return
		val value = DiagnosticsSession(UUID.randomUUID().toString().take(12), SystemClock::elapsedRealtimeNanos) {
			Log.i("PerformanceDiagnostics", it)
		}
		session = value
		val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
		value.event("session_start", null,
			"version=${BuildConfig.VERSION_NAME} build=${BuildConfig.VERSION_CODE} type=${BuildConfig.BUILD_TYPE} " +
				"api=${Build.VERSION.SDK_INT} android=${Build.VERSION.RELEASE} abi=${Build.SUPPORTED_ABIS.joinToString(",")} " +
				"cores=${Runtime.getRuntime().availableProcessors()} memory_class_mb=${am.memoryClass} " +
				"low_ram=${am.isLowRamDevice} pid=${android.os.Process.myPid()}")
		monitor = PerformanceMonitor(value)
		lifecycle?.attachCurrent()
	}

	@Synchronized fun stop(): String {
		val value = session ?: return ""
		session = null
		monitor?.stop()
		monitor = null
		currentTrace = null
		synchronized(owners) { owners.clear() }
		return value.stop()
	}

	fun navigation(from: String, to: String, owner: Any? = null): DiagnosticsSession.Trace? {
		val value = session ?: return null
		value.flushFrames(currentTrace)
		val trace = value.navigation(from, to)
		currentTrace = trace
		if (owner != null) bind(owner, trace)
		return trace
	}

	fun activate(trace: DiagnosticsSession.Trace?) {
		if (trace != null && session?.findTrace(trace.id) === trace) currentTrace = trace
	}

	fun trace(id: String?): DiagnosticsSession.Trace? = session?.findTrace(id)

	fun bind(owner: Any, trace: DiagnosticsSession.Trace?) {
		if (session == null || trace == null) return
		synchronized(owners) { owners[owner] = trace }
	}

	fun ownerTrace(owner: Any): DiagnosticsSession.Trace? {
		if (session == null) return null
		return synchronized(owners) { owners[owner] } ?: if (owner is Fragment) {
			owner.parentFragment?.let(::ownerTrace) ?: owner.activity?.let(::ownerTrace)
		} else null
	}

	fun lifecycle(owner: Any, stage: String) {
		val value = session ?: return
		value.event("lifecycle", ownerTrace(owner), "screen=${owner.javaClass.simpleName} stage=$stage")
		if (stage == "destroyed") synchronized(owners) { owners.remove(owner) }
	}

	fun milestone(trace: DiagnosticsSession.Trace?, name: String) = session?.milestone(trace, name)
	fun milestone(owner: Any, name: String) = milestone(ownerTrace(owner), name)

	/** Pre-draw is a rendering proxy, not proof that pixels were displayed by the compositor. */
	fun drawn(owner: Any, view: View, meaningful: Boolean = false) {
		val value = session ?: return
		val trace = ownerTrace(owner) ?: return
		val name = if (meaningful) "first_meaningful_content" else "first_draw"
		if (!value.reserveDraw(trace, name)) return
		val listener = DrawListener(owner, view, value, trace, name)
		view.viewTreeObserver.addOnPreDrawListener(listener)
		view.addOnAttachStateChangeListener(listener)
	}

	private class DrawListener(
		owner: Any,
		private val view: View,
		private val value: DiagnosticsSession,
		private val trace: DiagnosticsSession.Trace,
		private val name: String,
	) : android.view.ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
		private val owner = WeakReference(owner)
		private val observer = view.viewTreeObserver
		private fun remove() {
			if (observer.isAlive) observer.removeOnPreDrawListener(this)
			else view.viewTreeObserver.removeOnPreDrawListener(this)
			view.removeOnAttachStateChangeListener(this)
		}
		override fun onPreDraw(): Boolean {
			remove()
			val liveOwner = owner.get()
			if (session === value && liveOwner != null &&
				(liveOwner !is Fragment || !liveOwner.isHidden) &&
				ownerTrace(liveOwner) === trace && view.isShown) value.milestone(trace, name)
			else value.releaseDraw(trace, name)
			return true
		}
		override fun onViewDetachedFromWindow(v: View) {
			remove()
			value.releaseDraw(trace, name)
		}
		override fun onViewAttachedToWindow(v: View) = Unit
	}

	fun begin(kind: String, name: String, trace: DiagnosticsSession.Trace? = currentTrace): Operation? {
		val value = session ?: return null
		val span = value.begin(kind, name, trace) ?: return null
		return Operation(value, span)
	}

	fun end(operation: Operation?, outcome: String = "complete", detail: String = "") {
		operation ?: return
		operation.session.end(operation.span, outcome, detail)
	}

	inline fun <T> measure(kind: String, name: String, trace: DiagnosticsSession.Trace? = currentTrace, block: () -> T): T {
		val operation = begin(kind, name, trace)
		try {
			val result = block()
			end(operation)
			return result
		} catch (error: Throwable) {
			end(operation, if (error is CancellationException) "cancel" else "failure")
			throw error
		}
	}

	/** Subscription-to-first-result includes Room dispatch/mapping, never claims raw query duration. */
	fun <T> firstResult(source: Flow<T>, name: String): Flow<T> {
		if (!enabled) return source
		return flow {
			val operation = begin("repository", name)
			var first = true
			try {
				source.collect {
					if (first) {
						first = false
						end(operation)
						milestone(operation?.span?.trace, "favourites_data_ready")
					}
					emit(it)
				}
			} catch (error: Throwable) {
				if (first) end(operation, if (error is CancellationException) "cancel" else "failure")
				throw error
			}
		}
	}

	fun observed(kind: String, name: String, durationNs: Long = 0, outcome: String = "complete", trace: DiagnosticsSession.Trace? = currentTrace) {
		session?.observed(kind, name, trace, durationNs, outcome)
	}

	fun registerFragments(activity: android.app.Activity) { lifecycle?.registerFragments(activity) }

	fun resumed(activity: android.app.Activity) { monitor?.attach(activity) }
	fun paused(activity: android.app.Activity) { monitor?.detach(activity) }
}
