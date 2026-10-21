package org.koitharu.kotatsu.kotatsu.runtime

import org.json.JSONObject

/** Stable protocol between the Miyorare host and a self-contained Kotatsu Runtime Pack. */
object KotatsuRuntimePackContract {
	const val PROTOCOL_VERSION = 1
	const val ENTRYPOINT_CLASS = "org.miyorare.kotatsu.runtime.KotatsuRuntimeEntrypoint"
	const val DEFAULT_MANIFEST_URL =
		"https://github.com/Noirero/Miyorare/releases/download/kotatsu-runtime-latest/manifest.json"
	const val MAX_PACK_BYTES = 128L * 1024L * 1024L
}

data class KotatsuRuntimePackManifest(
	val protocolVersion: Int,
	val packVersion: String,
	val upstreamRevision: String,
	val sourceCount: Int,
	val sha256: String,
	val downloadUrl: String,
) {
	init {
		require(protocolVersion > 0) { "Invalid Kotatsu runtime protocol" }
		require(packVersion.isNotBlank()) { "Missing Kotatsu runtime pack version" }
		require(upstreamRevision.matches(Regex("[0-9a-fA-F]{7,64}"))) { "Invalid upstream revision" }
		require(sourceCount >= 0) { "Invalid source count" }
		require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SHA-256" }
		require(downloadUrl.startsWith("https://")) { "Kotatsu runtime download must use HTTPS" }
	}

	val isHostCompatible: Boolean
		get() = protocolVersion == KotatsuRuntimePackContract.PROTOCOL_VERSION

	fun toJson(): String = JSONObject()
		.put("protocolVersion", protocolVersion)
		.put("packVersion", packVersion)
		.put("upstreamRevision", upstreamRevision)
		.put("sourceCount", sourceCount)
		.put("sha256", sha256.lowercase())
		.put("downloadUrl", downloadUrl)
		.toString()

	companion object {
		fun parse(json: String): KotatsuRuntimePackManifest {
			val root = JSONObject(json)
			return KotatsuRuntimePackManifest(
				protocolVersion = root.getInt("protocolVersion"),
				packVersion = root.getString("packVersion"),
				upstreamRevision = root.getString("upstreamRevision"),
				sourceCount = root.getInt("sourceCount"),
				sha256 = root.getString("sha256").lowercase(),
				downloadUrl = root.getString("downloadUrl"),
			)
		}
	}
}
