package org.koitharu.kotatsu.browser

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.WorkerThread
import org.koitharu.kotatsu.core.network.webview.adblock.AdBlock
import org.koitharu.kotatsu.core.network.webview.adblock.ResourceType
import java.io.ByteArrayInputStream

open class BrowserClient(
	private val callback: BaseBrowserActivity,
	private val adBlock: AdBlock?,
	private val additionalHeaders: Map<String, String> = emptyMap(),
) : WebViewClient() {

	@Volatile
	private var currentMainFrameUrl: String? = null

	override fun onPageFinished(webView: WebView, url: String) {
		super.onPageFinished(webView, url)
		currentMainFrameUrl = url
		callback.onLoadingStateChanged(isLoading = false)
	}

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		if (!url.isNullOrEmpty()) {
			currentMainFrameUrl = url
		}
		callback.onLoadingStateChanged(isLoading = true)
	}

	override fun onPageCommitVisible(view: WebView, url: String) {
		super.onPageCommitVisible(view, url)
		currentMainFrameUrl = url
		callback.onTitleChanged(view.title.orEmpty(), url)
	}

	override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
		super.doUpdateVisitedHistory(view, url, isReload)
		if (!url.isNullOrEmpty()) {
			currentMainFrameUrl = url
		}
		callback.onHistoryChanged()
	}

	@Suppress("DEPRECATION")
	@Deprecated("Deprecated in Java")
	override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
		return loadWithAdditionalHeaders(view, url) || super.shouldOverrideUrlLoading(view, url)
	}

	override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
		// loadUrl(url, headers) always creates a GET request. Replaying a form submission here would
		// therefore discard its POST body and can make a successful login look like it failed.
		if (
			request?.isForMainFrame == true &&
			request.method.equals("GET", ignoreCase = true) &&
			loadWithAdditionalHeaders(view, request.url.toString())
		) {
			return true
		}
		return super.shouldOverrideUrlLoading(view, request)
	}

	@WorkerThread
	@Suppress("DEPRECATION")
	@Deprecated("Deprecated in Java")
	override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
		if (url.isNullOrEmpty()) {
			return super.shouldInterceptRequest(view, url)
		}
		return if (
			adBlock?.shouldLoadUrl(url, currentMainFrameUrl, inferResourceType(url, accept = null)) != false
		) {
			super.shouldInterceptRequest(view, url)
		} else {
			emptyResponse()
		}
	}

	@WorkerThread
	override fun shouldInterceptRequest(
		view: WebView?,
		request: WebResourceRequest?,
	): WebResourceResponse? {
		if (request == null || request.isForMainFrame) {
			return super.shouldInterceptRequest(view, request)
		}
		val accept = request.requestHeaders.entries
			.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
			?.value
		val resourceType = inferResourceType(request.url.toString(), accept)
		return if (
			adBlock?.shouldLoadUrl(request.url.toString(), currentMainFrameUrl, resourceType) != false
		) {
			super.shouldInterceptRequest(view, request)
		} else {
			emptyResponse()
		}
	}

	private fun loadWithAdditionalHeaders(view: WebView?, url: String?): Boolean {
		if (view == null || url.isNullOrEmpty() || additionalHeaders.isEmpty()) return false
		if (!url.startsWith("http://") && !url.startsWith("https://")) return false
		view.loadUrl(url, additionalHeaders)
		return true
	}

	private fun emptyResponse(): WebResourceResponse = WebResourceResponse(
		"text/plain",
		"utf-8",
		204,
		"No Content",
		mapOf("Cache-Control" to "no-store"),
		ByteArrayInputStream(byteArrayOf()),
	)

	private fun inferResourceType(url: String, accept: String?): ResourceType {
		val path = url.substringBefore('?').substringBefore('#').lowercase()
		val accepted = accept?.lowercase().orEmpty()
		return when {
			"text/css" in accepted || path.endsWith(".css") -> ResourceType.STYLESHEET
			"image/" in accepted || path.hasAnySuffix(IMAGE_SUFFIXES) -> ResourceType.IMAGE
			"javascript" in accepted || path.endsWith(".js") || path.endsWith(".mjs") -> ResourceType.SCRIPT
			"font/" in accepted || path.hasAnySuffix(FONT_SUFFIXES) -> ResourceType.FONT
			"audio/" in accepted || "video/" in accepted || path.hasAnySuffix(MEDIA_SUFFIXES) -> ResourceType.MEDIA
			"text/html" in accepted -> ResourceType.DOCUMENT
			"application/json" in accepted || "text/event-stream" in accepted -> ResourceType.XHR
			else -> ResourceType.OTHER
		}
	}

	private fun String.hasAnySuffix(suffixes: Set<String>): Boolean = suffixes.any(::endsWith)

	private companion object {
		val IMAGE_SUFFIXES = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".svg", ".ico")
		val FONT_SUFFIXES = setOf(".woff", ".woff2", ".ttf", ".otf", ".eot")
		val MEDIA_SUFFIXES = setOf(".mp3", ".m4a", ".aac", ".ogg", ".wav", ".mp4", ".m4v", ".webm")
	}
}
