package org.koitharu.kotatsu.lnreader

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.lnreader.js.JsHost
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.lnreader.model.LnPlugin
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks installed LNReader novel plugins on disk and loads them into [JsHost] on demand.
 *
 * Shaped like [org.koitharu.kotatsu.mihon.MihonExtensionManager], including the static
 * [activeInstance] handle, because the `MangaSource(name)` resolver is neither suspending nor
 * DI-aware and still has to map `"LN_<id>"` back to a live source.
 *
 * Deliberately not built on `ExternalExtensionManagerFacade`: that is organised around
 * PackageManager broadcasts, numeric Long ids and per-package language variants, none of which apply
 * here, and hashing string plugin ids into Longs to fit it would be a latent collision bug.
 */
@Singleton
class LnPluginManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val jsHost: JsHost,
) {

	private val loadMutex = Mutex()
	private val loaded = HashSet<String>()

	@Volatile
	private var isInitialized = false

	private val state = MutableStateFlow<List<LnMangaSource>>(emptyList())

	val sources: StateFlow<List<LnMangaSource>> = state

	private val root: File
		get() = File(context.filesDir, DIR_PLUGINS)

	private val catalogFile: File
		get() = File(root, FILE_CATALOG)

	init {
		activeInstance = this
	}

	/**
	 * Publishes installed plugin metadata without booting the JS realm.
	 *
	 * Cold starts use one compact catalog read. Older installs (or a catalog whose directory set no
	 * longer matches disk) fall back to the per-plugin manifest scan once and immediately regenerate
	 * the catalog for subsequent starts.
	 */
	fun initialize() {
		if (isInitialized) return
		isInitialized = true
		state.value = readCatalog()?.takeIf(::isCatalogCurrent) ?: scan()
	}

	private fun scan(): List<LnMangaSource> {
		val result = root.listFiles { file: File -> file.isDirectory }
			?.mapNotNull { dir -> readPlugin(dir)?.let(::LnMangaSource) }
			?.sortedBy { it.displayName.lowercase() }
			.orEmpty()
		state.value = result
		writeCatalog(result)
		return result
	}

	fun getAll(): List<LnMangaSource> = state.value

	fun getById(pluginId: String): LnMangaSource? = state.value.find { it.pluginId == pluginId }

	/** Loads [pluginId]'s code into the JS realm if it is not there yet. Safe to call repeatedly. */
	suspend fun ensureLoaded(pluginId: String) {
		if (pluginId in loaded) return
		loadMutex.withLock {
			if (pluginId in loaded) return
			val code = codeFile(pluginId).takeIf { it.isFile }?.readText()
				?: error("Plugin $pluginId is not installed")
			jsHost.install(pluginId, code)
			loaded.add(pluginId)
		}
	}

	/**
	 * Persists [rawCode] as [pluginId] and returns the metadata the plugin reported. Evaluating it
	 * first means a plugin that throws on load is never written to disk.
	 *
	 * A plugin's exported object carries no `iconUrl` — LNReader's build script writes that into the
	 * index, not the code — and some plugins omit `lang`, so the store row is the authority for both.
	 * [storeId] is kept so the catalog can name the provider.
	 */
	suspend fun install(
		pluginId: String,
		rawCode: String,
		iconUrl: String = "",
		lang: String = "",
		storeId: String? = null,
	): LnPlugin {
		val reported = LnPlugin.fromJson(jsHost.install(pluginId, rawCode))
		val metadata = reported.copy(
			iconUrl = reported.iconUrl.ifEmpty { iconUrl },
			lang = reported.lang.ifEmpty { lang },
			storeId = storeId,
		)
		loadMutex.withLock {
			val dir = File(root, pluginId)
			dir.mkdirs()
			// Stage then rename, like MihonExtensionLoader.installPrivateExtensionFile, so an
			// interrupted write cannot leave a half-truncated plugin behind.
			val staged = File(dir, "$FILE_CODE.new")
			staged.writeText(rawCode)
			if (!staged.renameTo(codeFile(pluginId))) {
				staged.delete()
				error("Could not write plugin $pluginId")
			}
			File(dir, FILE_MANIFEST).writeText(metadata.toJson().toString())
			loaded.add(pluginId)
		}
		scan()
		return metadata
	}

	suspend fun uninstall(pluginId: String) {
		loadMutex.withLock {
			loaded.remove(pluginId)
			File(root, pluginId).deleteRecursively()
		}
		jsHost.uninstall(pluginId)
		scan()
	}

	fun isInstalled(pluginId: String): Boolean = codeFile(pluginId).isFile

	private fun codeFile(pluginId: String) = File(File(root, pluginId), FILE_CODE)

	private fun readPlugin(dir: File): LnPlugin? {
		val manifest = File(dir, FILE_MANIFEST)
		if (!manifest.isFile || !File(dir, FILE_CODE).isFile) return null
		return runCatching { LnPlugin.fromJson(JSONObject(manifest.readText())) }
			.onFailure { Log.w(TAG, "Bad manifest in ${dir.name}", it) }
			.getOrNull()
	}

	private fun readCatalog(): List<LnMangaSource>? {
		val file = catalogFile
		if (!file.isFile) return null
		return runCatching {
			val json = JSONArray(file.readText())
			buildList(json.length()) {
				for (index in 0 until json.length()) {
					add(LnMangaSource(LnPlugin.fromJson(json.getJSONObject(index))))
				}
			}.sortedBy { it.displayName.lowercase() }
		}.onFailure {
			Log.w(TAG, "Bad cached LNReader plugin catalog", it)
		}.getOrNull()
	}

	private fun isCatalogCurrent(cached: List<LnMangaSource>): Boolean {
		val dirs = root.listFiles { file: File -> file.isDirectory && File(file, FILE_CODE).isFile }
			.orEmpty()
		if (dirs.size != cached.size) return false
		val cachedIds = cached.mapTo(HashSet(cached.size)) { it.pluginId }
		return dirs.all { it.name in cachedIds }
	}

	private fun writeCatalog(sources: List<LnMangaSource>) {
		runCatching {
			if (!root.exists() && !root.mkdirs()) return@runCatching
			val json = JSONArray()
			for (source in sources) {
				json.put(source.plugin.toJson())
			}
			val staged = File(root, "$FILE_CATALOG.new")
			staged.writeText(json.toString())
			if (catalogFile.exists() && !catalogFile.delete()) {
				staged.delete()
				return@runCatching
			}
			if (!staged.renameTo(catalogFile)) {
				staged.copyTo(catalogFile, overwrite = true)
				staged.delete()
			}
		}.onFailure {
			Log.w(TAG, "Could not cache LNReader plugin catalog", it)
		}
	}

	companion object {

		private const val TAG = "LnPluginManager"
		private const val DIR_PLUGINS = "lnplugins"
		private const val FILE_CODE = "index.js"
		private const val FILE_MANIFEST = "plugin.json"
		private const val FILE_CATALOG = "catalog.json"

		@Volatile
		private var activeInstance: LnPluginManager? = null

		/**
		 * Whether any plugin is installed, as a plain directory check. Deliberately static: injecting the
		 * manager into a main-thread caller (e.g. WorkScheduleManager during app startup) would build
		 * JsHost and its OkHttp client there, which the network module asserts against.
		 */
		fun hasInstalledPlugins(context: Context): Boolean =
			File(context.filesDir, DIR_PLUGINS).listFiles { file: File -> file.isDirectory }
				?.any { File(it, FILE_CODE).isFile } == true

		/**
		 * The plugin that serves [host], for tagging its network requests with a source. Static for the
		 * same reason as [hasInstalledPlugins]: an OkHttp interceptor must not build the DI graph.
		 *
		 * By host because the plugin realm shares one global `fetch`, so a request carries no plugin id.
		 */
		fun findBySiteHost(host: String): LnMangaSource? = activeInstance?.run {
			initialize()
			val target = host.removePrefix("www.")
			getAll().firstOrNull { source ->
				val site = source.plugin.site.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return@firstOrNull false
				// Either direction: plugins list either the bare domain or a www/subdomain of it, while
				// requests go to both that and sibling hosts (cdn, api).
				target == site || target.endsWith(".$site") || site.endsWith(".$target")
			}
		}

		/** Exact source ownership for requests routed through a plugin-scoped fetch shim. */
		fun findByPluginId(pluginId: String): LnMangaSource? = activeInstance?.run {
			initialize()
			getById(pluginId)
		}

		/** Resolves a stored `"LN_<id>"` source name without DI. Null when nothing is installed yet. */
		fun getByName(name: String): LnMangaSource? = activeInstance?.run {
			initialize()
			getById(name.removePrefix("LN_"))
		}
	}
}
