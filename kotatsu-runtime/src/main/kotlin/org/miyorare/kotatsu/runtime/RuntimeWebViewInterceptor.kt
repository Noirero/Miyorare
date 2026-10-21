package org.miyorare.kotatsu.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** WebView request-capture capability owned entirely by the isolated runtime. */
internal class RuntimeWebViewInterceptor(
	private val context: Context,
	private val userAgent: String,
) {

	suspend fun intercept(url: String, config: InterceptionConfig): List<InterceptedRequest> =
		withContext(Dispatchers.Main.immediate) {
			suspendCancellableCoroutine { continuation ->
				val maxRequests = config.maxRequests.coerceAtLeast(1)
				val captured = Collections.synchronizedList(mutableListOf<InterceptedRequest>())
				val finished = AtomicBoolean(false)
				val timeoutReached = AtomicBoolean(false)
				val pendingFilters = AtomicInteger(0)
				val scriptInjected = AtomicBoolean(false)
				val handler = Handler(Looper.getMainLooper())
				var webView: WebView? = null
				lateinit var timeoutRunnable: Runnable

				fun snapshot(): List<InterceptedRequest> = synchronized(captured) { captured.toList() }

				fun finish() {
					if (!finished.compareAndSet(false, true)) return
					handler.removeCallbacks(timeoutRunnable)
					val result = snapshot()
					val view = webView
					webView = null
					view?.stopLoading()
					view?.webViewClient = WebViewClient()
					view?.destroy()
					if (continuation.isActive) continuation.resume(result)
				}

				fun finishAfterPendingFilters() {
					if (timeoutReached.get() && pendingFilters.get() == 0) finish()
				}

				fun record(intercepted: InterceptedRequest) {
					if (finished.get()) return
					val reachedLimit = synchronized(captured) {
						if (captured.size >= maxRequests) return@synchronized true
						captured += intercepted
						captured.size >= maxRequests
					}
					if (reachedLimit) handler.post(::finish)
				}

				fun evaluateFilter(intercepted: InterceptedRequest) {
					if (finished.get()) return
					val script = config.filterScript
					if (script.isNullOrBlank()) {
						record(intercepted)
						return
					}
					val view = webView ?: return
					pendingFilters.incrementAndGet()
					val requestJson = JSONObject()
						.put("url", intercepted.url)
						.put("method", intercepted.method)
						.put("headers", JSONObject(intercepted.headers))
						.put("timestamp", intercepted.timestamp)
						.put("body", intercepted.body ?: JSONObject.NULL)
						.toString()
					val predicate = """
						(function() {
							const request = $requestJson;
							const url = request.url;
							try {
								return !!(function() { $script }).call(null);
							} catch (error) {
								return false;
							}
						})()
					""".trimIndent()
					view.evaluateJavascript(predicate) { raw ->
						try {
							if (!finished.get() && raw == "true") record(intercepted)
						} finally {
							pendingFilters.decrementAndGet()
							finishAfterPendingFilters()
						}
					}
				}

				fun capture(request: WebResourceRequest) {
					if (finished.get()) return
					val intercepted = InterceptedRequest(
						url = request.url.toString(),
						method = request.method,
						headers = request.requestHeaders.orEmpty(),
						timestamp = System.currentTimeMillis(),
					)
					if (config.urlPattern?.containsMatchIn(intercepted.url) == false) return
					// WebResourceRequest callbacks may arrive off-main. The predicate is real JavaScript and must
					// execute in the WebView context; posting also keeps all capture state serialized on main.
					handler.post { evaluateFilter(intercepted) }
				}

				try {
					webView = WebView(context).apply {
						settings.javaScriptEnabled = true
						settings.userAgentString = userAgent
						webViewClient = object : WebViewClient() {
							override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
								if (request != null) capture(request)
								return null
							}

							override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
								if (request != null) capture(request)
								// Capturing is observational. Returning true would cancel a matching navigation and alter
								// the page behaviour the parser is trying to inspect.
								return false
							}

							override fun onPageFinished(view: WebView, pageUrl: String) {
								super.onPageFinished(view, pageUrl)
								val script = config.pageScript
								if (!script.isNullOrBlank() && scriptInjected.compareAndSet(false, true)) {
									view.evaluateJavascript(script, null)
								}
							}
						}
						loadUrl(url)
					}
					timeoutRunnable = Runnable {
						timeoutReached.set(true)
						finishAfterPendingFilters()
					}
					handler.postDelayed(timeoutRunnable, config.timeoutMs.coerceAtLeast(1L))
					continuation.invokeOnCancellation {
						handler.post {
							if (!finished.compareAndSet(false, true)) return@post
							handler.removeCallbacks(timeoutRunnable)
							webView?.stopLoading()
							webView?.destroy()
							webView = null
						}
					}
				} catch (error: Throwable) {
					if (finished.compareAndSet(false, true)) {
						webView?.destroy()
						webView = null
						continuation.resumeWith(Result.failure(error))
					}
				}
			}
		}

	suspend fun captureUrls(pageUrl: String, pattern: Regex, timeout: Long): List<String> =
		intercept(
			pageUrl,
			InterceptionConfig(
				timeoutMs = timeout,
				maxRequests = 1,
				urlPattern = pattern,
			),
		).map { it.url }
}
