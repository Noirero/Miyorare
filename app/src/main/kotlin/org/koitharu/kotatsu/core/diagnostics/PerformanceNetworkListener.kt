package org.koitharu.kotatsu.core.diagnostics

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject

/** Measures the entire call through body consumption; never reads URL, headers or exception text. */
class PerformanceNetworkListener @Inject constructor() : EventListener.Factory {
	override fun create(call: Call): EventListener {
		if (!PerformanceDiagnostics.enabled) return EventListener.NONE
		val trace = PerformanceDiagnostics.currentTrace
		return PerformanceNetworkEvents(
			start = { PerformanceDiagnostics.begin("network", "http_call", trace) },
			finish = PerformanceDiagnostics::end,
		)
	}
}

internal class PerformanceNetworkEvents(
	private val start: () -> PerformanceDiagnostics.Operation?,
	private val finish: (PerformanceDiagnostics.Operation?, String, String) -> Unit,
) : EventListener() {
	private var operation: PerformanceDiagnostics.Operation? = null
	private var route = "unknown"
	private var status = 0
	override fun callStart(call: Call) { operation = start() }
	override fun cacheHit(call: Call, response: Response) { route = "cache"; status = response.code }
	override fun cacheMiss(call: Call) { route = "network" }
	override fun cacheConditionalHit(call: Call, cachedResponse: Response) { route = "conditional_cache" }
	override fun responseHeadersEnd(call: Call, response: Response) { status = response.code }
	override fun callEnd(call: Call) {
		finish(operation, if (status in 200..399) "complete" else "failure", detail())
	}
	override fun callFailed(call: Call, ioe: IOException) {
		finish(operation, if (call.isCanceled()) "cancel" else "failure", detail())
	}
	private fun detail() = "route=$route status=$status scope=foreground_at_start"
}
