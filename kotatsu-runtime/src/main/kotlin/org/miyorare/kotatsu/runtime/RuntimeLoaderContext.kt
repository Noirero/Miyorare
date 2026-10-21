@file:Suppress("DEPRECATION")

package org.miyorare.kotatsu.runtime

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect as AndroidRect
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

internal class RuntimeLoaderContext(private val appContext: Context) : MangaLoaderContext() {
	private val cookies = PersistentCookieJar(appContext)
	private val configs = ConcurrentHashMap<String, RuntimeSourceConfig>()

	override val cookieJar: CookieJar = cookies
	override val httpClient: OkHttpClient = OkHttpClient.Builder().cookieJar(cookies).build()

	override fun getConfig(source: MangaSource): MangaSourceConfig = configs.getOrPut(source.name) {
		RuntimeSourceConfig(appContext, source.name)
	}

	fun putConfig(source: MangaSource, key: ConfigKey<*>, value: Any?) {
		configs.getOrPut(source.name) { RuntimeSourceConfig(appContext, source.name) }.put(key, value)
	}

	override fun getDefaultUserAgent(): String =
		"Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}) AppleWebKit/537.36 " +
			"(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

	override fun getPreferredLocales(): List<Locale> = buildList {
		val locales = appContext.resources.configuration.locales
		for (i in 0 until locales.size()) add(locales[i])
		if (isEmpty()) add(Locale.getDefault())
	}

	override suspend fun evaluateJs(script: String): String? = evaluateJs("about:blank", script, 30_000L)

	override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = withTimeout(timeout) {
		suspendCancellableCoroutine { continuation ->
			val handler = Handler(Looper.getMainLooper())
			handler.post {
				val webView = WebView(appContext)
				webView.settings.javaScriptEnabled = true
				webView.settings.userAgentString = getDefaultUserAgent()
				continuation.invokeOnCancellation { handler.post { webView.destroy() } }
				webView.loadDataWithBaseURL(baseUrl, "<html><body></body></html>", "text/html", "UTF-8", null)
				webView.post {
					webView.evaluateJavascript(script) { raw ->
						val value = runCatching {
							if (raw == null || raw == "null") null else JSONArray("[$raw]").optString(0, raw)
						}.getOrNull()
						if (continuation.isActive) continuation.resume(value)
						webView.destroy()
					}
				}
			}
		}
	}

	override fun requestBrowserAction(parser: MangaParser, url: String): Nothing {
		throw UnsupportedOperationException("MIYORARE_BROWSER_ACTION|${parser.source.name}|$url")
	}

	override fun requestCloudflareVerification(parser: MangaParser, url: String): Nothing {
		throw UnsupportedOperationException("MIYORARE_CLOUDFLARE|${parser.source.name}|$url")
	}

	override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
		RuntimeBitmapBridge.redraw(response, redraw)

	override fun createBitmap(width: Int, height: Int): Bitmap = RuntimeBitmapBridge.create(width, height)
}

private class RuntimeSourceConfig(context: Context, sourceName: String) : MangaSourceConfig {
	private val prefs = context.getSharedPreferences("kotatsu_runtime_config_$sourceName", Context.MODE_PRIVATE)

	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: ConfigKey<T>): T {
		if (!prefs.contains(key.key)) return key.defaultValue
		val default = key.defaultValue
		return when (default) {
			is Boolean -> prefs.getBoolean(key.key, default) as T
			is Int -> prefs.getInt(key.key, default) as T
			is Long -> prefs.getLong(key.key, default) as T
			is Float -> prefs.getFloat(key.key, default) as T
			is String -> prefs.getString(key.key, default) as T
			null -> prefs.getString(key.key, null) as T
			else -> default
		}
	}

	fun put(key: ConfigKey<*>, value: Any?) {
		val edit = prefs.edit()
		when (value) {
			null -> edit.remove(key.key)
			is Boolean -> edit.putBoolean(key.key, value)
			is Int -> edit.putInt(key.key, value)
			is Long -> edit.putLong(key.key, value)
			is Float -> edit.putFloat(key.key, value)
			else -> edit.putString(key.key, value.toString())
		}
		edit.apply()
	}
}

private class PersistentCookieJar(context: Context) : CookieJar {
	private val prefs = context.getSharedPreferences("kotatsu_runtime_cookies", Context.MODE_PRIVATE)

	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		val editor = prefs.edit()
		for (cookie in cookies) {
			val key = "${cookie.domain}|${cookie.path}|${cookie.name}"
			if (cookie.expiresAt <= System.currentTimeMillis()) editor.remove(key) else editor.putString(key, cookie.toString())
		}
		editor.apply()
	}

	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val now = System.currentTimeMillis()
		val result = ArrayList<Cookie>()
		val expired = ArrayList<String>()
		for ((key, raw) in prefs.all) {
			val encoded = raw as? String ?: continue
			val domain = key.substringBefore('|')
			val base = runCatching { HttpUrl.Builder().scheme("https").host(domain.removePrefix(".")).build() }.getOrNull() ?: continue
			val cookie = Cookie.parse(base, encoded) ?: continue
			if (cookie.expiresAt <= now) expired += key else if (cookie.matches(url)) result += cookie
		}
		if (expired.isNotEmpty()) prefs.edit().also { edit -> expired.forEach(edit::remove) }.apply()
		return result
	}
}

private object RuntimeBitmapBridge {
	fun create(width: Int, height: Int): Bitmap {
		require(width > 0 && height > 0)
		return Wrapper(android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888))
	}

	fun redraw(response: Response, block: (Bitmap) -> Bitmap): Response {
		val bytes = response.body.bytes()
		val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Cannot decode parser image")
		val mutable = if (decoded.isMutable) decoded else decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
			?: error("Cannot allocate parser image")
		val input = Wrapper(mutable)
		var output: Wrapper? = null
		try {
			output = block(input) as? Wrapper ?: error("Parser returned a foreign bitmap")
			val stream = ByteArrayOutputStream()
			require(output.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
			return response.newBuilder().body(stream.toByteArray().toResponseBody("image/png".toMediaType())).build()
		} finally {
			val result = output?.bitmap
			if (result != null && result !== input.bitmap && !result.isRecycled) result.recycle()
			if (!input.bitmap.isRecycled) input.bitmap.recycle()
		}
	}

	private class Wrapper(val bitmap: android.graphics.Bitmap) : Bitmap {
		override val width: Int get() = bitmap.width
		override val height: Int get() = bitmap.height
		override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
			val source = (sourceBitmap as? Wrapper)?.bitmap ?: error("Parser bitmap belongs to another runtime")
			Canvas(bitmap).drawBitmap(source, AndroidRect(src.left, src.top, src.right, src.bottom), AndroidRect(dst.left, dst.top, dst.right, dst.bottom), null)
		}
	}
}
