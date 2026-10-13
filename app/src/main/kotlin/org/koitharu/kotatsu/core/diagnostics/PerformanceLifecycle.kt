package org.koitharu.kotatsu.core.diagnostics

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PerformanceLifecycle @Inject constructor() :
	FragmentManager.FragmentLifecycleCallbacks(), DefaultActivityLifecycleCallbacks {
	private var resumed = WeakReference<Activity>(null)
	private val registered = WeakHashMap<FragmentManager, Boolean>()

	init { PerformanceDiagnostics.lifecycle = this }

	fun attachCurrent() {
		Handler(Looper.getMainLooper()).post {
			resumed.get()?.let { activity ->
				if (!PerformanceDiagnostics.enabled) return@let
				val trace = PerformanceDiagnostics.navigation("verbose_enabled", activity.javaClass.simpleName, activity)
				val fragment = visibleFragment(activity)
				if (fragment != null) PerformanceDiagnostics.bind(fragment, trace)
				PerformanceDiagnostics.resumed(activity)
			}
		}
	}

	private fun visibleFragment(activity: Activity): Fragment? =
		(activity as? FragmentActivity)?.supportFragmentManager?.fragments?.lastOrNull {
			it.isAdded && !it.isHidden && it !is androidx.fragment.app.DialogFragment
		}

	fun registerFragments(activity: Activity) {
		val fm = (activity as? FragmentActivity)?.supportFragmentManager ?: return
		if (registered.put(fm, true) == null) fm.registerFragmentLifecycleCallbacks(this, true)
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		registerFragments(activity)
		if (!PerformanceDiagnostics.enabled) return
		val trace = PerformanceDiagnostics.trace(activity.intent.getStringExtra(PerformanceDiagnostics.TRACE_EXTRA))
			?: PerformanceDiagnostics.ownerTrace(activity)
			?: PerformanceDiagnostics.navigation("system", activity.javaClass.simpleName)
		PerformanceDiagnostics.bind(activity, trace)
		PerformanceDiagnostics.lifecycle(activity, "created")
	}
	override fun onActivityStarted(activity: Activity) = PerformanceDiagnostics.lifecycle(activity, "started")
	override fun onActivityResumed(activity: Activity) {
		resumed = WeakReference(activity)
		if (PerformanceDiagnostics.enabled) {
			val owner = visibleFragment(activity) ?: activity
			if (PerformanceDiagnostics.ownerTrace(owner) !== PerformanceDiagnostics.currentTrace) {
				val trace = PerformanceDiagnostics.navigation(PerformanceDiagnostics.currentTrace?.screen ?: "resume", owner.javaClass.simpleName, owner)
				PerformanceDiagnostics.bind(activity, trace)
			}
		}
		PerformanceDiagnostics.lifecycle(activity, "resumed")
		PerformanceDiagnostics.resumed(activity)
		if (PerformanceDiagnostics.enabled) PerformanceDiagnostics.drawn(activity, activity.window.decorView)
	}
	override fun onActivityPaused(activity: Activity) {
		if (resumed.get() === activity) resumed.clear()
		PerformanceDiagnostics.lifecycle(activity, "paused")
		PerformanceDiagnostics.paused(activity)
	}
	override fun onActivityStopped(activity: Activity) = PerformanceDiagnostics.lifecycle(activity, "stopped")
	override fun onActivityDestroyed(activity: Activity) {
		PerformanceDiagnostics.paused(activity)
		PerformanceDiagnostics.lifecycle(activity, "destroyed")
		(activity as? FragmentActivity)?.supportFragmentManager?.let { fm ->
			fm.unregisterFragmentLifecycleCallbacks(this)
			registered.remove(fm)
		}
	}
	override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) = PerformanceDiagnostics.lifecycle(f, "attached")
	override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) = PerformanceDiagnostics.lifecycle(f, "created")
	override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: android.view.View, savedInstanceState: Bundle?) = PerformanceDiagnostics.lifecycle(f, "view_created")
	override fun onFragmentStarted(fm: FragmentManager, f: Fragment) = PerformanceDiagnostics.lifecycle(f, "started")
	override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
		PerformanceDiagnostics.lifecycle(f, "resumed")
		if (PerformanceDiagnostics.enabled) f.view?.let { PerformanceDiagnostics.drawn(f, it) }
	}
	override fun onFragmentPaused(fm: FragmentManager, f: Fragment) = PerformanceDiagnostics.lifecycle(f, "paused")
	override fun onFragmentStopped(fm: FragmentManager, f: Fragment) = PerformanceDiagnostics.lifecycle(f, "stopped")
	override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = PerformanceDiagnostics.lifecycle(f, "view_destroyed")
	override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) = PerformanceDiagnostics.lifecycle(f, "destroyed")
}
