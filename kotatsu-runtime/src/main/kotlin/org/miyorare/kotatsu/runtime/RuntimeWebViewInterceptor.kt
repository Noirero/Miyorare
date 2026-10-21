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
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Minimal WebView request-capture capability owned entirely by the isolated runtime. */
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

				fun capture(request: WebResourceRequest): Boolean {
					if (finished.get()) return false
					val intercepted = InterceptedRequest(
						url = request.url.toString(),
						method = request.method,
						headers = request.requestHeaders.orEmpty(),
						timestamp = System.currentTimeMillis(),
					)
					if (config.urlPattern?.containsMatchIn(intercepted.url) == false) return false
					if (!matchesFilterScript(config.filterScript, intercepted.url)) return false
					val reachedLimit = synchronized(captured) {
						if (captured.size >= maxRequests) return@synchronized true
						captured += intercepted
						captured.size >= maxRequests
					}
					if (reachedLimit) handler.post(::finish)
					return true
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
								return request != null && capture(request)
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
					timeoutRunnable = Runnable(::finish)
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

	private fun matchesFilterScript(script: String?, requestUrl: String): Boolean {
		if (script.isNullOrBlank()) return true
		val returnIndex = script.lastIndexOf("return")
		if (returnIndex < 0) return true
		val expression = script.substring(returnIndex + "return".length)
			.trim()
			.removeSuffix(";")
			.trim()
		if (expression.isEmpty() || expression == "true") return true
		if (expression == "false") return false

		return expression.split("||").any { rawClause ->
			val clause = rawClause.trim().trim('(', ')').trim()
			val terms = clause.split("&&").map(String::trim).filter(String::isNotEmpty)
			terms.isNotEmpty() && terms.all { rawTerm ->
				val term = rawTerm.trim().trim('(', ')').trim()
				val match = URL_INCLUDES.matchEntire(term) ?: return@all false
				requestUrl.contains(match.groupValues[2])
			}
		}
	}

	private companion object {
		val URL_INCLUDES = Regex("""url\.includes\(\s*(['\"])(.*?)\1\s*\)""")
	}
}
