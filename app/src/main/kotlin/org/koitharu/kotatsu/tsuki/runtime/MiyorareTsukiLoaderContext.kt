@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.tsuki.runtime

import android.content.Context
import android.os.LocaleList
import android.util.Base64
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import tsuki.MangaLoaderContext
import tsuki.MangaParser
import tsuki.bitmap.Bitmap
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaSource
import tsuki.network.UserAgents
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One loader context per loaded plugin; construction happens only when that plugin is first used. */
internal class MiyorareTsukiLoaderContext(
	private val appContext: Context,
	private val plugin: TsukiPluginDescriptor,
	private val runtime: TsukiPluginRuntime,
	override val httpClient: OkHttpClient,
	override val cookieJar: CookieJar,
	private val webViewExecutor: TsukiWebViewExecutor,
) : MangaLoaderContext() {

	private val configs = ConcurrentHashMap<String, MangaSourceConfig>()

	override fun newParserInstance(source: MangaSource): MangaParser =
		runtime.createParser(plugin, source.name)

	override fun getParserSources(): List<MangaSource> = runtime.rawSources(plugin)

	override fun getConfig(source: MangaSource): MangaSourceConfig = configs.getOrPut(source.name) {
		TsukiSourceConfig(appContext, plugin.storageKey, source)
	}

	override fun getDefaultUserAgent(): String = webViewExecutor.defaultUserAgent ?: UserAgents.FIREFOX_MOBILE

	override fun encodeBase64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

	override fun decodeBase64(data: String): ByteArray = Base64.decode(data, Base64.DEFAULT)

	override fun getPreferredLocales(): List<Locale> {
		val locales = LocaleList.getAdjustedDefault()
		return buildList(locales.size()) {
			for (i in 0 until locales.size()) add(locales[i])
		}
	}

	override suspend fun evaluateJs(script: String): String? =
		webViewExecutor.evaluateJs(null, script)

	override suspend fun evaluateJs(baseUrl: String, script: String): String? =
		webViewExecutor.evaluateJs(baseUrl, script)

	override fun requestBrowserAction(parser: MangaParser, url: String): Nothing {
		val descriptor = plugin.sources.firstOrNull { it.name == parser.source.name }
			?: TsukiSourceDescriptor(
				name = parser.source.name,
				title = parser.source.title,
				locale = parser.source.locale,
				contentType = parser.source.contentType.name,
			)
		throw InteractiveActionRequiredException(
			source = TsukiMangaSource(plugin, descriptor),
			url = url,
			userAgent = getDefaultUserAgent(),
		)
	}

	override fun redrawImageResponse(
		response: Response,
		redraw: (Bitmap) -> Bitmap,
	): Response = TsukiBitmapBridge.redraw(response, redraw)

	override fun createBitmap(width: Int, height: Int): Bitmap = TsukiBitmapBridge.create(width, height)
}
