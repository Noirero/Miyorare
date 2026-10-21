package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import java.io.File
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

/** Lazy host for the isolated Kotatsu Runtime Pack. No runtime Kotatsu classes cross this boundary. */
@Singleton
class KotatsuRuntimeManager @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private val store = KotatsuRuntimePackStore(context)
	private val verifier = KotatsuRuntimePackVerifier(context)
	private val prefs = context.getSharedPreferences("kotatsu_runtime_sources", Context.MODE_PRIVATE)
	private val sourceState = MutableStateFlow<List<KotatsuMangaSource>>(emptyList())
	private val enabledSourceState = MutableStateFlow(
		prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty().toSet(),
	)
	val sources: StateFlow<List<KotatsuMangaSource>> = sourceState
	val enabledSourceKeys: StateFlow<Set<String>> = enabledSourceState

	@Volatile private var initialized = false
	@Volatile private var session: Session? = null

	init { activeInstance = this }

	fun initialize() {
		if (initialized) return
		synchronized(this) {
			if (initialized) return
			initialized = true
			val currentManifest = store.currentManifest()
			if (currentManifest != null && store.currentFile.isFile) {
				val current = runCatching { openSession(store.currentFile, currentManifest, RuntimeOrigin.CURRENT) }.getOrNull()
				if (current != null) {
					publishSession(current)
					return
				}
				if (store.rollback()) {
					val previous = store.currentManifest()?.let { manifest ->
						runCatching { openSession(store.currentFile, manifest, RuntimeOrigin.ROLLED_BACK) }.getOrNull()
					}
					if (previous != null) {
						publishSession(previous)
						return
					}
				}
			}
			loadBundledFallback()?.let(::publishSession)
		}
	}

	fun getAvailableSources(): List<KotatsuMangaSource> { initialize(); return sourceState.value }

	fun getEnabledSources(): List<KotatsuMangaSource> {
		val enabled = enabledSourceState.value
		// Keep disappeared keys in preferences: a temporary upstream removal must not erase the user's
		// enablement choice. Only sources present in the active runtime are exposed to the app.
		return getAvailableSources().filter { it.sourceKey in enabled && !it.isBroken }
	}

	fun resolveSource(storedName: String): KotatsuMangaSource? {
		val key = KotatsuMangaSource.sourceKeyOf(storedName) ?: return null
		return getAvailableSources().firstOrNull { it.sourceKey == key }
	}

	fun setSourceEnabled(sourceKey: String, enabled: Boolean) {
		require(getAvailableSources().any { it.sourceKey == sourceKey }) { "Unknown Kotatsu source: $sourceKey" }
		val values = enabledSourceState.value.toMutableSet()
		if (enabled) values += sourceKey else values -= sourceKey
		val snapshot = values.toSet()
		prefs.edit().putStringSet(KEY_ENABLED, snapshot).apply()
		enabledSourceState.value = snapshot
	}

	fun status(): RuntimeStatus {
		initialize()
		val active = session
		return RuntimeStatus(
			isAvailable = active != null,
			origin = active?.origin,
			packVersion = active?.manifest?.packVersion,
			upstreamRevision = active?.manifest?.upstreamRevision,
			sourceCount = sourceState.value.size,
		)
	}

	@Synchronized
	fun install(candidate: File, manifest: KotatsuRuntimePackManifest) {
		store.install(candidate, manifest, verifier)
		try {
			publishSession(openSession(store.currentFile, manifest, RuntimeOrigin.CURRENT))
		} catch (error: Throwable) {
			val recovered = if (store.rollback()) {
				store.currentManifest()?.let { previous ->
					runCatching { openSession(store.currentFile, previous, RuntimeOrigin.ROLLED_BACK) }.getOrNull()
				}
			} else null
			if (recovered != null) {
				publishSession(recovered)
			} else {
				loadBundledFallback()?.let(::publishSession)
			}
			throw error
		}
	}

	internal fun runtime(): Session {
		initialize()
		return checkNotNull(session) { "Kotatsu Runtime Pack is not installed or could not be loaded" }
	}

	private fun loadBundledFallback(): Session? = store.ensureBundled(verifier)?.let { bundled ->
		runCatching { openSession(bundled.file, bundled.manifest, RuntimeOrigin.BUNDLED) }.getOrNull()
	}

	private fun publishSession(value: Session) {
		session = value
		sourceState.value = value.catalog()
	}

	private fun openSession(file: File, manifest: KotatsuRuntimePackManifest, origin: RuntimeOrigin): Session {
		verifier.verify(file, manifest).getOrThrow()
		val optimized = File(context.codeCacheDir, "kotatsu-runtime").apply { mkdirs() }
		val loader = KotatsuRuntimeClassLoader(file.absolutePath, optimized.absolutePath, context.classLoader)
		val clazz = loader.loadClass(KotatsuRuntimePackContract.ENTRYPOINT_CLASS)
		// Context is an Android platform type, not a Kotatsu parser type. The executable pack is already
		// trusted by signature; protocol v1 uses Context only as its bootstrap capability.
		val instance = clazz.getConstructor(Context::class.java).newInstance(context.applicationContext)
		val result = Session(instance, clazz, manifest, origin)
		require(result.protocolVersion() == KotatsuRuntimePackContract.PROTOCOL_VERSION) { "Kotatsu runtime protocol mismatch" }
		require(result.healthCheck()) { "Kotatsu runtime health check failed" }
		return result
	}

	internal class Session(
		private val target: Any,
		clazz: Class<*>,
		val manifest: KotatsuRuntimePackManifest,
		val origin: RuntimeOrigin,
	) {
		private val methods = KotatsuRuntimePackContract.Methods
		private val protocol: Method = clazz.getMethod(methods.PROTOCOL_VERSION)
		private val health: Method = clazz.getMethod(methods.HEALTH_CHECK)
		private val catalog: Method = clazz.getMethod(methods.CATALOG_JSON)
		private val list: Method = clazz.getMethod(methods.GET_LIST_JSON, String::class.java, Integer.TYPE, String::class.java, String::class.java)
		private val details: Method = clazz.getMethod(methods.GET_DETAILS_JSON, String::class.java, String::class.java)
		private val pages: Method = clazz.getMethod(methods.GET_PAGES_JSON, String::class.java, String::class.java)
		private val pageUrl: Method = clazz.getMethod(methods.GET_PAGE_URL, String::class.java, String::class.java)
		private val related: Method = clazz.getMethod(methods.GET_RELATED_JSON, String::class.java, String::class.java)
		private val sortOrders: Method = clazz.getMethod(methods.SORT_ORDERS_JSON, String::class.java)
		private val filterCapabilities: Method = clazz.getMethod(methods.FILTER_CAPABILITIES_JSON, String::class.java)
		private val filterOptions: Method = clazz.getMethod(methods.FILTER_OPTIONS_JSON, String::class.java)
		private val sourceConfig: Method = clazz.getMethod(methods.SOURCE_CONFIG_JSON, String::class.java)
		private val setSourceConfig: Method = clazz.getMethod(methods.SET_SOURCE_CONFIG_JSON, String::class.java, String::class.java)
		private val headers: Method = clazz.getMethod(methods.REQUEST_HEADERS_JSON, String::class.java)

		fun protocolVersion() = protocol.invoke(target) as Int
		fun healthCheck() = health.invoke(target) as Boolean

		fun catalog(): List<KotatsuMangaSource> {
			val array = JSONArray(catalog.invoke(target) as String)
			val result = buildList(array.length()) {
				for (i in 0 until array.length()) {
					val item = array.getJSONObject(i)
					add(
						KotatsuMangaSource(
							item.getString("key"),
							item.optString("title", item.getString("key")),
							item.optString("locale"),
							item.optString("contentType", "OTHER"),
							item.optBoolean("broken"),
						),
					)
				}
			}
			require(result.map { it.sourceKey }.toSet().size == result.size) { "Kotatsu runtime catalog contains duplicate source keys" }
			return result
		}

		fun getList(source: String, offset: Int, order: String, filterJson: String) = list.invoke(target, source, offset, order, filterJson) as String
		fun getDetails(source: String, manga: String) = details.invoke(target, source, manga) as String
		fun getPages(source: String, chapter: String) = pages.invoke(target, source, chapter) as String
		fun getPageUrl(source: String, page: String) = pageUrl.invoke(target, source, page) as String
		fun getRelated(source: String, manga: String) = related.invoke(target, source, manga) as String
		fun sortOrders(source: String): Set<String> = JSONArray(sortOrders.invoke(target, source) as String).let { array -> buildSet { for (i in 0 until array.length()) add(array.getString(i)) } }
		fun filterCapabilities(source: String): JSONObject = JSONObject(filterCapabilities.invoke(target, source) as String)
		fun filterOptions(source: String): JSONObject = JSONObject(filterOptions.invoke(target, source) as String)
		fun sourceConfig(source: String): JSONObject = JSONObject(sourceConfig.invoke(target, source) as String)
		fun setSourceConfig(source: String, valuesJson: String): JSONObject = JSONObject(setSourceConfig.invoke(target, source, valuesJson) as String)
		fun requestHeaders(source: String): JSONObject = JSONObject(headers.invoke(target, source) as String)
	}

	data class RuntimeStatus(
		val isAvailable: Boolean,
		val origin: RuntimeOrigin?,
		val packVersion: String?,
		val upstreamRevision: String?,
		val sourceCount: Int,
	)

	enum class RuntimeOrigin { BUNDLED, CURRENT, ROLLED_BACK }

	companion object {
		private const val KEY_ENABLED = "enabled_sources"
		@Volatile private var activeInstance: KotatsuRuntimeManager? = null
		fun getByName(name: String): KotatsuMangaSource? = activeInstance?.resolveSource(name)
	}
}
