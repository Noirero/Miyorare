package org.koitharu.kotatsu.tsuki

/** One independently built runtime shard inside a logical official Miyorare source pack. */
data class MiyorareOfficialSourceShard(
	val pluginId: String,
	val displayName: String,
	val assetName: String,
)

/**
 * Trusted metadata for source packs published by Miyorare itself.
 *
 * A logical ID/EN pack may consist of multiple independently built JARs. [assetName] remains the
 * legacy one-JAR asset name so installed clients can keep using immutable pre-shard releases.
 * The UMA shard deliberately keeps the logical plugin id so upgrading from a legacy one-JAR pack
 * preserves the user's source visibility choices and stored source identities.
 */
data class MiyorareOfficialSourcePack(
	val pluginId: String,
	val displayName: String,
	val language: String,
	val assetName: String,
	val shards: List<MiyorareOfficialSourceShard>,
)

object MiyorareOfficialSourcePacks {
	const val REPOSITORY = "Noirero/Miyorare-Source-Packs"
	const val LEGACY_REPOSITORY = "Noirero/Miyorare"
	const val RELEASE_TAG_PREFIX = "miyorare-sources-v"
	const val ID_PLUGIN_ID = "miyorare-id"
	const val EN_PLUGIN_ID = "miyorare-en"

	val packs: List<MiyorareOfficialSourcePack> = listOf(
		MiyorareOfficialSourcePack(
			pluginId = ID_PLUGIN_ID,
			displayName = "Miyorare-ID",
			language = "id",
			assetName = "miyorare-id.jar",
			shards = listOf(
				MiyorareOfficialSourceShard(
					pluginId = ID_PLUGIN_ID,
					displayName = "Miyorare-ID / UMA",
					assetName = "miyorare-id-uma.jar",
				),
				MiyorareOfficialSourceShard(
					pluginId = "miyorare-id-gekkoushi",
					displayName = "Miyorare-ID / Gekkoushi",
					assetName = "miyorare-id-gekkoushi.jar",
				),
			),
		),
		MiyorareOfficialSourcePack(
			pluginId = EN_PLUGIN_ID,
			displayName = "Miyorare-EN",
			language = "en",
			assetName = "miyorare-en.jar",
			shards = listOf(
				MiyorareOfficialSourceShard(
					pluginId = EN_PLUGIN_ID,
					displayName = "Miyorare-EN / UMA",
					assetName = "miyorare-en-uma.jar",
				),
				MiyorareOfficialSourceShard(
					pluginId = "miyorare-en-gekkoushi",
					displayName = "Miyorare-EN / Gekkoushi",
					assetName = "miyorare-en-gekkoushi.jar",
				),
			),
		),
	)

	fun find(pluginId: String): MiyorareOfficialSourcePack? =
		packs.firstOrNull { it.pluginId == pluginId }

	fun findByInstalledPluginId(pluginId: String): MiyorareOfficialSourcePack? =
		packs.firstOrNull { pack -> pack.pluginId == pluginId || pack.shards.any { it.pluginId == pluginId } }

	fun findShard(pluginId: String): Pair<MiyorareOfficialSourcePack, MiyorareOfficialSourceShard>? =
		packs.firstNotNullOfOrNull { pack ->
			pack.shards.firstOrNull { it.pluginId == pluginId }?.let { pack to it }
		}

	fun versionFromTag(tag: String): SourcePackVersion? {
		if (!tag.startsWith(RELEASE_TAG_PREFIX)) return null
		val raw = tag.removePrefix(RELEASE_TAG_PREFIX)
		val parts = raw.split('.')
		if (parts.size != 3) return null
		val numbers = parts.map { part ->
			if (part.isEmpty() || part.any { !it.isDigit() }) return null
			part.toIntOrNull() ?: return null
		}
		return SourcePackVersion(numbers[0], numbers[1], numbers[2])
	}

	fun newestReleaseTag(tags: Iterable<String>): String? = tags
		.mapNotNull { tag -> versionFromTag(tag)?.let { version -> tag to version } }
		.maxByOrNull { it.second }
		?.first

	fun normalizeSha256Digest(value: String?): String? {
		val raw = value?.trim().orEmpty()
		if (!raw.startsWith("sha256:", ignoreCase = true)) return null
		val digest = raw.substringAfter(':').lowercase()
		return digest.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
	}
}

data class SourcePackVersion(
	val major: Int,
	val minor: Int,
	val patch: Int,
) : Comparable<SourcePackVersion> {
	override fun compareTo(other: SourcePackVersion): Int =
		compareValuesBy(this, other, SourcePackVersion::major, SourcePackVersion::minor, SourcePackVersion::patch)
}
