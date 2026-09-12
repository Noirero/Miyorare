package org.koitharu.kotatsu.tsuki

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.WorkerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import java.io.File
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit installer/updater for optional Tsuki plugins.
 *
 * There is deliberately no background polling here. Network access happens only when the user
 * asks to install/check/update a plugin. Known providers use exact release assets. Custom GitHub
 * repositories are accepted only when their latest release exposes exactly one JAR asset, avoiding
 * guesswork about which executable artifact should be trusted. Local imports pass through the same
 * validator and atomic rollback path in [TsukiPluginManager].
 */
@Singleton
class TsukiPluginInstaller @Inject constructor(
	@ApplicationContext private val context: Context,
	private val pluginManager: TsukiPluginManager,
	@BaseHttpClient private val httpClient: OkHttpClient,
) {

	data class RemoteRelease(
		val provider: TsukiPluginProvider,
		val tag: String,
		val assetName: String,
		val downloadUrl: String,
		val size: Long,
		val sha256: String?,
	)

	fun isStageAvailable(provider: TsukiPluginProvider): Boolean = when (provider) {
		TsukiPluginProvider.MIYORARE,
		TsukiPluginProvider.UMA,
		TsukiPluginProvider.GEKKOUSHI,
		TsukiPluginProvider.CUSTOM,
		-> true
	}

	suspend fun latestRelease(provider: TsukiPluginProvider): RemoteRelease = withContext(Dispatchers.IO) {
		require(provider != TsukiPluginProvider.MIYORARE) {
			"Miyorare has multiple official packs; select a plugin id"
		}
		val config = requireNotNull(knownProvider(provider)) { "No official repository for $provider" }
		requireStageAvailable(config)
		fetchLatestRelease(config)
	}

	fun officialMiyorarePacks(): List<MiyorareOfficialSourcePack> = MiyorareOfficialSourcePacks.packs

	suspend fun latestMiyorareRelease(pluginId: String): RemoteRelease = withContext(Dispatchers.IO) {
		val config = requireNotNull(miyorarePackConfig(pluginId)) { "Unknown official Miyorare source pack: $pluginId" }
		requireStageAvailable(config)
		fetchLatestRelease(config)
	}

	fun supportsRemoteUpdate(plugin: TsukiPluginDescriptor): Boolean =
		isStageAvailable(plugin.provider) && configForPlugin(plugin) != null

	suspend fun checkForUpdate(plugin: TsukiPluginDescriptor): RemoteRelease? = withContext(Dispatchers.IO) {
		if (!isStageAvailable(plugin.provider)) return@withContext null
		val config = configForPlugin(plugin) ?: return@withContext null
		val latest = fetchLatestRelease(config)
		latest.takeUnless { release ->
			release.tag == plugin.version &&
				(release.sha256 == null || release.sha256.equals(plugin.sha256, ignoreCase = true))
		}
	}

	suspend fun installLatest(provider: TsukiPluginProvider): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		require(provider != TsukiPluginProvider.MIYORARE) {
			"Miyorare has multiple official packs; select a plugin id"
		}
		val config = requireNotNull(knownProvider(provider)) { "No official repository for $provider" }
		installFromConfig(config)
	}

	suspend fun installLatestMiyorare(pluginId: String): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		val config = requireNotNull(miyorarePackConfig(pluginId)) { "Unknown official Miyorare source pack: $pluginId" }
		installFromConfig(config)
	}

	/** Install the single JAR asset from a public GitHub repository's latest release. */
	suspend fun installFromGitHubRepository(input: String): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		installFromConfig(customGitHubConfig(input))
	}

	/** Manual update/reinstall using the exact remote origin already persisted for this plugin. */
	suspend fun installLatest(plugin: TsukiPluginDescriptor): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		val config = requireNotNull(configForPlugin(plugin)) { "Plugin has no supported remote repository" }
		installFromConfig(config)
	}

	suspend fun installLocal(uri: Uri): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		val displayName = queryDisplayName(uri).orEmpty().ifBlank { "plugin.jar" }
		val config = inferLocalProvider(displayName)
		requireStageAvailable(config)
		val temp = File.createTempFile("tsuki-local-", ".jar", context.cacheDir)
		try {
			val input = context.contentResolver.openInputStream(uri) ?: error("Could not open selected JAR")
			input.use { copyBounded(it, temp, MAX_PLUGIN_BYTES) }
			pluginManager.installLocalJar(
				sourceFile = temp,
				request = TsukiPluginManager.InstallRequest(
					pluginId = config.pluginId,
					displayName = config.displayName,
					provider = config.provider,
					origin = "local://import/${Uri.encode(displayName)}",
				),
			)
		} finally {
			temp.delete()
		}
	}

	@WorkerThread
	private fun installFromConfig(config: ProviderConfig): TsukiPluginDescriptor {
		requireStageAvailable(config)
		val release = fetchLatestRelease(config)
		val temp = File.createTempFile("tsuki-${config.pluginId}-", ".jar", context.cacheDir)
		try {
			downloadRelease(release, temp)
			return pluginManager.installLocalJar(
				sourceFile = temp,
				request = TsukiPluginManager.InstallRequest(
					pluginId = config.pluginId,
					displayName = config.displayName,
					provider = config.provider,
					origin = config.repositoryUrl,
					version = release.tag,
				),
			)
		} finally {
			temp.delete()
		}
	}

	private fun requireStageAvailable(config: ProviderConfig) {
		require(isStageAvailable(config.provider)) {
			"${config.displayName} support is not available in this plugin stage"
		}
	}

	@WorkerThread
	private fun fetchLatestRelease(config: ProviderConfig): RemoteRelease {
		if (config.releaseTagPrefix != null) {
			return fetchLatestPrefixedRelease(config)
		}
		val request = Request.Builder()
			.url("https://api.github.com/repos/${config.repository}/releases/latest")
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.build()
		httpClient.newCall(request).execute().use { response ->
			require(response.isSuccessful) { "GitHub returned HTTP ${response.code}" }
			return parseRelease(config, JSONObject(response.body.string()))
		}
	}

	@WorkerThread
	private fun fetchLatestPrefixedRelease(config: ProviderConfig): RemoteRelease {
		val prefix = requireNotNull(config.releaseTagPrefix)
		val request = Request.Builder()
			.url("https://api.github.com/repos/${config.repository}/releases?per_page=30")
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.build()
		httpClient.newCall(request).execute().use { response ->
			require(response.isSuccessful) { "GitHub returned HTTP ${response.code}" }
			val releases = JSONArray(response.body.string())
			val candidates = ArrayList<Pair<SourcePackVersion, JSONObject>>()
			for (i in 0 until releases.length()) {
				val release = releases.getJSONObject(i)
				if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
				val tag = release.optString("tag_name")
				if (!tag.startsWith(prefix)) continue
				val version = MiyorareOfficialSourcePacks.versionFromTag(tag) ?: continue
				candidates += version to release
			}
			val selected = candidates.maxByOrNull { it.first }?.second
				?: error("No stable official Miyorare source-pack release is published yet")
			return parseRelease(config, selected)
		}
	}

	private fun parseRelease(config: ProviderConfig, root: JSONObject): RemoteRelease {
		val tag = root.getString("tag_name").trim().also { require(it.isNotEmpty()) }
		config.releaseTagPrefix?.let { prefix ->
			require(tag.startsWith(prefix) && MiyorareOfficialSourcePacks.versionFromTag(tag) != null) {
				"Unexpected official source-pack release tag: $tag"
			}
		}
		val assets = root.getJSONArray("assets")
		val candidates = ArrayList<JSONObject>()
		for (i in 0 until assets.length()) {
			val asset = assets.getJSONObject(i)
			val name = asset.optString("name")
			if (config.assetName != null) {
				if (name == config.assetName) candidates += asset
			} else if (name.endsWith(".jar", ignoreCase = true)) {
				candidates += asset
			}
		}
		if (config.assetName != null) {
			require(candidates.size == 1) { "Release $tag does not contain exactly one ${config.assetName}" }
		} else {
			require(candidates.size == 1) {
				"Custom GitHub release must contain exactly one .jar asset; found ${candidates.size}"
			}
		}
		val asset = candidates.single()
		val name = asset.getString("name")
		val size = asset.optLong("size", -1L)
		require(size in 1..MAX_PLUGIN_BYTES) { "Plugin asset has an invalid size: $size" }
		val url = asset.getString("browser_download_url")
		require(url.startsWith("https://github.com/${config.repository}/releases/download/$tag/")) {
			"Unexpected plugin download origin"
		}
		val digest = if (config.requireSha256) {
			MiyorareOfficialSourcePacks.normalizeSha256Digest(asset.optString("digest")).also {
				require(it != null) { "Official Miyorare source pack is missing a valid GitHub SHA-256 digest" }
			}
		} else {
			asset.optString("digest")
				.takeIf { it.startsWith("sha256:", ignoreCase = true) }
				?.substringAfter(':')
				?.lowercase(Locale.ROOT)
		}
		return RemoteRelease(config.provider, tag, name, url, size, digest)
	}

	@WorkerThread
	private fun downloadRelease(release: RemoteRelease, target: File) {
		val request = Request.Builder().url(release.downloadUrl).build()
		httpClient.newCall(request).execute().use { response ->
			require(response.isSuccessful) { "Plugin download returned HTTP ${response.code}" }
			val contentLength = response.body.contentLength()
			if (contentLength >= 0) {
				require(contentLength == release.size) { "Plugin download size changed" }
			}
			response.body.byteStream().use { copyBounded(it, target, MAX_PLUGIN_BYTES) }
		}
		require(target.length() == release.size) { "Plugin download was truncated" }
		val validated = TsukiPluginValidator.validate(target).getOrThrow()
		release.sha256?.let { expected ->
			require(validated.sha256.equals(expected, ignoreCase = true)) { "Plugin SHA-256 does not match GitHub release" }
		}
	}

	@WorkerThread
	private fun copyBounded(input: InputStream, target: File, limit: Long) {
		target.outputStream().buffered().use { output ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			var total = 0L
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				if (read == 0) continue
				total += read
				require(total <= limit) { "Plugin exceeds ${limit / (1024 * 1024)} MiB safety limit" }
				output.write(buffer, 0, read)
			}
			require(total > 0) { "Plugin file is empty" }
		}
	}

	private fun queryDisplayName(uri: Uri): String? = runCatching {
		context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
			if (!cursor.moveToFirst()) return@use null
			cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
		}
	}.getOrNull()

	private fun inferLocalProvider(fileName: String): ProviderConfig {
		val baseName = fileName.substringBeforeLast('.').lowercase(Locale.ROOT)
		if (baseName.matches(Regex("^uma(?:[-_.].*)?$"))) {
			return requireNotNull(knownProvider(TsukiPluginProvider.UMA))
		}
		if (baseName.matches(Regex("^gekkoushi(?:[-_.].*)?$"))) {
			return requireNotNull(knownProvider(TsukiPluginProvider.GEKKOUSHI))
		}
		val id = sanitizePluginId(baseName.ifBlank { "custom" })
		return ProviderConfig(
			provider = TsukiPluginProvider.CUSTOM,
			pluginId = id,
			displayName = fileName.substringBeforeLast('.').ifBlank { "Custom Tsuki plugin" },
			repository = "",
			assetName = fileName,
		)
	}

	private fun customGitHubConfig(input: String): ProviderConfig {
		val repository = parseGitHubRepository(input)
		if (repository.equals("InvalidDavid/UMA", ignoreCase = true)) {
			return requireNotNull(knownProvider(TsukiPluginProvider.UMA))
		}
		if (repository.equals("Gekkoushi/plugin", ignoreCase = true)) {
			return requireNotNull(knownProvider(TsukiPluginProvider.GEKKOUSHI))
		}
		val repoName = repository.substringAfter('/')
		return ProviderConfig(
			provider = TsukiPluginProvider.CUSTOM,
			pluginId = sanitizePluginId(repository.replace('/', '-').lowercase(Locale.ROOT)),
			displayName = repoName,
			repository = repository,
			assetName = null,
		)
	}

	private fun configForPlugin(plugin: TsukiPluginDescriptor): ProviderConfig? {
		if (plugin.provider == TsukiPluginProvider.MIYORARE) {
			return miyorarePackConfig(plugin.pluginId)
		}
		knownProvider(plugin.provider)?.let { return it }
		if (plugin.provider != TsukiPluginProvider.CUSTOM || !plugin.origin.startsWith("https://github.com/")) {
			return null
		}
		return runCatching {
			customGitHubConfig(plugin.origin).copy(
				pluginId = plugin.pluginId,
				displayName = plugin.displayName,
			)
		}.getOrNull()
	}

	private fun parseGitHubRepository(input: String): String {
		val value = input.trim().removeSuffix("/")
		require(value.isNotEmpty()) { "GitHub repository is empty" }
		val repository = if (value.startsWith("https://", ignoreCase = true)) {
			val uri = Uri.parse(value)
			require(uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true)) {
				"Only https://github.com repositories are supported"
			}
			val segments = uri.pathSegments.filter { it.isNotBlank() }
			require(segments.size == 2) { "Use a repository URL like https://github.com/owner/repo" }
			"${segments[0]}/${segments[1].removeSuffix(".git")}"
		} else {
			value.removeSuffix(".git")
		}
		val parts = repository.split('/')
		require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Use owner/repo" }
		require(parts.all { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }) { "Invalid GitHub repository name" }
		return "${parts[0]}/${parts[1]}"
	}

	private fun sanitizePluginId(value: String): String = value
		.replace(Regex("[^a-zA-Z0-9._-]"), "-")
		.trim('-')
		.take(64)
		.ifBlank { "custom" }

	private fun miyorarePackConfig(pluginId: String): ProviderConfig? {
		val pack = MiyorareOfficialSourcePacks.find(pluginId) ?: return null
		return ProviderConfig(
			provider = TsukiPluginProvider.MIYORARE,
			pluginId = pack.pluginId,
			displayName = pack.displayName,
			repository = MiyorareOfficialSourcePacks.REPOSITORY,
			assetName = pack.assetName,
			releaseTagPrefix = MiyorareOfficialSourcePacks.RELEASE_TAG_PREFIX,
			requireSha256 = true,
		)
	}

	private fun knownProvider(provider: TsukiPluginProvider): ProviderConfig? = when (provider) {
		TsukiPluginProvider.MIYORARE -> null
		TsukiPluginProvider.UMA -> ProviderConfig(
			provider = provider,
			pluginId = "uma",
			displayName = "UMA",
			repository = "InvalidDavid/UMA",
			assetName = "uma.jar",
		)
		TsukiPluginProvider.GEKKOUSHI -> ProviderConfig(
			provider = provider,
			pluginId = "gekkoushi",
			displayName = "Gekkoushi",
			repository = "Gekkoushi/plugin",
			assetName = "gekkoushi.jar",
		)
		TsukiPluginProvider.CUSTOM -> null
	}

	private data class ProviderConfig(
		val provider: TsukiPluginProvider,
		val pluginId: String,
		val displayName: String,
		val repository: String,
		val assetName: String?,
		val releaseTagPrefix: String? = null,
		val requireSha256: Boolean = false,
	) {
		val repositoryUrl: String
			get() = "https://github.com/$repository"
	}

	private companion object {
		const val MAX_PLUGIN_BYTES = 32L * 1024L * 1024L
	}
}
