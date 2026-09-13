package org.koitharu.kotatsu.tsuki.model

import android.net.Uri
import org.koitharu.kotatsu.parsers.model.MangaSource

/** Ecosystem that supplied a Tsuki/Usagi-compatible plugin. */
enum class TsukiPluginProvider(val wireName: String) {
	/** Official Miyorare source packs, e.g. Miyorare-ID and Miyorare-EN. */
	MIYORARE("MIYORARE"),
	UMA("UMA"),
	GEKKOUSHI("GEKKOUSHI"),
	CUSTOM("CUSTOM");

	companion object {
		fun fromWireName(value: String): TsukiPluginProvider =
			entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: CUSTOM
	}
}

enum class TsukiPluginState {
	ENABLED,
	DISABLED,
	BROKEN,
}

enum class TsukiCompatibilityStatus {
	COMPATIBLE,
	INCOMPATIBLE,
	INVALID,
}

data class TsukiCompatibilityResult(
	val status: TsukiCompatibilityStatus,
	val hostApi: String,
	val requiredApi: String?,
	val reason: String? = null,
) {
	val isCompatible: Boolean
		get() = status == TsukiCompatibilityStatus.COMPATIBLE
}

data class TsukiSourceDescriptor(
	val name: String,
	val title: String,
	val locale: String,
	val contentType: String,
	val isBroken: Boolean = false,
	/** Optional first-party source logo URL. Third-party plugins leave this null. */
	val iconUrl: String? = null,
)

data class TsukiPluginDescriptor(
	val pluginId: String,
	val displayName: String,
	val provider: TsukiPluginProvider,
	val version: String,
	val origin: String,
	val sha256: String,
	val fileSize: Long,
	val lastModified: Long,
	val requiredApi: String?,
	val compatibility: TsukiCompatibilityStatus,
	val state: TsukiPluginState,
	val sources: List<TsukiSourceDescriptor>,
	/**
	 * Source participation is intentionally separate from plugin lifecycle. New plugins start with
	 * an empty set so installing a large provider never silently expands Global Search or Explore.
	 */
	val enabledSourceNames: Set<String> = emptySet(),
	val failureReason: String? = null,
) {
	val storageKey: String
		get() = "${provider.wireName.lowercase()}__$pluginId"
}

/**
 * Stable persisted identity. Backend/provider/plugin/source are all part of the key so a Tsuki
 * source can never alias a Mihon or LN source with the same website name.
 */
data class TsukiSourceIdentity(
	val provider: TsukiPluginProvider,
	val pluginId: String,
	val sourceName: String,
) {
	val storedName: String
		get() = buildString {
			append(PREFIX)
			append(provider.wireName)
			append(':')
			append(Uri.encode(pluginId))
			append(':')
			append(Uri.encode(sourceName))
		}

	companion object {
		const val PREFIX = "TSUKI:"

		fun parse(value: String): TsukiSourceIdentity? {
			if (!value.startsWith(PREFIX)) return null
			val parts = value.removePrefix(PREFIX).split(':', limit = 3)
			if (parts.size != 3) return null
			val pluginId = Uri.decode(parts[1]).takeIf { it.isNotBlank() } ?: return null
			val sourceName = Uri.decode(parts[2]).takeIf { it.isNotBlank() } ?: return null
			return TsukiSourceIdentity(
				provider = TsukiPluginProvider.fromWireName(parts[0]),
				pluginId = pluginId,
				sourceName = sourceName,
			)
		}
	}
}

/** Lightweight source object. It contains metadata only; creating it never loads plugin code. */
data class TsukiMangaSource(
	val plugin: TsukiPluginDescriptor,
	val descriptor: TsukiSourceDescriptor,
) : MangaSource {
	val identity = TsukiSourceIdentity(plugin.provider, plugin.pluginId, descriptor.name)

	override val name: String
		get() = identity.storedName

	val displayName: String
		get() = descriptor.title.ifBlank { descriptor.name }

	val language: String
		get() = descriptor.locale

	val pluginId: String
		get() = plugin.pluginId

	val isEnabled: Boolean
		get() = descriptor.name in plugin.enabledSourceNames

	override fun equals(other: Any?): Boolean = other is MangaSource && other.name == name

	override fun hashCode(): Int = name.hashCode()
}