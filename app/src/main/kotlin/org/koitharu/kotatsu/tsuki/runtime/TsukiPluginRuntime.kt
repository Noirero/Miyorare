package org.koitharu.kotatsu.tsuki.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.tsuki.TsukiPluginClassLoader
import org.koitharu.kotatsu.tsuki.TsukiPluginManager
import org.koitharu.kotatsu.tsuki.TsukiPluginValidator
import org.koitharu.kotatsu.tsuki.model.TsukiCompatibilityStatus
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import org.koitharu.kotatsu.tsuki.model.TsukiSourceIdentity
import tsuki.MangaParser
import tsuki.model.MangaSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazy Tsuki 1.0.5 runtime. Installing/scanning metadata never reaches this class loader path.
 *
 * At most four plugin class loaders and eight parser instances are retained. Updating a JAR changes
 * its SHA, which invalidates the old class loader and every parser created from it on the next use.
 */
@Singleton
class TsukiPluginRuntime @Inject constructor(
	@ApplicationContext private val context: Context,
	private val pluginManager: TsukiPluginManager,
	@BaseHttpClient private val baseHttpClient: OkHttpClient,
	private val cookieJar: CookieJar,
	private val webViewExecutor: TsukiWebViewExecutor,
) {

	data class ParserHandle internal constructor(
		val source: TsukiMangaSource,
		val rawSource: MangaSource,
		val parser: MangaParser,
		val httpClient: OkHttpClient,
	)

	private data class LoadedPlugin(
		val descriptor: TsukiPluginDescriptor,
		val loader: TsukiPluginClassLoader,
		val factory: Method,
		val rawSources: Map<String, MangaSource>,
		val context: MiyorareTsukiLoaderContext,
		val httpClient: OkHttpClient,
	)

	private val lock = Any()
	private val loadedPlugins = object : LinkedHashMap<String, LoadedPlugin>(MAX_LOADED_PLUGINS + 1, 0.75f, true) {}
	private val parserCache = object : LinkedHashMap<String, ParserHandle>(MAX_CACHED_PARSERS + 1, 0.75f, true) {}

	fun peekHandle(source: TsukiMangaSource): ParserHandle? {
		val current = pluginManager.findPlugin(source.plugin.provider, source.pluginId) ?: return null
		if (current.state != TsukiPluginState.ENABLED ||
			current.compatibility != TsukiCompatibilityStatus.COMPATIBLE
		) {
			return null
		}
		return synchronized(lock) {
			val cached = parserCache[source.name] ?: return@synchronized null
			if (cached.source.plugin.sha256.equals(current.sha256, ignoreCase = true)) {
				cached
			} else {
				parserCache.remove(source.name)
				null
			}
		}
	}

	fun getHandle(source: TsukiMangaSource): ParserHandle {
		val current = pluginManager.findPlugin(source.plugin.provider, source.pluginId)
			?: error("Tsuki plugin ${source.pluginId} is not installed")
		require(current.state == TsukiPluginState.ENABLED) { "Tsuki plugin ${current.displayName} is disabled" }
		require(current.compatibility == TsukiCompatibilityStatus.COMPATIBLE) {
			current.failureReason ?: "Tsuki plugin ${current.displayName} is incompatible"
		}
		val descriptor = current.sources.firstOrNull { it.name == source.descriptor.name && !it.isBroken }
			?: error("Tsuki source ${source.descriptor.name} is no longer provided by ${current.displayName}")
		val freshSource = TsukiMangaSource(current, descriptor)
		val parser = createParser(current, descriptor.name)
		return synchronized(lock) {
			parserCache[freshSource.name] ?: error("Tsuki parser cache lost ${freshSource.name}")
		}.also { check(it.parser === parser) }
	}

	internal fun createParser(plugin: TsukiPluginDescriptor, sourceName: String): MangaParser {
		val current = pluginManager.findPlugin(plugin.provider, plugin.pluginId) ?: plugin
		val key = TsukiSourceIdentity(current.provider, current.pluginId, sourceName).storedName
		synchronized(lock) {
			parserCache[key]?.let { cached ->
				if (cached.source.plugin.sha256.equals(current.sha256, ignoreCase = true)) {
					return cached.parser
				}
				parserCache.remove(key)
			}
		}

		val loaded = loadPlugin(current)
		val rawSource = loaded.rawSources[sourceName]
			?: error("Tsuki plugin ${current.displayName} has no source $sourceName")
		val sourceDescriptor = current.sources.firstOrNull { it.name == sourceName }
			?: error("Missing metadata for Tsuki source $sourceName")
		val parser = try {
			loaded.factory.invoke(null, rawSource, loaded.context) as? MangaParser
				?: error("Tsuki factory returned an unexpected parser type")
		} catch (e: InvocationTargetException) {
			throw e.targetException
		}
		check(parser.source.name == rawSource.name) {
			"Tsuki factory returned parser for ${parser.source.name} instead of ${rawSource.name}"
		}
		val handle = ParserHandle(
			source = TsukiMangaSource(current, sourceDescriptor),
			rawSource = rawSource,
			parser = parser,
			httpClient = loaded.httpClient,
		)
		synchronized(lock) {
			parserCache[key]?.let { cached ->
				if (cached.source.plugin.sha256.equals(current.sha256, ignoreCase = true)) {
					return cached.parser
				}
				parserCache.remove(key)
			}
			parserCache[key] = handle
			trimParserCache()
		}
		return parser
	}

	internal fun rawSources(plugin: TsukiPluginDescriptor): List<MangaSource> =
		loadPlugin(plugin).rawSources.values.toList()

	fun getRequestHeaders(source: TsukiMangaSource): Headers = getHandle(source).parser.getRequestHeaders()

	fun getHttpClient(source: TsukiMangaSource): OkHttpClient = getHandle(source).httpClient

	fun createTaggedRequest(source: TsukiMangaSource, url: String): Request {
		val handle = getHandle(source)
		return Request.Builder()
			.url(url)
			.tag(MangaSource::class.java, handle.rawSource)
			.build()
	}

	private fun loadPlugin(requested: TsukiPluginDescriptor): LoadedPlugin {
		val current = pluginManager.findPlugin(requested.provider, requested.pluginId) ?: requested
		val key = current.storageKey
		synchronized(lock) {
			loadedPlugins[key]?.let { loaded ->
				if (loaded.descriptor.sha256.equals(current.sha256, ignoreCase = true)) return loaded
				evictPluginLocked(key)
			}
		}

		val jar = pluginManager.pluginJar(current) ?: error("Tsuki plugin JAR is missing: ${current.displayName}")
		val validated = TsukiPluginValidator.validate(jar).getOrThrow()
		require(validated.sha256.equals(current.sha256, ignoreCase = true)) {
			"Tsuki plugin checksum changed after validation"
		}
		require(validated.fileSize == current.fileSize) { "Tsuki plugin size changed after validation" }

		val loader = TsukiPluginClassLoader(
			dexPath = jar.absolutePath,
			optimizedDirectory = context.codeCacheDir.absolutePath,
			parent = context.classLoader,
		)
		val factoryClass = loader.loadClass(TsukiPluginClassLoader.MODERN_FACTORY)
		val sourceEnum = loader.loadClass(TsukiPluginClassLoader.MODERN_SOURCE_ENUM)
		val loaderContextClass = loader.loadClass(TsukiPluginClassLoader.MODERN_CONTEXT)
		val factory = factoryClass.getMethod("newParser", sourceEnum, loaderContextClass)
		val rawSources = sourceEnum.enumConstants.orEmpty()
			.mapNotNull { it as? MangaSource }
			.associateBy { it.name }
		require(rawSources.isNotEmpty()) { "Tsuki plugin exposes no modern sources" }
		val expectedNames = current.sources.asSequence().map { it.name }.toSet()
		require(rawSources.keys == expectedNames) {
			"Tsuki plugin source metadata changed after installation; reinstall the plugin"
		}

		val routingInterceptor = RoutingInterceptor(this, current)
		val client = baseHttpClient.newBuilder().apply {
			// Parser headers/interception must run before Cloudflare/rate-limit/base interceptors.
			interceptors().add(0, routingInterceptor)
		}.build()
		val loaderContext = MiyorareTsukiLoaderContext(
			appContext = context,
			plugin = current,
			runtime = this,
			httpClient = client,
			cookieJar = cookieJar,
			webViewExecutor = webViewExecutor,
		)
		val loaded = LoadedPlugin(current, loader, factory, rawSources, loaderContext, client)
		synchronized(lock) {
			loadedPlugins[key]?.let { existing ->
				if (existing.descriptor.sha256.equals(current.sha256, ignoreCase = true)) return existing
				evictPluginLocked(key)
			}
			loadedPlugins[key] = loaded
			trimPluginCache()
		}
		return loaded
	}

	private fun findCachedParser(plugin: TsukiPluginDescriptor, rawSource: MangaSource): MangaParser? = synchronized(lock) {
		val key = TsukiSourceIdentity(plugin.provider, plugin.pluginId, rawSource.name).storedName
		parserCache[key]
			?.takeIf { it.source.plugin.sha256.equals(plugin.sha256, ignoreCase = true) }
			?.parser
	}

	private fun trimPluginCache() {
		while (loadedPlugins.size > MAX_LOADED_PLUGINS) {
			val eldest = loadedPlugins.entries.iterator().next().key
			evictPluginLocked(eldest)
		}
	}

	private fun trimParserCache() {
		while (parserCache.size > MAX_CACHED_PARSERS) {
			parserCache.entries.iterator().apply {
				next()
				remove()
			}
		}

	private fun evictPluginLocked(pluginKey: String) {
		val removed = loadedPlugins.remove(pluginKey) ?: return
		val provider = removed.descriptor.provider
		val pluginId = removed.descriptor.pluginId
		parserCache.entries.removeAll { (_, handle) ->
			handle.source.plugin.provider == provider && handle.source.pluginId == pluginId
		}
	}

	private class RoutingInterceptor(
		private val runtime: TsukiPluginRuntime,
		private val plugin: TsukiPluginDescriptor,
	) : Interceptor {

		override fun intercept(chain: Interceptor.Chain): Response {
			val request = chain.request()
			val rawSource = request.tag(MangaSource::class.java)
			val parser = rawSource?.let { runtime.findCachedParser(plugin, it) }
			val builder = request.newBuilder()
			if (parser != null) {
				val parserHeaders = runCatching { parser.getRequestHeaders() }.getOrNull()
				parserHeaders?.forEach { (name, value) ->
					if (request.header(name) == null) builder.header(name, value)
				}
			}
			if (builder.build().header("User-Agent") == null) {
				builder.header("User-Agent", runtime.webViewExecutor.defaultUserAgent ?: tsuki.network.UserAgents.FIREFOX_MOBILE)
			}
			val routed = builder.build()
			return if (parser != null) {
				parser.intercept(ProxyChain(chain, routed))
			} else {
				chain.proceed(routed)
			}
		}
	}

	private class ProxyChain(
		private val delegate: Interceptor.Chain,
		private val routedRequest: Request,
	) : Interceptor.Chain by delegate {
		override fun request(): Request = routedRequest
	}

	private companion object {
		const val MAX_LOADED_PLUGINS = 4
		const val MAX_CACHED_PARSERS = 8
	}
}
