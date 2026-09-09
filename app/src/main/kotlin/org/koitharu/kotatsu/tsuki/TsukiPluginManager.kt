package org.koitharu.kotatsu.tsuki

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.tsuki.model.TsukiCompatibilityStatus
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiSourceIdentity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional Tsuki/Usagi plugin registry. Construction is inert: no directory scan, class loader or
 * network work happens until a Tsuki-facing call explicitly reaches this manager.
 */
@Singleton
class TsukiPluginManager @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	data class InstallRequest(
		val pluginId: String,
		val displayName: String,
		val provider: TsukiPluginProvider,
		/** Human-auditable origin, e.g. https://github.com/InvalidDavid/UMA or local://import. */
		val origin: String,
		val version: String? = null,
	)

	private val state = MutableStateFlow<List<TsukiPluginDescriptor>>(emptyList())
	val plugins: StateFlow<List<TsukiPluginDescriptor>> = state

	@Volatile
	private var initialized = false

	private val root: File
		get() = File(context.filesDir, DIR_PLUGINS)

	init {
		activeInstance = this
	}

	/** Metadata-only scan. JARs are never opened or class-loaded here. */
	fun initialize() {
		if (initialized) return
		synchronized(this) {
			if (initialized) return
			initialized = true
			refreshMetadata()
		}
	}

	fun getPlugins(): List<TsukiPluginDescriptor> {
		initialize()
		return state.value
	}

	/** All usable sources from enabled plugins, including sources hidden from Explore/global search. */
	fun getAvailableSources(): List<TsukiMangaSource> = getPlugins()
		.asSequence()
		.filter(::isUsablePlugin)
		.flatMap { plugin ->
			plugin.sources.asSequence().filterNot { it.isBroken }.map { TsukiMangaSource(plugin, it) }
		}
		.toList()

	/** Only explicitly enabled sources participate in source catalogs and global search. */
	fun getEnabledSources(): List<TsukiMangaSource> = getAvailableSources().filter { it.isEnabled }

	/**
	 * Resolve persisted content independently from catalog participation. Disabling a source hides it
	 * from Explore/global search but must not orphan Favourites, History, Details or Downloads.
	 */
	fun resolveSource(storedName: String): TsukiMangaSource? {
		val identity = TsukiSourceIdentity.parse(storedName) ?: return null
		val plugin = findPlugin(identity.provider, identity.pluginId)?.takeIf(::isUsablePlugin) ?: return null
		return plugin.sources.firstOrNull { it.name == identity.sourceName && !it.isBroken }
			?.let { TsukiMangaSource(plugin, it) }
	}

	fun findPlugin(provider: TsukiPluginProvider, pluginId: String): TsukiPluginDescriptor? =
		getPlugins().firstOrNull { it.provider == provider && it.pluginId == pluginId }

	/**
	 * Validates and probes a local dexed JAR before replacing an installed plugin. The previous
	 * working directory is retained until the new directory is fully staged, providing rollback on
	 * interrupted/failed updates.
	 */
	@WorkerThread
	fun installLocalJar(sourceFile: File, request: InstallRequest): TsukiPluginDescriptor {
		val pluginId = validatePluginId(request.pluginId)
		val validated = TsukiPluginValidator.validate(sourceFile).getOrThrow()
		val declaredCompatibility = validated.declaredApi?.let {
			TsukiPluginValidator.compatibility(it, probeSucceeded = true)
		}
		if (declaredCompatibility?.status == TsukiCompatibilityStatus.INCOMPATIBLE) {
			error(declaredCompatibility.reason ?: "Unsupported Tsuki API")
		}

		root.mkdirs()
		val target = pluginDirectory(request.provider, pluginId)
		migrateFoundationDirectoryIfNeeded(request.provider, pluginId, target)
		val previous = readPlugin(target)
		val staging = File(root, ".staging-${target.name}-${System.nanoTime()}")
		require(staging.mkdirs()) { "Could not create plugin staging directory" }
		try {
			val jar = File(staging, FILE_PLUGIN)
			sourceFile.copyTo(jar, overwrite = false)
			require(jar.setReadOnly()) { "Could not make staged plugin read-only" }

			val probe = runCatching {
				TsukiPluginProbe.probe(jar, context.codeCacheDir, context.classLoader)
			}
			val compatibility = TsukiPluginValidator.compatibility(
				requiredApi = validated.declaredApi,
				probeSucceeded = probe.isSuccess,
				probeError = probe.exceptionOrNull()?.message,
			)
			require(compatibility.isCompatible) { compatibility.reason ?: "Plugin is not compatible" }
			val probed = probe.getOrThrow()
			val sourceNames = probed.sources.asSequence().map { it.name }.toSet()
			val descriptor = TsukiPluginDescriptor(
				pluginId = pluginId,
				displayName = request.displayName.ifBlank { pluginId },
				provider = request.provider,
				version = request.version ?: validated.declaredVersion ?: "unknown",
				origin = request.origin,
				sha256 = validated.sha256,
				fileSize = validated.fileSize,
				lastModified = validated.lastModified,
				requiredApi = validated.declaredApi,
				compatibility = compatibility.status,
				state = previous?.state?.takeUnless { it == TsukiPluginState.BROKEN } ?: TsukiPluginState.ENABLED,
				sources = probed.sources,
				// Preserve explicit user choices across updates. New sources remain disabled.
				enabledSourceNames = previous?.enabledSourceNames.orEmpty().intersect(sourceNames),
			)
			File(staging, FILE_MANIFEST).writeText(descriptor.toJson().toString())

			synchronized(this) {
				val backup = File(root, ".backup-${target.name}")
				backup.deleteRecursively()
				if (target.exists()) require(target.renameTo(backup)) { "Could not stage previous plugin for rollback" }
				if (!staging.renameTo(target)) {
					if (backup.exists()) backup.renameTo(target)
					error("Could not install plugin")
				}
				backup.deleteRecursively()
				initialized = true
				refreshMetadata()
			}
			return descriptor
		} finally {
			staging.deleteRecursively()
		}
	}

	fun setEnabled(provider: TsukiPluginProvider, pluginId: String, enabled: Boolean) {
		initialize()
		synchronized(this) {
			val dir = pluginDirectory(provider, validatePluginId(pluginId))
			val current = readPlugin(dir) ?: return
			val updated = current.copy(state = if (enabled) TsukiPluginState.ENABLED else TsukiPluginState.DISABLED)
			writeManifest(dir, updated)
			refreshMetadata()
		}
	}

	fun setSourceEnabled(identity: TsukiSourceIdentity, enabled: Boolean) {
		initialize()
		synchronized(this) {
			val dir = pluginDirectory(identity.provider, validatePluginId(identity.pluginId))
			val current = readPlugin(dir) ?: return
			if (current.sources.none { it.name == identity.sourceName && !it.isBroken }) return
			val names = current.enabledSourceNames.toMutableSet()
			if (enabled) names += identity.sourceName else names -= identity.sourceName
			writeManifest(dir, current.copy(enabledSourceNames = names))
			refreshMetadata()
		}
	}

	fun remove(provider: TsukiPluginProvider, pluginId: String) {
		initialize()
		synchronized(this) {
			pluginDirectory(provider, validatePluginId(pluginId)).deleteRecursively()
			refreshMetadata()
		}
	}

	internal fun pluginJar(plugin: TsukiPluginDescriptor): File? {
		initialize()
		val canonical = File(pluginDirectory(plugin.provider, plugin.pluginId), FILE_PLUGIN)
		if (canonical.isFile) return canonical
		// Compatibility with the first foundation commit, before provider-scoped directories existed.
		return File(File(root, plugin.pluginId), FILE_PLUGIN).takeIf { it.isFile }
	}

	private fun refreshMetadata() {
		state.value = root.listFiles { file -> file.isDirectory && !file.name.startsWith('.') }
			?.mapNotNull(::readPlugin)
			?.distinctBy { Pair(it.provider, it.pluginId) }
			?.sortedBy { it.displayName.lowercase() }
			.orEmpty()
	}

	private fun readPlugin(dir: File): TsukiPluginDescriptor? {
		val jar = File(dir, FILE_PLUGIN)
		val manifest = File(dir, FILE_MANIFEST)
		if (!jar.isFile || !manifest.isFile) return null
		return runCatching { descriptorFromJson(JSONObject(manifest.readText())) }
			.onFailure { Log.w(TAG, "Invalid Tsuki plugin metadata in ${dir.name}", it) }
			.getOrNull()
	}

	private fun writeManifest(dir: File, descriptor: TsukiPluginDescriptor) {
		val staged = File(dir, "$FILE_MANIFEST.new")
		staged.writeText(descriptor.toJson().toString())
		val target = File(dir, FILE_MANIFEST)
		if (target.exists() && !target.delete()) {
			staged.delete()
			error("Could not update plugin metadata")
		}
		if (!staged.renameTo(target)) error("Could not commit plugin metadata")
	}

	private fun pluginDirectory(provider: TsukiPluginProvider, pluginId: String) =
		File(root, "${provider.wireName.lowercase()}__$pluginId")

	private fun migrateFoundationDirectoryIfNeeded(
		provider: TsukiPluginProvider,
		pluginId: String,
		target: File,
	) {
		if (target.exists()) return
		val legacy = File(root, pluginId)
		if (!legacy.isDirectory) return
		val descriptor = readPlugin(legacy) ?: return
		if (descriptor.provider == provider && descriptor.pluginId == pluginId) {
			legacy.renameTo(target)
		}
	}

	private fun validatePluginId(value: String): String {
		require(value.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid plugin id" }
		return value
	}

	private fun isUsablePlugin(plugin: TsukiPluginDescriptor): Boolean =
		plugin.state == TsukiPluginState.ENABLED && plugin.compatibility == TsukiCompatibilityStatus.COMPATIBLE

	private fun TsukiPluginDescriptor.toJson() = JSONObject()
		.put("pluginId", pluginId)
		.put("displayName", displayName)
		.put("provider", provider.wireName)
		.put("version", version)
		.put("origin", origin)
		.put("sha256", sha256)
		.put("fileSize", fileSize)
		.put("lastModified", lastModified)
		.put("requiredApi", requiredApi ?: JSONObject.NULL)
		.put("compatibility", compatibility.name)
		.put("state", state.name)
		.put("failureReason", failureReason ?: JSONObject.NULL)
		.put("enabledSources", JSONArray().also { array -> enabledSourceNames.sorted().forEach(array::put) })
		.put("sources", JSONArray().also { array ->
			sources.forEach { source ->
				array.put(JSONObject()
					.put("name", source.name)
					.put("title", source.title)
					.put("locale", source.locale)
					.put("contentType", source.contentType)
					.put("isBroken", source.isBroken))
			}
		})

	private fun descriptorFromJson(json: JSONObject): TsukiPluginDescriptor {
		val sourcesJson = json.optJSONArray("sources") ?: JSONArray()
		val sources = ArrayList<TsukiSourceDescriptor>(sourcesJson.length())
		for (i in 0 until sourcesJson.length()) {
			val item = sourcesJson.getJSONObject(i)
			sources += TsukiSourceDescriptor(
				name = item.getString("name"),
				title = item.optString("title").ifBlank { item.getString("name") },
				locale = item.optString("locale"),
				contentType = item.optString("contentType", "OTHER"),
				isBroken = item.optBoolean("isBroken"),
			)
		}
		val enabledJson = json.optJSONArray("enabledSources")
		val enabledSources = buildSet {
			if (enabledJson != null) {
				for (i in 0 until enabledJson.length()) enabledJson.optString(i).takeIf { it.isNotBlank() }?.let(::add)
			}
		}
		return TsukiPluginDescriptor(
			pluginId = json.getString("pluginId"),
			displayName = json.optString("displayName").ifBlank { json.getString("pluginId") },
			provider = TsukiPluginProvider.fromWireName(json.optString("provider")),
			version = json.optString("version", "unknown"),
			origin = json.optString("origin"),
			sha256 = json.getString("sha256"),
			fileSize = json.optLong("fileSize"),
			lastModified = json.optLong("lastModified"),
			requiredApi = json.optString("requiredApi").takeIf { it.isNotBlank() },
			compatibility = runCatching { TsukiCompatibilityStatus.valueOf(json.getString("compatibility")) }
				.getOrDefault(TsukiCompatibilityStatus.INVALID),
			state = runCatching { TsukiPluginState.valueOf(json.getString("state")) }
				.getOrDefault(TsukiPluginState.BROKEN),
			sources = sources,
			enabledSourceNames = enabledSources.intersect(sources.asSequence().map { it.name }.toSet()),
			failureReason = json.optString("failureReason").takeIf { it.isNotBlank() },
		)
	}

	companion object {
		private const val TAG = "TsukiPluginManager"
		private const val DIR_PLUGINS = "tsuki_plugins"
		private const val FILE_PLUGIN = "plugin.jar"
		private const val FILE_MANIFEST = "plugin.json"

		@Volatile
		private var activeInstance: TsukiPluginManager? = null

		fun hasInstalledPlugins(context: Context): Boolean =
			File(context.filesDir, DIR_PLUGINS).listFiles { file -> file.isDirectory && !file.name.startsWith('.') }
				?.any { File(it, FILE_PLUGIN).isFile && File(it, FILE_MANIFEST).isFile } == true

		/** Synchronous resolver used by DB mapping before a DI-aware repository exists. */
		fun getByName(name: String): TsukiMangaSource? = activeInstance?.resolveSource(name)
	}
}
