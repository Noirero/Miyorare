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
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import org.koitharu.kotatsu.tsuki.model.TsukiSourceIdentity
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

	private data class MiyorarePackRelease(
		val tag: String,
		val repository: String,
		val releases: List<RemoteRelease>,
		val legacySingleJar: Boolean,
	)

	private data class PreviousPlugin(
		val descriptor: TsukiPluginDescriptor,
		val jar: File,
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

	/** Representative release for callers that only need version/availability information. */
	suspend fun latestMiyorareRelease(pluginId: String): RemoteRelease = withContext(Dispatchers.IO) {
		val pack = requireNotNull(MiyorareOfficialSourcePacks.find(pluginId)) {
			"Unknown official Miyorare source pack: $pluginId"
		}
		fetchLatestMiyorarePackRelease(pack).releases.first()
	}

	/** Official Miyorare shards are updated only through their logical ID/EN pack button. */
	fun supportsRemoteUpdate(plugin: TsukiPluginDescriptor): Boolean =
		plugin.provider != TsukiPluginProvider.MIYORARE &&
			isStageAvailable(plugin.provider) && configForPlugin(plugin) != null

	suspend fun checkForUpdate(plugin: TsukiPluginDescriptor): RemoteRelease? = withContext(Dispatchers.IO) {
		if (!isStageAvailable(plugin.provider)) return@withContext null
		if (plugin.provider == TsukiPluginProvider.MIYORARE) {
			val pack = MiyorareOfficialSourcePacks.findByInstalledPluginId(plugin.pluginId) ?: return@withContext null
			val latest = fetchLatestMiyorarePackRelease(pack)
			return@withContext latest.releases.first().takeUnless { miyorarePackIsCurrent(pack, latest) }
		}
		val config = configForPlugin(plugin) ?: return@withContext null
		val latest = fetchLatestRelease(config)
		latest.takeUnless { release -> releaseMatches(plugin, release) }
	}

	/** True when the logical official pack is missing or any required shard differs from latest. */
	suspend fun hasMiyorarePackUpdate(pluginId: String): Boolean = withContext(Dispatchers.IO) {
		val pack = requireNotNull(MiyorareOfficialSourcePacks.find(pluginId)) {
			"Unknown official Miyorare source pack: $pluginId"
		}
		!miyorarePackIsCurrent(pack, fetchLatestMiyorarePackRelease(pack))
	}

	suspend fun installLatest(provider: TsukiPluginProvider): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		require(provider != TsukiPluginProvider.MIYORARE) {
			"Miyorare has multiple official packs; select a plugin id"
		}
		val config = requireNotNull(knownProvider(provider)) { "No official repository for $provider" }
		installFromConfig(config)
	}

	/**
	 * Installs one logical Miyorare pack. New releases consist of independent UMA + Gekkoushi JARs;
	 * legacy one-JAR releases remain installable until a shard release is published. All new shard
	 * bytes are downloaded and validated before the first installed plugin is replaced. If a later
	 * shard commit fails, every already-replaced shard is rolled back to its previous JAR/state.
	 */
	suspend fun installLatestMiyorare(pluginId: String): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		val pack = requireNotNull(MiyorareOfficialSourcePacks.find(pluginId)) {
			"Unknown official Miyorare source pack: $pluginId"
		}
		val installed = installMiyorarePack(pack, fetchLatestMiyorarePackRelease(pack))
		installed.firstOrNull { it.pluginId == pack.pluginId } ?: installed.first()
	}

	/** Install the single JAR asset from a public GitHub repository's latest release. */
	suspend fun installFromGitHubRepository(input: String): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		installFromConfig(customGitHubConfig(input))
	}

	/** Manual update/reinstall using the exact remote origin already persisted for this plugin. */
	suspend fun installLatest(plugin: TsukiPluginDescriptor): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		if (plugin.provider == TsukiPluginProvider.MIYORARE) {
			val pack = requireNotNull(MiyorareOfficialSourcePacks.findByInstalledPluginId(plugin.pluginId)) {
				"Unknown official Miyorare source pack: ${plugin.pluginId}"
			}
			return@withContext installMiyorarePack(pack, fetchLatestMiyorarePackRelease(pack))
				.firstOrNull { it.pluginId == plugin.pluginId }
				?: error("Updated Miyorare pack did not contain ${plugin.pluginId}")
		}
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
			return installDownloadedRelease(config, release, temp)
		} finally {
			temp.delete()
		}
	}

	@WorkerThread
	private fun installMiyorarePack(
		pack: MiyorareOfficialSourcePack,
		release: MiyorarePackRelease,
	): List<TsukiPluginDescriptor> {
		if (release.legacySingleJar) {
			val config = miyorareLegacyConfig(pack, release.repository)
			val remote = release.releases.single()
			val temp = File.createTempFile("tsuki-${pack.pluginId}-legacy-", ".jar", context.cacheDir)
			try {
				downloadRelease(remote, temp)
				return listOf(installDownloadedRelease(config, remote, temp))
			} finally {
				temp.delete()
			}
		}

		require(release.releases.size == pack.shards.size) { "Official Miyorare shard release is incomplete" }
		val configs = pack.shards.map { shard -> miyorareShardConfig(pack, shard, release.repository) }
		val staged = configs.map { config ->
			File.createTempFile("tsuki-${config.pluginId}-stage-", ".jar", context.cacheDir)
		}
		val snapshots = LinkedHashMap<String, PreviousPlugin?>()
		val installedNow = ArrayList<String>(configs.size)
		try {
			// Fail before touching installed state if either network asset, checksum, or plugin container is bad.
			for (index in staged.indices) {
				downloadRelease(release.releases[index], staged[index])
			}

			for (config in configs) {
				val previous = pluginManager.findPlugin(TsukiPluginProvider.MIYORARE, config.pluginId)
				val previousJar = previous?.let(pluginManager::pluginJar)
				snapshots[config.pluginId] = if (previous != null && previousJar != null && previousJar.isFile) {
					val copy = File.createTempFile("tsuki-${config.pluginId}-rollback-", ".jar", context.cacheDir)
					previousJar.copyTo(copy, overwrite = true)
					PreviousPlugin(previous, copy)
				} else {
					null
				}
			}

			val result = ArrayList<TsukiPluginDescriptor>(configs.size)
			for (index in configs.indices) {
				val installed = installDownloadedRelease(configs[index], release.releases[index], staged[index])
				installedNow += configs[index].pluginId
				result += installed
			}
			return result
		} catch (error: Throwable) {
			for (pluginId in installedNow.asReversed()) {
				val snapshot = snapshots[pluginId]
				if (snapshot == null) {
					runCatching { pluginManager.remove(TsukiPluginProvider.MIYORARE, pluginId) }
					continue
				}
				runCatching {
					val old = snapshot.descriptor
					pluginManager.installLocalJar(
						sourceFile = snapshot.jar,
						request = TsukiPluginManager.InstallRequest(
							pluginId = old.pluginId,
							displayName = old.displayName,
							provider = old.provider,
							origin = old.origin,
							version = old.version,
						),
					)
					val states = old.sources.associate { source ->
						TsukiSourceIdentity(old.provider, old.pluginId, source.name) to
							(source.name in old.enabledSourceNames)
					}
					pluginManager.setSourceStates(states)
					pluginManager.setEnabled(
						old.provider,
						old.pluginId,
						old.state == TsukiPluginState.ENABLED,
					)
				}
			}
			throw error
		} finally {
			staged.forEach(File::delete)
			snapshots.values.filterNotNull().forEach { it.jar.delete() }
		}
	}

	private fun miyorarePackIsCurrent(
		pack: MiyorareOfficialSourcePack,
		release: MiyorarePackRelease,
	): Boolean {
		val installed = pluginManager.getPlugins().filter { it.provider == TsukiPluginProvider.MIYORARE }
		if (release.legacySingleJar) {
			val current = installed.firstOrNull { it.pluginId == pack.pluginId } ?: return false
			return releaseMatches(current, release.releases.single())
		}
		return pack.shards.indices.all { index ->
			val shard = pack.shards[index]
			val current = installed.firstOrNull { it.pluginId == shard.pluginId } ?: return@all false
			releaseMatches(current, release.releases[index])
		}
	}

	private fun installDownloadedRelease(
		config: ProviderConfig,
		release: RemoteRelease,
		file: File,
	): TsukiPluginDescriptor = pluginManager.installLocalJar(
		sourceFile = file,
		request = TsukiPluginManager.InstallRequest(
			pluginId = config.pluginId,
			displayName = config.displayName,
			provider = config.provider,
			origin = config.repositoryUrl,
			version = release.tag,
		),
	)

	private fun releaseMatches(plugin: TsukiPluginDescriptor, release: RemoteRelease): Boolean =
		release.tag == plugin.version &&
			(release.sha256 == null || release.sha256.equals(plugin.sha256, ignoreCase = true))

	private fun requireStageAvailable(config: ProviderConfig) {
		require(isStageAvailable(config.provider)) {
			"${config.displayName} support is not available in this plugin stage"
		}
	}

	@WorkerThread
	private fun fetchLatestRelease(config: ProviderConfig): RemoteRelease {
		val repositories = buildList {
			add(config.repository)
			addAll(config.fallbackRepositories)
		}.distinct()
		var lastFailure: Exception? = null
		for (repository in repositories) {
			val candidate = config.copy(
				repository = repository,
				fallbackRepositories = emptyList(),
			)
			try {
				return fetchLatestReleaseFromRepository(candidate)
			} catch (error: Exception) {
				lastFailure = error
			}
		}
		throw lastFailure ?: IllegalStateException("No release repository is configured")
	}

	@WorkerThread
	private fun fetchLatestMiyorarePackRelease(pack: MiyorareOfficialSourcePack): MiyorarePackRelease {
		val repositories = listOf(
			MiyorareOfficialSourcePacks.REPOSITORY,
			MiyorareOfficialSourcePacks.LEGACY_REPOSITORY,
		).distinct()
		var lastFailure: Exception? = null
		for (repository in repositories) {
			try {
				val root = fetchLatestPrefixedReleaseRoot(repository)
				val tag = root.getString("tag_name")
				val names = releaseAssetNames(root)
				if (pack.shards.all { it.assetName in names }) {
					val releases = pack.shards.map { shard ->
						parseRelease(miyorareShardConfig(pack, shard, repository), root)
					}
					return MiyorarePackRelease(tag, repository, releases, legacySingleJar = false)
				}
				if (pack.assetName in names) {
					val remote = parseRelease(miyorareLegacyConfig(pack, repository), root)
					return MiyorarePackRelease(tag, repository, listOf(remote), legacySingleJar = true)
				}
				error("Release $tag has neither the complete ${pack.displayName} shard set nor ${pack.assetName}")
			} catch (error: Exception) {
				lastFailure = error
			}
		}
		throw lastFailure ?: IllegalStateException("No official Miyorare source-pack release is configured")
	}

	private fun releaseAssetNames(root: JSONObject): Set<String> {
		val assets = root.getJSONArray("assets")
		return buildSet(assets.length()) {
			for (index in 0 until assets.length()) add(assets.getJSONObject(index).optString("name"))
		}
	}

	@WorkerThread
	private fun fetchLatestReleaseFromRepository(config: ProviderConfig): RemoteRelease {
		if (config.releaseTagPrefix != null) {
			return parseRelease(config, fetchLatestPrefixedReleaseRoot(config.repository))
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
	private fun fetchLatestPrefixedReleaseRoot(repository: String): JSONObject {
		val request = Request.Builder()
			.url("https://api.github.com/repos/$repository/releases?per_page=30")
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
				val version = MiyorareOfficialSourcePacks.versionFromTag(tag) ?: continue
				candidates += version to release
			}
			return candidates.maxByOrNull { it.first }?.second
				?: error("No stable official Miyorare source-pack release is published yet")
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
		if (plugin.provider == TsukiPluginProvider.MIYORARE) return null
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

	private fun miyorareLegacyConfig(pack: MiyorareOfficialSourcePack, repository: String): ProviderConfig =
		ProviderConfig(
			provider = TsukiPluginProvider.MIYORARE,
			pluginId = pack.pluginId,
			displayName = pack.displayName,
			repository = repository,
			assetName = pack.assetName,
			releaseTagPrefix = MiyorareOfficialSourcePacks.RELEASE_TAG_PREFIX,
			requireSha256 = true,
		)

	private fun miyorareShardConfig(
		pack: MiyorareOfficialSourcePack,
		shard: MiyorareOfficialSourceShard,
		repository: String,
	): ProviderConfig = ProviderConfig(
		provider = TsukiPluginProvider.MIYORARE,
		pluginId = shard.pluginId,
		displayName = shard.displayName,
		repository = repository,
		assetName = shard.assetName,
		releaseTagPrefix = MiyorareOfficialSourcePacks.RELEASE_TAG_PREFIX,
		requireSha256 = true,
	)

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
		val fallbackRepositories: List<String> = emptyList(),
	) {
		val repositoryUrl: String
			get() = "https://github.com/$repository"
	}

	private companion object {
		const val MAX_PLUGIN_BYTES = 32L * 1024L * 1024L
	}
}
