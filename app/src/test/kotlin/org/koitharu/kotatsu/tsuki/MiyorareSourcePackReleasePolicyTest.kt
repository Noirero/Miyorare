package org.koitharu.kotatsu.tsuki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiyorareSourcePackReleasePolicyTest {

	private val contractSha256 = "f".repeat(64)
	private val farmCommit = "6".repeat(40)
	private val upstreamCommits = mapOf(
		"uma" to "3".repeat(40),
		"gekkoushi" to "4".repeat(40),
		"keiyoushi" to "5".repeat(40),
	)

	@Test
	fun `schema 3 stable manifest is accepted`() {
		val manifest = MiyorareSourcePackReleasePolicy.parseManifest(
			manifest(version = "1.2.3").encodeToByteArray(),
			"miyorare-sources-v1.2.3",
		)
		assertEquals(SourcePackVersion(1, 2, 3), manifest.version)
		assertTrue(MiyorareSourcePackReleasePolicy.isCompatible(manifest, 75))
	}

	@Test
	fun `schema 2 is not silently reinterpreted`() {
		assertThrows(IllegalArgumentException::class.java) {
			MiyorareSourcePackReleasePolicy.parseManifest(
				manifest(version = "1.2.3", schema = 2).encodeToByteArray(),
				"miyorare-sources-v1.2.3",
			)
		}
	}

	@Test
	fun `stable consumer rejects beta epoch api and version range mismatch`() {
		for (raw in listOf(
			manifest(channel = "beta"),
			manifest(epoch = 2),
			manifest(tsukiApi = "2.0.0"),
		)) {
			assertThrows(IllegalArgumentException::class.java) {
				MiyorareSourcePackReleasePolicy.parseManifest(raw.encodeToByteArray(), "miyorare-sources-v1.2.3")
			}
		}
		val future = MiyorareSourcePackReleasePolicy.parseManifest(
			manifest(minVersionCode = 80).encodeToByteArray(),
			"miyorare-sources-v1.2.3",
		)
		assertTrue(!MiyorareSourcePackReleasePolicy.isCompatible(future, 75))
	}

	@Test
	fun `newest incompatible release falls back to older compatible release`() {
		val compatible = MiyorareSourcePackReleasePolicy.parseManifest(
			manifest(version = "1.2.3", minVersionCode = 70, maxVersionCode = 79).encodeToByteArray(),
			"miyorare-sources-v1.2.3",
		)
		val tooNew = MiyorareSourcePackReleasePolicy.parseManifest(
			manifest(version = "1.3.0", minVersionCode = 80).encodeToByteArray(),
			"miyorare-sources-v1.3.0",
		)
		assertEquals(
			compatible,
			MiyorareSourcePackReleasePolicy.selectNewestCompatible(listOf(compatible, tooNew), 75),
		)
		assertNull(MiyorareSourcePackReleasePolicy.selectNewestCompatible(listOf(tooNew), 75))
	}

	@Test
	fun `missing required shard fails closed`() {
		val broken = manifest().replace(
			"""{"provider":"GEKKOUSHI","pluginId":"miyorare-global","assetName":"miyorare-global-gekkoushi.jar","size":14,"sha256":"${"e".repeat(64)}","sourceCount":2}""",
			"",
		)
		assertThrows(IllegalArgumentException::class.java) {
			MiyorareSourcePackReleasePolicy.parseManifest(broken.encodeToByteArray(), "miyorare-sources-v1.2.3")
		}
	}

	@Test
	fun `compatibility snapshot is deterministic and fail closed`() {
		val snapshotId = MiyorareSourcePackReleasePolicy.compatibilitySnapshotId(
			contractSha256 = contractSha256,
			runtimeCommit = "2".repeat(40),
			builderCommit = "1".repeat(40),
			farmCommit = farmCommit,
			upstreams = upstreamCommits,
		)
		val parsed = MiyorareSourcePackReleasePolicy.parseManifest(
			manifest(withSnapshot = true).encodeToByteArray(),
			"miyorare-sources-v1.2.3",
		)
		assertEquals(snapshotId, parsed.compatibilitySnapshot?.id)
		assertEquals(farmCommit, parsed.compatibilitySnapshot?.farmCommit)
		assertEquals(contractSha256, parsed.compatibilitySnapshot?.contractSha256)

		val tampered = manifest(withSnapshot = true).replace(farmCommit, "7".repeat(40))
		assertThrows(IllegalArgumentException::class.java) {
			MiyorareSourcePackReleasePolicy.parseManifest(
				tampered.encodeToByteArray(),
				"miyorare-sources-v1.2.3",
			)
		}
	}

	@Test
	fun `release lock must bind compatibility snapshot when present`() {
		val manifestBytes = manifest(withSnapshot = true).encodeToByteArray()
		val parsed = MiyorareSourcePackReleasePolicy.parseManifest(
			manifestBytes,
			"miyorare-sources-v1.2.3",
		)
		val snapshotId = requireNotNull(parsed.compatibilitySnapshot).id
		val manifestDigest = MiyorareSourcePackReleasePolicy.sha256Hex(manifestBytes)

		fun lockBytes(id: String): ByteArray = """
			{
			  "schemaVersion":1,
			  "kind":"MIYORARE_SOURCE_PACK_RELEASE_LOCK",
			  "immutable":true,
			  "tag":"miyorare-sources-v1.2.3",
			  "releaseManifestSha256":"$manifestDigest",
			  "compatibilitySnapshotId":"$id",
			  "assets":[
			    {"name":"miyorare-source-packs.json","size":${manifestBytes.size},"sha256":"$manifestDigest"}
			  ],
			  "signatureMode":"GITHUB_ARTIFACT_ATTESTATION",
			  "signatureIssuer":"https://token.actions.githubusercontent.com",
			  "signatureRepository":"Noirero/Miyorare-Source-Packs",
			  "signatureWorkflow":".github/workflows/source-pack-release-seal.yml"
			}
		""".trimIndent().encodeToByteArray()

		val releaseAssets = listOf(
			MiyorareSourcePackReleasePolicy.ReleaseAssetMetadata(
				MiyorareSourcePackReleasePolicy.RELEASE_MANIFEST_ASSET,
				manifestBytes.size.toLong(),
				manifestDigest,
			),
			MiyorareSourcePackReleasePolicy.ReleaseAssetMetadata(
				MiyorareSourcePackReleasePolicy.RELEASE_LOCK_ASSET,
				1,
				"a".repeat(64),
			),
			MiyorareSourcePackReleasePolicy.ReleaseAssetMetadata(
				MiyorareSourcePackReleasePolicy.RELEASE_LOCK_SHA256_ASSET,
				1,
				"b".repeat(64),
			),
		)

		val lock = lockBytes(snapshotId)
		val checksum = "${MiyorareSourcePackReleasePolicy.sha256Hex(lock)}  miyorare-release-lock.json\n".encodeToByteArray()
		MiyorareSourcePackReleasePolicy.verifyReleaseLock(
			lock,
			checksum,
			manifestBytes,
			"miyorare-sources-v1.2.3",
			releaseAssets,
		)

		val wrong = lockBytes("0".repeat(64))
		val wrongChecksum = "${MiyorareSourcePackReleasePolicy.sha256Hex(wrong)}  miyorare-release-lock.json\n".encodeToByteArray()
		assertThrows(IllegalArgumentException::class.java) {
			MiyorareSourcePackReleasePolicy.verifyReleaseLock(
				wrong,
				wrongChecksum,
				manifestBytes,
				"miyorare-sources-v1.2.3",
				releaseAssets,
			)
		}
	}

	@Test
	fun `release lock must bind exact manifest and github asset set`() {
		val manifestBytes = manifest().encodeToByteArray()
		val jarBytes = "jar-payload".encodeToByteArray()
		val packBytes = "pack-metadata".encodeToByteArray()
		val manifestDigest = MiyorareSourcePackReleasePolicy.sha256Hex(manifestBytes)
		val jarDigest = MiyorareSourcePackReleasePolicy.sha256Hex(jarBytes)
		val packDigest = MiyorareSourcePackReleasePolicy.sha256Hex(packBytes)
		val assets = listOf(
			MiyorareSourcePackReleasePolicy.ReleaseAssetMetadata(
				MiyorareSourcePackReleasePolicy.RELEASE_MANIFEST_ASSET,
				manifestBytes.size.toLong(),
				manifestDigest,
			),
			MiyorareSourcePackReleasePolicy.ReleaseAssetMetadata("a.jar", jarBytes.size.toLong(), jarDigest),
			MiyorareSourcePackReleasePolicy.ReleaseAssetMetadata("a-pack.json", packBytes.size.toLong(), packDigest),
			MiyorareSourcePackReleasePolicy.ReleaseAssetMetadata(
				MiyorareSourcePackReleasePolicy.RELEASE_LOCK_ASSET,
				100,
				"f".repeat(64),
			),
			MiyorareSourcePackReleasePolicy.ReleaseAssetMetadata(
				MiyorareSourcePackReleasePolicy.RELEASE_LOCK_SHA256_ASSET,
				100,
				"0".repeat(64),
			),
		)
		val lock = """
			{
			  "schemaVersion":1,
			  "kind":"MIYORARE_SOURCE_PACK_RELEASE_LOCK",
			  "immutable":true,
			  "tag":"miyorare-sources-v1.2.3",
			  "releaseManifestSha256":"$manifestDigest",
			  "assets":[
			    {"name":"miyorare-source-packs.json","size":${manifestBytes.size},"sha256":"$manifestDigest"},
			    {"name":"a.jar","size":${jarBytes.size},"sha256":"$jarDigest"},
			    {"name":"a-pack.json","size":${packBytes.size},"sha256":"$packDigest"}
			  ],
			  "signatureMode":"GITHUB_ARTIFACT_ATTESTATION",
			  "signatureIssuer":"https://token.actions.githubusercontent.com",
			  "signatureRepository":"Noirero/Miyorare-Source-Packs",
			  "signatureWorkflow":".github/workflows/source-pack-release-seal.yml"
			}
		""".trimIndent().encodeToByteArray()
		val lockDigest = MiyorareSourcePackReleasePolicy.sha256Hex(lock)
		val checksum = "$lockDigest  miyorare-release-lock.json\n".encodeToByteArray()

		MiyorareSourcePackReleasePolicy.verifyReleaseLock(
			lock,
			checksum,
			manifestBytes,
			"miyorare-sources-v1.2.3",
			assets,
		)

		assertThrows(IllegalArgumentException::class.java) {
			MiyorareSourcePackReleasePolicy.verifyReleaseLock(
				lock,
				checksum,
				"tampered".encodeToByteArray(),
				"miyorare-sources-v1.2.3",
				assets,
			)
		}
	}

	private fun manifest(
		version: String = "1.2.3",
		schema: Int = 3,
		channel: String = "stable",
		epoch: Int = 1,
		tsukiApi: String = "1.0.5",
		minVersionCode: Int = 75,
		maxVersionCode: Int? = null,
		withSnapshot: Boolean = false,
	): String {
		val max = maxVersionCode?.toString() ?: "null"
		val snapshotId = MiyorareSourcePackReleasePolicy.compatibilitySnapshotId(
			contractSha256 = contractSha256,
			runtimeCommit = "2".repeat(40),
			builderCommit = "1".repeat(40),
			farmCommit = farmCommit,
			upstreams = upstreamCommits,
		)
		val snapshot = if (withSnapshot) {
			""",
			  "compatibilitySnapshotId":"$snapshotId",
			  "compatibilitySnapshot":{
			    "schemaVersion":1,
			    "algorithm":"sha256",
			    "contractSha256":"$contractSha256",
			    "farmCommit":"$farmCommit"
			  }""".trimIndent()
		} else {
			""
		}
		return """
			{
			  "schema":$schema,
			  "version":"$version",
			  "tag":"miyorare-sources-v$version",
			  "compatibility":{
			    "channel":"$channel",
			    "tsukiApi":"$tsukiApi",
			    "compatibilityEpoch":$epoch,
			    "minMiyorareVersionCode":$minVersionCode,
			    "maxMiyorareVersionCode":$max,
			    "requiredLogicalPacks":["miyorare-id","miyorare-en","miyorare-global"],
			    "releaseLockRequired":true
			  },
			  "sourceRepository":"Noirero/Miyorare",
			  "sourceBranch":"beta",
			  "sourceCommit":"${"1".repeat(40)}",
			  "runtimeCompatibility":{
			    "repository":"Noirero/Miyorare",
			    "branch":"main",
			    "commit":"${"2".repeat(40)}",
			    "versionCode":$minVersionCode,
			    "tsukiApi":"$tsukiApi"
			  },
			  "upstreams":{
			    "uma":"${"3".repeat(40)}",
			    "gekkoushi":"${"4".repeat(40)}",
			    "keiyoushi":"${"5".repeat(40)}"
			  }$snapshot,
			  "packs":[
			    {"pluginId":"miyorare-id","language":"id","sourceCount":2,"shards":[
			      {"provider":"UMA","pluginId":"miyorare-id","assetName":"miyorare-id-uma.jar","size":10,"sha256":"${"a".repeat(64)}","sourceCount":1},
			      {"provider":"GEKKOUSHI","pluginId":"miyorare-id-gekkoushi","assetName":"miyorare-id-gekkoushi.jar","size":11,"sha256":"${"b".repeat(64)}","sourceCount":1}
			    ]},
			    {"pluginId":"miyorare-en","language":"en","sourceCount":2,"shards":[
			      {"provider":"UMA","pluginId":"miyorare-en","assetName":"miyorare-en-uma.jar","size":12,"sha256":"${"c".repeat(64)}","sourceCount":1},
			      {"provider":"GEKKOUSHI","pluginId":"miyorare-en-gekkoushi","assetName":"miyorare-en-gekkoushi.jar","size":13,"sha256":"${"d".repeat(64)}","sourceCount":1}
			    ]},
			    {"pluginId":"miyorare-global","language":"all","sourceCount":2,"shards":[
			      {"provider":"GEKKOUSHI","pluginId":"miyorare-global","assetName":"miyorare-global-gekkoushi.jar","size":14,"sha256":"${"e".repeat(64)}","sourceCount":2}
			    ]}
			  ]
			}
		""".trimIndent()
	}
}
