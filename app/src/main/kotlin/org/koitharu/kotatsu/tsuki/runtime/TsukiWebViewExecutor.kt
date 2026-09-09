package org.koitharu.kotatsu.tsuki.runtime

import android.content.Context
import android.util.AndroidRuntimeException
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Small WebView host used only by Tsuki plugins.
 *
 * Nothing is created at application startup. The first plugin that actually asks to evaluate
 * JavaScript creates one cached WebView, applies the app proxy configuration and reuses it under a
 * mutex. This keeps the optional plugin subsystem out of normal Miyorare startup and reader paths.
 */
@Singleton
class TsukiWebViewExecutor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
) {

	private val mutex = Mutex()
	private var cached: WeakReference<WebView>? = null

	val defaultUserAgent: String? by lazy {
		try {
			WebSettings.getDefaultUserAgent(context)
		} catch (e: AndroidRuntimeException) {
			e.printStackTraceDebug()
			null
		}
	}

	suspend fun evaluateJs(baseUrl: String?, script: String): String? = mutex.withLock {
		withContext(Dispatchers.Main.immediate) {
			val webView = obtainWebView()
			try {
				if (!baseUrl.isNullOrBlank()) {
					suspendCoroutine { cont ->
						webView.webViewClient = object : WebViewClient() {
							override fun onPageFinished(view: WebView, url: String?) {
								view.webViewClient = WebViewClient()
								cont.resume(Unit)
							}
						}
						webView.loadDataWithBaseURL(baseUrl, " ", "text/html", "utf-8", null)
					}
				}
				suspendCoroutine { cont ->
					webView.evaluateJavascript(script) { result ->
						cont.resume(result?.takeUnless { it == "null" })
					}
				}
			} finally {
				webView.stopLoading()
				webView.webViewClient = WebViewClient()
				webView.settings.userAgentString = defaultUserAgent
				webView.loadDataWithBaseURL(null, " ", "text/html", "utf-8", null)
				webView.clearHistory()
			}
		}
	}

	private suspend fun obtainWebView(): WebView {
		cached?.get()?.let { return it }
		return withContext(Dispatchers.Main.immediate) {
			cached?.get()?.let { return@withContext it }
			proxyProvider.applyWebViewConfig()
			WebView(context).also { view ->
				view.configureForParser(null)
				view.onResume()
				view.resumeTimers()
				cached = WeakReference(view)
			}
		}
	}
}
