package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import java.io.File
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/** Lazy host for the isolated Kotatsu Runtime Pack. No runtime classes cross this boundary. */
@Singleton
class KotatsuRuntimeManager @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private val store = KotatsuRuntimePackStore(context)
	private val verifier = KotatsuRuntimePackVerifier(context)
	private val prefs = context.getSharedPreferences("kotatsu_runtime_sources", Context.MODE_PRIVATE)
	private val sourceState = MutableStateFlow<List<KotatsuMangaSource>>(emptyList())
	val sources: StateFlow<List<KotatsuMangaSource>> = sourceState

	@Volatile private var initialized = false
	@Volatile private var session: Session? = null

	init { activeInstance = this }

	fun initialize() {
		if (initialized) return
		synchronized(this) {
			if (initialized) return
			initialized = true
			val manifest = store.currentManifest()
			if (manifest != null && store.currentFile.isFile) {
				runCatching { openSession(store.currentFile, manifest) }
					.onSuccess(::publishSession)
					.onFailure {
						if (store.rollback()) {
							store.currentManifest()?.let { previous ->
								runCatching { openSession(store.currentFile, previous) }.onSuccess(::publishSession)
							}
						}
					}
			}
		}
	}

	fun getAvailableSources(): List<KotatsuMangaSource> { initialize(); return sourceState.value }
	fun getEnabledSources(): List<KotatsuMangaSource> {
		val enabled = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()
		return getAvailableSources().filter { it.sourceKey in enabled && !it.isBroken }
	}
	fun resolveSource(storedName: String): KotatsuMangaSource? {
		val key = KotatsuMangaSource.sourceKeyOf(storedName) ?: return null
		return getAvailableSources().firstOrNull { it.sourceKey == key }
	}
	fun setSourceEnabled(sourceKey: String, enabled: Boolean) {
		require(getAvailableSources().any { it.sourceKey == sourceKey }) { "Unknown Kotatsu source: $sourceKey" }
		val values = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty().toMutableSet()
		if (enabled) values += sourceKey else values -= sourceKey
		prefs.edit().putStringSet(KEY_ENABLED, values).apply()
	}

	@Synchronized
	fun install(candidate: File, manifest: KotatsuRuntimePackManifest) {
		store.install(candidate, manifest, verifier)
		try {
			publishSession(openSession(store.currentFile, manifest))
		} catch (error: Throwable) {
			if (store.rollback()) {
				store.currentManifest()?.let { previous ->
					runCatching { openSession(store.currentFile, previous) }.onSuccess(::publishSession)
				}
			}
			throw error
		}
	}

	internal fun runtime(): Session {
		initialize()
		return checkNotNull(session) { "Kotatsu Runtime Pack is not installed or could not be loaded" }
	}

	private fun publishSession(value: Session) {
		session = value
		sourceState.value = value.catalog()
	}

	private fun openSession(file: File, manifest: KotatsuRuntimePackManifest): Session {
		verifier.verify(file, manifest).getOrThrow()
		val optimized = File(context.codeCacheDir, "kotatsu-runtime").apply { mkdirs() }
		val loader = KotatsuRuntimeClassLoader(file.absolutePath, optimized.absolutePath, context.classLoader)
		val clazz = loader.loadClass(KotatsuRuntimePackContract.ENTRYPOINT_CLASS)
		val instance = clazz.getConstructor(Context::class.java).newInstance(context.applicationContext)
		val result = Session(instance, clazz, manifest)
		require(result.protocolVersion() == KotatsuRuntimePackContract.PROTOCOL_VERSION) { "Kotatsu runtime protocol mismatch" }
		require(result.healthCheck()) { "Kotatsu runtime health check failed" }
		return result
	}

	internal class Session(private val target: Any, clazz: Class<*>, val manifest: KotatsuRuntimePackManifest) {
		private val protocol: Method = clazz.getMethod("protocolVersion")
		private val health: Method = clazz.getMethod("healthCheck")
		private val catalog: Method = clazz.getMethod("catalogJson")
		private val list: Method = clazz.getMethod("getListJson", String::class.java, Integer.TYPE, String::class.java, String::class.java)
		private val details: Method = clazz.getMethod("getDetailsJson", String::class.java, String::class.java)
		private val pages: Method = clazz.getMethod("getPagesJson", String::class.java, String::class.java)
		private val pageUrl: Method = clazz.getMethod("getPageUrl", String::class.java, String::class.java)
		private val related: Method = clazz.getMethod("getRelatedJson", String::class.java, String::class.java)
		private val sortOrders: Method = clazz.getMethod("sortOrdersJson", String::class.java)
		private val searchSupported: Method = clazz.getMethod("isSearchSupported", String::class.java)
		private val headers: Method = clazz.getMethod("requestHeadersJson", String::class.java)

		fun protocolVersion() = protocol.invoke(target) as Int
		fun healthCheck() = health.invoke(target) as Boolean
		fun catalog(): List<KotatsuMangaSource> {
			val array = JSONArray(catalog.invoke(target) as String)
			return buildList(array.length()) {
				for (i in 0 until array.length()) {
					val item = array.getJSONObject(i)
					add(KotatsuMangaSource(item.getString("key"), item.optString("title", item.getString("key")), item.optString("locale"), item.optString("contentType", "OTHER"), item.optBoolean("broken")))
				}
			}
		}
		fun getList(source: String, offset: Int, order: String, query: String?) = list.invoke(target, source, offset, order, query.orEmpty()) as String
		fun getDetails(source: String, manga: String) = details.invoke(target, source, manga) as String
		fun getPages(source: String, chapter: String) = pages.invoke(target, source, chapter) as String
		fun getPageUrl(source: String, page: String) = pageUrl.invoke(target, source, page) as String
		fun getRelated(source: String, manga: String) = related.invoke(target, source, manga) as String
		fun sortOrders(source: String): Set<String> = JSONArray(sortOrders.invoke(target, source) as String).let { array -> buildSet { for (i in 0 until array.length()) add(array.getString(i)) } }
		fun isSearchSupported(source: String) = searchSupported.invoke(target, source) as Boolean
		fun requestHeaders(source: String): JSONObject = JSONObject(headers.invoke(target, source) as String)
	}

	companion object {
		private const val KEY_ENABLED = "enabled_sources"
		@Volatile private var activeInstance: KotatsuRuntimeManager? = null
		fun getByName(name: String): KotatsuMangaSource? = activeInstance?.resolveSource(name)
	}
}
