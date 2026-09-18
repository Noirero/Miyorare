import copy
import hashlib
import importlib.util
import json
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "source_pack_readiness",
    Path(__file__).with_name("source_pack_readiness.py"),
)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

TAG = "miyorare-sources-v1.2.3"
APP_VERSION = 75


def contract():
    return {
        "consumer": {
            "repository": "Noirero/Miyorare",
            "sourceBranchForPackBuilds": "beta",
            "stableCompatibilityBranch": "main",
        },
        "producer": {"repository": "Noirero/Miyorare-Source-Packs"},
        "format": {
            "releaseManifestSchema": 3,
            "requiredLogicalPacks": ["miyorare-id", "miyorare-en", "miyorare-global"],
        },
        "compatibility": {
            "stableChannel": "stable",
            "compatibilityEpoch": 1,
            "tsukiApi": "1.0.5",
        },
        "trust": {
            "signatureIdentity": {
                "issuer": "https://token.actions.githubusercontent.com",
                "repository": "Noirero/Miyorare-Source-Packs",
                "workflow": ".github/workflows/source-pack-release-seal.yml",
            }
        },
    }


def manifest(minimum=70, maximum=None):
    return {
        "schema": 3,
        "version": "1.2.3",
        "tag": TAG,
        "compatibility": {
            "channel": "stable",
            "tsukiApi": "1.0.5",
            "compatibilityEpoch": 1,
            "minMiyorareVersionCode": minimum,
            "maxMiyorareVersionCode": maximum,
            "requiredLogicalPacks": ["miyorare-id", "miyorare-en", "miyorare-global"],
            "releaseLockRequired": True,
        },
        "sourceRepository": "Noirero/Miyorare",
        "sourceBranch": "beta",
        "sourceCommit": "1" * 40,
        "runtimeCompatibility": {
            "repository": "Noirero/Miyorare",
            "branch": "main",
            "commit": "2" * 40,
            "versionCode": minimum,
            "tsukiApi": "1.0.5",
        },
        "upstreams": {
            "uma": "3" * 40,
            "gekkoushi": "4" * 40,
            "keiyoushi": "5" * 40,
        },
        "packs": [
            {
                "pluginId": "miyorare-id",
                "language": "id",
                "sourceCount": 2,
                "shards": [
                    {"provider": "UMA", "pluginId": "miyorare-id", "assetName": "id-uma.jar", "size": 10, "sha256": "a" * 64, "sourceCount": 1},
                    {"provider": "GEKKOUSHI", "pluginId": "miyorare-id-gekkoushi", "assetName": "id-gek.jar", "size": 11, "sha256": "b" * 64, "sourceCount": 1},
                ],
            },
            {
                "pluginId": "miyorare-en",
                "language": "en",
                "sourceCount": 2,
                "shards": [
                    {"provider": "UMA", "pluginId": "miyorare-en", "assetName": "en-uma.jar", "size": 12, "sha256": "c" * 64, "sourceCount": 1},
                    {"provider": "GEKKOUSHI", "pluginId": "miyorare-en-gekkoushi", "assetName": "en-gek.jar", "size": 13, "sha256": "d" * 64, "sourceCount": 1},
                ],
            },
            {
                "pluginId": "miyorare-global",
                "language": "all",
                "sourceCount": 2,
                "shards": [
                    {"provider": "GEKKOUSHI", "pluginId": "miyorare-global", "assetName": "global-gek.jar", "size": 14, "sha256": "e" * 64, "sourceCount": 2},
                ],
            },
        ],
    }


def fixture(minimum=70, maximum=None):
    manifest_bytes = (json.dumps(manifest(minimum, maximum), sort_keys=True) + "\n").encode()
    logical_assets = {
        "miyorare-id-pack.json": (20, "1" * 64),
        "miyorare-en-pack.json": (21, "2" * 64),
        "miyorare-global-pack.json": (22, "3" * 64),
    }
    shard_assets = {
        "id-uma.jar": (10, "a" * 64),
        "id-gek.jar": (11, "b" * 64),
        "en-uma.jar": (12, "c" * 64),
        "en-gek.jar": (13, "d" * 64),
        "global-gek.jar": (14, "e" * 64),
    }
    covered = {
        MODULE.MANIFEST_ASSET: (len(manifest_bytes), hashlib.sha256(manifest_bytes).hexdigest()),
        **logical_assets,
        **shard_assets,
    }
    lock = {
        "schemaVersion": 1,
        "kind": "MIYORARE_SOURCE_PACK_RELEASE_LOCK",
        "immutable": True,
        "sealedBeforePublish": True,
        "tag": TAG,
        "releaseManifestSha256": hashlib.sha256(manifest_bytes).hexdigest(),
        "signatureMode": "GITHUB_ARTIFACT_ATTESTATION",
        "signatureIssuer": "https://token.actions.githubusercontent.com",
        "signatureRepository": "Noirero/Miyorare-Source-Packs",
        "signatureWorkflow": ".github/workflows/source-pack-release-seal.yml",
        "assets": [
            {"name": name, "size": size, "sha256": sha}
            for name, (size, sha) in sorted(covered.items())
        ],
    }
    lock_bytes = (json.dumps(lock, sort_keys=True) + "\n").encode()
    lock_sha_bytes = (
        f"{hashlib.sha256(lock_bytes).hexdigest()}  {MODULE.LOCK_ASSET}\n"
    ).encode()
    all_assets = {
        **covered,
        MODULE.LOCK_ASSET: (len(lock_bytes), hashlib.sha256(lock_bytes).hexdigest()),
        MODULE.LOCK_SHA_ASSET: (len(lock_sha_bytes), hashlib.sha256(lock_sha_bytes).hexdigest()),
    }
    release = {
        "tag_name": TAG,
        "draft": False,
        "prerelease": False,
        "assets": [
            {"name": name, "size": size, "digest": f"sha256:{sha}"}
            for name, (size, sha) in sorted(all_assets.items())
        ],
    }
    return release, manifest_bytes, lock_bytes, lock_sha_bytes


class ReadinessTests(unittest.TestCase):
    def test_list_tags_filters_and_sorts_stable_releases(self):
        releases = [
            {"tag_name": "miyorare-sources-v1.2.3", "draft": False, "prerelease": False},
            {"tag_name": "miyorare-sources-v2.0.0", "draft": True, "prerelease": False},
            {"tag_name": "miyorare-sources-v1.10.0", "draft": False, "prerelease": False},
            {"tag_name": "other-v9.0.0", "draft": False, "prerelease": False},
        ]
        self.assertEqual(
            ["miyorare-sources-v1.10.0", "miyorare-sources-v1.2.3"],
            MODULE.list_stable_tags(releases),
        )

    def test_valid_compatible_release_passes(self):
        release, manifest_bytes, lock_bytes, lock_sha_bytes = fixture()
        result = MODULE.validate_release(
            release, manifest_bytes, lock_bytes, lock_sha_bytes, contract(), APP_VERSION
        )
        self.assertEqual(TAG, result["tag"])

    def test_future_minimum_is_incompatible(self):
        release, manifest_bytes, lock_bytes, lock_sha_bytes = fixture(minimum=80)
        with self.assertRaises(MODULE.ReadinessError):
            MODULE.validate_release(
                release, manifest_bytes, lock_bytes, lock_sha_bytes, contract(), APP_VERSION
            )

    def test_missing_logical_pack_metadata_fails(self):
        release, manifest_bytes, lock_bytes, lock_sha_bytes = fixture()
        release["assets"] = [
            item for item in release["assets"] if item["name"] != "miyorare-en-pack.json"
        ]
        with self.assertRaises(MODULE.ReadinessError):
            MODULE.validate_release(
                release, manifest_bytes, lock_bytes, lock_sha_bytes, contract(), APP_VERSION
            )

    def test_tampered_lock_checksum_fails(self):
        release, manifest_bytes, lock_bytes, lock_sha_bytes = fixture()
        tampered = ("0" * 64 + f"  {MODULE.LOCK_ASSET}\n").encode()
        with self.assertRaises(MODULE.ReadinessError):
            MODULE.validate_release(
                release, manifest_bytes, lock_bytes, tampered, contract(), APP_VERSION
            )

    def test_missing_github_digest_fails(self):
        release, manifest_bytes, lock_bytes, lock_sha_bytes = fixture()
        release = copy.deepcopy(release)
        release["assets"][0]["digest"] = None
        with self.assertRaises(MODULE.ReadinessError):
            MODULE.validate_release(
                release, manifest_bytes, lock_bytes, lock_sha_bytes, contract(), APP_VERSION
            )


if __name__ == "__main__":
    unittest.main()
