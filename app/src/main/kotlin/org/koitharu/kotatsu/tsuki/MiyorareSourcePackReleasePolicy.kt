package org.koitharu.kotatsu.tsuki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/**
 * Fail-closed policy for official Miyorare Source Pack release metadata.
 *
 * Schema 2 remains historical and is intentionally not reinterpreted. New remote selection requires
 * schema 3 compatibility metadata. If no compatible sealed release exists, callers leave the
 * installed last-known-good pack untouched.
 */
object MiyorareSourcePackReleasePolicy {
	const val RELEASE_MANIFEST_ASSET = "miyorare-source-packs.json"
	const val RELEASE_LOCK_ASSET = "miyorare-release-lock.json"
	const val RELEASE_LOCK_SHA256_ASSET = "miyorare-release-lock.sha256"
	const val RELEASE_MANIFEST_SCHEMA = 3
	const val COMPATIBILITY_EPOCH = 1
	const val TSUKI_API = "1.0.5"
	const val STABLE_CHANNEL = "stable"

	private const val RELEASE_LOCK_SCHEMA = 1
	private const val RELEASE_LOCK_KIND = "MIYORARE_SOURCE_PACK_RELEASE_LOCK"
	private const val SIGNATURE_MODE = "GITHUB_ARTIFACT_ATTESTATION"
	private const val SIGNATURE_ISSUER = "https://token.actions.githubusercontent.com"
	private const val SIGNATURE_REPOSITORY = "Noirero/Miyorare-Source-Packs"
	private const val SIGNATURE_WORKFLOW = ".github/workflows/source-pack-release-seal.yml"
	private val HEX40 = Regex("^[0-9a-f]{40}$")
	private val HEX64 = Regex("^[0-9a-f]{64}$")
	private val json = Json { isLenient = false }

	data class ReleaseAssetMetadata(
		val name: String,
		val size: Long,
		val sha256: String?,
	)

	data class ShardManifest(
		val provider: String,
		val pluginId: String,
		val assetName: String,
		val size: Long,
		val sha256: String,
		val sourceCount: Int,
	)

	data class PackManifest(
		val pluginId: String,
		val language: String,
		val sourceCount: Int,
		val shards: List<ShardManifest>,
	)

	data class ReleaseManifest(
		val version: SourcePackVersion,
		val versionText: String,
		val tag: String,
		val channel: String,
		val tsukiApi: String,
		val compatibilityEpoch: Int,
		val minMiyorareVersionCode: Int,
		val maxMiyorareVersionCode: Int?,
		val releaseLockRequired: Boolean,
		val requiredLogicalPacks: Set<String>,
		val sourceCommit: String,
		val runtimeCommit: String,
		val runtimeVersionCode: Int,
		val packs: Map<String, PackManifest>,
	)

	fun parseManifest(bytes: ByteArray, expectedTag: String): ReleaseManifest {
		val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
		require(root.requiredInt("schema") == RELEASE_MANIFEST_SCHEMA) {
			"Unsupported Source Pack release manifest schema"
		}
		val versionText = root.requiredString("version")
		val version = parseVersion(versionText)
		val tag = root.requiredString("tag")
		require(tag == expectedTag && tag == "${MiyorareOfficialSourcePacks.RELEASE_TAG_PREFIX}$versionText") {
			"Source Pack manifest tag/version mismatch"
		}

		val compatibility = root.requiredObject("compatibility")
		val channel = compatibility.requiredString("channel")
		val tsukiApi = compatibility.requiredString("tsukiApi")
		val epoch = compatibility.requiredInt("compatibilityEpoch")
		val minimum = compatibility.requiredInt("minMiyorareVersionCode")
		val maximum = compatibility.optionalInt("maxMiyorareVersionCode")
		val releaseLockRequired = compatibility.requiredBoolean("releaseLockRequired")
		val requiredLogicalPacks = compatibility.requiredStringSet("requiredLogicalPacks")
		require(channel == STABLE_CHANNEL) { "Source Pack release channel is not stable" }
		require(tsukiApi == TSUKI_API) { "Source Pack Tsuki API is incompatible" }
		require(epoch == COMPATIBILITY_EPOCH) { "Source Pack compatibility epoch is incompatible" }
		require(minimum > 0) { "Source Pack minimum Miyorare versionCode is invalid" }
		require(maximum == null || maximum >= minimum) { "Source Pack Miyorare versionCode range is invalid" }
		require(releaseLockRequired) { "Source Pack release lock is required" }
		require(requiredLogicalPacks == MiyorareOfficialSourcePacks.packs.map { it.pluginId }.toSet()) {
			"Source Pack required logical pack set is incompatible"
		}

		require(root.requiredString("sourceRepository") == "Noirero/Miyorare")
		require(root.requiredString("sourceBranch") == "beta")
		val sourceCommit = root.requiredSha("sourceCommit")
		val runtime = root.requiredObject("runtimeCompatibility")
		require(runtime.requiredString("repository") == "Noirero/Miyorare")
		require(runtime.requiredString("branch") == "main")
		val runtimeCommit = runtime.requiredSha("commit")
		val runtimeVersionCode = runtime.requiredInt("versionCode")
		require(runtimeVersionCode == minimum) { "Stable runtime versionCode does not match minimum compatibility" }
		require(runtime.requiredString("tsukiApi") == tsukiApi) { "Stable runtime Tsuki API mismatch" }

		val upstreams = root.requiredObject("upstreams")
		require(upstreams.keys == setOf("uma", "gekkoushi", "keiyoushi")) { "Unexpected Source Pack upstream set" }
		upstreams.keys.forEach { upstreams.requiredSha(it) }

		val packArray = root.requiredArray("packs")
		val expectedPacks = MiyorareOfficialSourcePacks.packs.associateBy { it.pluginId }
		val parsedPacks = LinkedHashMap<String, PackManifest>()
		for (element in packArray) {
			val item = element.jsonObject
			val pluginId = item.requiredString("pluginId")
			val expected = requireNotNull(expectedPacks[pluginId]) { "Unexpected logical Source Pack: $pluginId" }
			require(pluginId !in parsedPacks) { "Duplicate logical Source Pack: $pluginId" }
			val language = item.requiredString("language")
			require(language == expected.language) { "Unexpected language for $pluginId" }
			val sourceCount = item.requiredInt("sourceCount")
			require(sourceCount > 0) { "Source Pack $pluginId contains no sources" }
			val shards = item.requiredArray("shards").map { shardElement ->
				val shard = shardElement.jsonObject
				ShardManifest(
					provider = shard.requiredString("provider"),
					pluginId = shard.requiredString("pluginId"),
					assetName = shard.requiredString("assetName"),
					size = shard.requiredLong("size").also { require(it > 0) },
					sha256 = shard.requiredHex64("sha256"),
					sourceCount = shard.requiredInt("sourceCount").also { require(it > 0) },
				)
			}
			require(shards.isNotEmpty()) { "Source Pack $pluginId contains no shards" }
			require(shards.map { it.assetName }.toSet().size == shards.size) { "Duplicate shard asset in $pluginId" }
			val expectedShards = expected.shards.associateBy { it.assetName }
			require(shards.map { it.assetName }.toSet() == expectedShards.keys) {
				"Source Pack $pluginId has an incomplete or unexpected shard set"
			}
			for (shard in shards) {
				val expectedShard = requireNotNull(expectedShards[shard.assetName])
				require(shard.pluginId == expectedShard.pluginId) { "Shard plugin identity mismatch: ${shard.assetName}" }
				require(shard.provider == "UMA" || shard.provider == "GEKKOUSHI") {
					"Unexpected shard provider: ${shard.provider}"
				}
			}
			parsedPacks[pluginId] = PackManifest(pluginId, language, sourceCount, shards)
		}
		require(parsedPacks.keys == expectedPacks.keys) { "Source Pack release is missing required logical packs" }

		return ReleaseManifest(
			version = version,
			versionText = versionText,
			tag = tag,
			channel = channel,
			tsukiApi = tsukiApi,
			compatibilityEpoch = epoch,
			minMiyorareVersionCode = minimum,
			maxMiyorareVersionCode = maximum,
			releaseLockRequired = releaseLockRequired,
			requiredLogicalPacks = requiredLogicalPacks,
			sourceCommit = sourceCommit,
			runtimeCommit = runtimeCommit,
			runtimeVersionCode = runtimeVersionCode,
			packs = parsedPacks,
		)
	}

	fun isCompatible(manifest: ReleaseManifest, appVersionCode: Int): Boolean =
		appVersionCode >= manifest.minMiyorareVersionCode &&
			(manifest.maxMiyorareVersionCode == null || appVersionCode <= manifest.maxMiyorareVersionCode)

	fun selectNewestCompatible(
		manifests: Iterable<ReleaseManifest>,
		appVersionCode: Int,
	): ReleaseManifest? = manifests
		.filter { isCompatible(it, appVersionCode) }
		.maxByOrNull { it.version }

	fun verifyReleaseLock(
		lockBytes: ByteArray,
		checksumBytes: ByteArray,
		manifestBytes: ByteArray,
		expectedTag: String,
		releaseAssets: List<ReleaseAssetMetadata>,
	) {
		val checksumParts = checksumBytes.toString(Charsets.UTF_8).trim().split(Regex("\\s+"))
		require(checksumParts.size >= 2 && checksumParts[1] == RELEASE_LOCK_ASSET) {
			"Invalid Source Pack release-lock checksum file"
		}
		require(checksumParts[0].lowercase() == sha256Hex(lockBytes)) { "Source Pack release-lock SHA-256 mismatch" }

		val lock = json.parseToJsonElement(lockBytes.toString(Charsets.UTF_8)).jsonObject
		require(lock.requiredInt("schemaVersion") == RELEASE_LOCK_SCHEMA)
		require(lock.requiredString("kind") == RELEASE_LOCK_KIND)
		require(lock.requiredBoolean("immutable"))
		require(lock.requiredString("tag") == expectedTag) { "Source Pack release-lock tag mismatch" }
		require(lock.requiredHex64("releaseManifestSha256") == sha256Hex(manifestBytes)) {
			"Source Pack manifest does not match release lock"
		}
		require(lock.requiredString("signatureMode") == SIGNATURE_MODE)
		require(lock.requiredString("signatureIssuer") == SIGNATURE_ISSUER)
		require(lock.requiredString("signatureRepository") == SIGNATURE_REPOSITORY)
		require(lock.requiredString("signatureWorkflow") == SIGNATURE_WORKFLOW)

		val actualAssets = releaseAssets
			.filterNot { it.name == RELEASE_LOCK_ASSET || it.name == RELEASE_LOCK_SHA256_ASSET }
			.associateBy { it.name }
		require(actualAssets.size == releaseAssets.count {
			it.name != RELEASE_LOCK_ASSET && it.name != RELEASE_LOCK_SHA256_ASSET
		}) { "Duplicate Source Pack release asset" }

		val locked = LinkedHashMap<String, Pair<Long, String>>()
		for (element in lock.requiredArray("assets")) {
			val item = element.jsonObject
			val name = item.requiredString("name")
			require(name != RELEASE_LOCK_ASSET && name != RELEASE_LOCK_SHA256_ASSET)
			require(name !in locked) { "Duplicate locked Source Pack asset: $name" }
			val size = item.requiredLong("size")
			val digest = item.requiredHex64("sha256")
			require(size > 0)
			locked[name] = size to digest
		}
		require(locked.keys == actualAssets.keys) { "Source Pack release asset set does not match release lock" }
		for ((name, binding) in locked) {
			val actual = requireNotNull(actualAssets[name])
			val actualDigest = requireNotNull(actual.sha256) { "GitHub SHA-256 digest missing for $name" }
			require(actual.size == binding.first) { "Source Pack asset size differs from release lock: $name" }
			require(actualDigest.lowercase() == binding.second) { "Source Pack asset digest differs from release lock: $name" }
		}
	}

	fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }

	private fun parseVersion(value: String): SourcePackVersion {
		val parts = value.split('.')
		require(parts.size == 3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
			"Invalid Source Pack semantic version"
		}
		return SourcePackVersion(
			parts[0].toIntOrNull() ?: error("Invalid Source Pack major version"),
			parts[1].toIntOrNull() ?: error("Invalid Source Pack minor version"),
			parts[2].toIntOrNull() ?: error("Invalid Source Pack patch version"),
		)
	}

	private fun JsonObject.requiredObject(key: String): JsonObject =
		requireNotNull(this[key]) { "Missing $key" }.jsonObject

	private fun JsonObject.requiredArray(key: String): JsonArray =
		requireNotNull(this[key]) { "Missing $key" }.jsonArray

	private fun JsonObject.requiredString(key: String): String =
		requireNotNull(this[key]) { "Missing $key" }.jsonPrimitive.contentOrNull
			?.takeIf { it.isNotBlank() }
			?: error("Invalid $key")

	private fun JsonObject.requiredInt(key: String): Int =
		requireNotNull(this[key]) { "Missing $key" }.jsonPrimitive.intOrNull
			?: error("Invalid $key")

	private fun JsonObject.optionalInt(key: String): Int? {
		val element = this[key] ?: return null
		if (element.toString() == "null") return null
		return element.jsonPrimitive.intOrNull ?: error("Invalid $key")
	}

	private fun JsonObject.requiredLong(key: String): Long =
		requireNotNull(this[key]) { "Missing $key" }.jsonPrimitive.longOrNull
			?: error("Invalid $key")

	private fun JsonObject.requiredBoolean(key: String): Boolean =
		requireNotNull(this[key]) { "Missing $key" }.jsonPrimitive.booleanOrNull
			?: error("Invalid $key")

	private fun JsonObject.requiredSha(key: String): String = requiredString(key).lowercase().also {
		require(HEX40.matches(it)) { "Invalid git SHA for $key" }
	}

	private fun JsonObject.requiredHex64(key: String): String = requiredString(key).lowercase().also {
		require(HEX64.matches(it)) { "Invalid SHA-256 for $key" }
	}

	private fun JsonObject.requiredStringSet(key: String): Set<String> {
		val array = requiredArray(key)
		val values = array.map { element ->
			element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: error("Invalid $key entry")
		}
		require(values.toSet().size == values.size) { "Duplicate $key entry" }
		return values.toSet()
	}
}
