package org.koitharu.kotatsu.tsuki

import org.json.JSONObject
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Reads first-party adaptation provenance only when Source Pack settings ask for it.
 *
 * This deliberately stays off startup/source-resolution hot paths. The metadata is embedded in
 * official Miyorare JARs by the validated Source Pack pipeline and is never trusted from third-party
 * plugins.
 */
internal fun TsukiPluginManager.readMiyorareAdaptedSourceNames(
	pack: MiyorareOfficialSourcePack,
): Set<String> = buildSet {
	val packPlugins = getPlugins().filter { plugin ->
		plugin.provider == TsukiPluginProvider.MIYORARE &&
			MiyorareOfficialSourcePacks.findByInstalledPluginId(plugin.pluginId)?.pluginId == pack.pluginId
	}
	for (plugin in packPlugins) {
		val jar = pluginJar(plugin)?.takeIf { it.isFile } ?: continue
		val names = runCatching {
			ZipFile(jar).use { archive ->
				val entry = archive.getEntry(MIYORARE_PACK_METADATA) ?: return@use emptySet<String>()
				val root = archive.getInputStream(entry).bufferedReader().use { reader ->
					JSONObject(reader.readText())
				}
				val adapters = root.optJSONArray("semanticAdapters") ?: return@use emptySet<String>()
				buildSet {
					for (adapterIndex in 0 until adapters.length()) {
						val adapter = adapters.optJSONObject(adapterIndex) ?: continue
						val applied = adapter.optJSONArray("applied") ?: continue
						for (appliedIndex in 0 until applied.length()) {
							val item = applied.optJSONObject(appliedIndex) ?: continue
							val canonicalId = item.optString("canonicalId").trim()
							val sourceName = canonicalId.substringAfterLast(':', "").trim()
							if (sourceName.isNotEmpty()) add(sourceName.uppercase(Locale.ROOT))
						}
					}
				}
			}
		}.getOrDefault(emptySet())
		addAll(names)
	}
}

private const val MIYORARE_PACK_METADATA = "META-INF/miyorare-pack.json"
