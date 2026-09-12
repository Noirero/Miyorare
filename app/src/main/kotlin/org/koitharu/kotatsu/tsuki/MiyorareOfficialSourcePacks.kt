package org.koitharu.kotatsu.tsuki

/**
 * Trusted metadata for source packs published by Miyorare itself.
 *
 * The list is intentionally small and static. A remote release may only become an official plugin
 * when its plugin id and asset name are declared here, its release tag is in the dedicated source
 * release namespace, and GitHub supplies a matching SHA-256 digest for the downloaded asset.
 */
data class MiyorareOfficialSourcePack(
	val pluginId: String,
	val displayName: String,
	val language: String,
	val assetName: String,
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
		),
		MiyorareOfficialSourcePack(
			pluginId = EN_PLUGIN_ID,
			displayName = "Miyorare-EN",
			language = "en",
			assetName = "miyorare-en.jar",
		),
	)

	fun find(pluginId: String): MiyorareOfficialSourcePack? =
		packs.firstOrNull { it.pluginId == pluginId }

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
