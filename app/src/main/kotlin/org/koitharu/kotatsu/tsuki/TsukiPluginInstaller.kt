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
 * asks to install/check/update a plugin. Official provider installs are restricted to known GitHub
 * repositories and exact JAR asset names; local imports still pass through the same validator and
 * atomic rollback path in [TsukiPluginManager].
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

	suspend fun latestRelease(provider: TsukiPluginProvider): RemoteRelease = withContext(Dispatchers.IO) {
		fetchLatestRelease(requireNotNull(knownProvider(provider)) { "No official repository for $provider" })
	}

	suspend fun checkForUpdate(plugin: TsukiPluginDescriptor): RemoteRelease? = withContext(Dispatchers.IO) {
		val config = knownProvider(plugin.provider) ?: return@withContext null
		val latest = fetchLatestRelease(config)
		latest.takeUnless { it.tag == plugin.version }
	}

	suspend fun installLatest(provider: TsukiPluginProvider): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		val config = requireNotNull(knownProvider(provider)) { "No official repository for $provider" }
		val release = fetchLatestRelease(config)
		val temp = File.createTempFile("tsuki-${config.pluginId}-", ".jar", context.cacheDir)
		try {
			downloadRelease(release, temp)
			pluginManager.installLocalJar(
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

	suspend fun installLocal(uri: Uri): TsukiPluginDescriptor = withContext(Dispatchers.IO) {
		val displayName = queryDisplayName(uri).orEmpty().ifBlank { "plugin.jar" }
		val config = inferLocalProvider(displayName)
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
	private fun fetchLatestRelease(config: ProviderConfig): RemoteRelease {
		val request = Request.Builder()
			.url("https://api.github.com/repos/${config.repository}/releases/latest")
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.build()
		httpClient.newCall(request).execute().use { response ->
			require(response.isSuccessful) { "GitHub returned HTTP ${response.code}" }
			val root = JSONObject(response.body.string())
			val tag = root.getString("tag_name").trim().also { require(it.isNotEmpty()) }
			val assets = root.getJSONArray("assets")
			for (i in 0 until assets.length()) {
				val asset = assets.getJSONObject(i)
				if (asset.optString("name") != config.assetName) continue
				val size = asset.optLong("size", -1L)
				require(size in 1..MAX_PLUGIN_BYTES) { "Plugin asset has an invalid size: $size" }
				val url = asset.getString("browser_download_url")
				require(url.startsWith("https://github.com/${config.repository}/releases/download/")) {
					"Unexpected plugin download origin"
				}
				val digest = asset.optString("digest")
					.takeIf { it.startsWith("sha256:", ignoreCase = true) }
					?.substringAfter(':')
					?.lowercase(Locale.ROOT)
				return RemoteRelease(config.provider, tag, config.assetName, url, size, digest)
			}
			error("Release $tag does not contain ${config.assetName}")
		}
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
		val id = baseName
			.replace(Regex("[^a-z0-9._-]"), "-")
			.trim('-')
			.take(64)
			.ifBlank { "custom" }
		return ProviderConfig(
			provider = TsukiPluginProvider.CUSTOM,
			pluginId = id,
			displayName = fileName.substringBeforeLast('.').ifBlank { "Custom Tsuki plugin" },
			repository = "",
			assetName = fileName,
		)
	}

	private fun knownProvider(provider: TsukiPluginProvider): ProviderConfig? = when (provider) {
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
		val assetName: String,
	) {
		val repositoryUrl: String
			get() = "https://github.com/$repository"
	}

	private companion object {
		const val MAX_PLUGIN_BYTES = 32L * 1024L * 1024L
	}
}
