package org.koitharu.kotatsu.tsuki

import org.json.JSONObject
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Tiny metadata-only logo lookup for official Miyorare source packs.
 *
 * The source-pack JAR already carries META-INF/miyorare-pack.json, so this never creates a parser,
 * opens a website, or performs network work. One small JSON map is cached per installed shard
 * generation and Coil remains responsible for lazy image loading/caching when a visible row asks
 * for the URL.
 */
internal object MiyorareSourceIconRegistry {

	private val cache = ConcurrentHashMap<String, Map<String, String>>()

	fun iconUrl(
		pluginManager: TsukiPluginManager,
		plugin: TsukiPluginDescriptor,
		source: TsukiSourceDescriptor,
	): String? {
		if (plugin.provider != TsukiPluginProvider.MIYORARE) return null
		source.iconUrl?.takeIf(::isSafeUrl)?.let { return it }
		val cacheKey = buildString {
			append(plugin.storageKey)
			append(':')
			append(plugin.sha256)
			append(':')
			append(plugin.fileSize)
			append(':')
			append(plugin.lastModified)
		}
		return cache.getOrPut(cacheKey) {
			readIcons(pluginManager, plugin)
		}[source.name]
	}

	private fun readIcons(
		pluginManager: TsukiPluginManager,
		plugin: TsukiPluginDescriptor,
	): Map<String, String> {
		val jar = pluginManager.pluginJar(plugin) ?: return emptyMap()
		return runCatching {
			ZipFile(jar).use { archive ->
				val entry = archive.getEntry(METADATA_ENTRY) ?: return@use emptyMap()
				val root = archive.getInputStream(entry).bufferedReader().use { reader ->
					JSONObject(reader.readText())
				}
				val icons = root.optJSONObject("sourceIcons") ?: return@use emptyMap()
				buildMap {
					for (name in icons.keys()) {
						val url = icons.optString(name).trim()
						if (name.isNotBlank() && isSafeUrl(url)) put(name, url)
					}
				}
			}
		}.getOrDefault(emptyMap())
	}

	private fun isSafeUrl(url: String): Boolean =
		url.length <= MAX_URL_LENGTH && (url.startsWith("https://") || url.startsWith("http://"))

	private const val METADATA_ENTRY = "META-INF/miyorare-pack.json"
	private const val MAX_URL_LENGTH = 2_048
}
