#!/usr/bin/env python3
"""Fail-closed readiness validation for public stable Miyorare Source Pack releases."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

TAG_RE = re.compile(r"^miyorare-sources-v(\d+)\.(\d+)\.(\d+)$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
MANIFEST_ASSET = "miyorare-source-packs.json"
LOCK_ASSET = "miyorare-release-lock.json"
LOCK_SHA_ASSET = "miyorare-release-lock.sha256"
LOGICAL_PACK_ASSETS = {
    "miyorare-id": "miyorare-id-pack.json",
    "miyorare-en": "miyorare-en-pack.json",
    "miyorare-global": "miyorare-global-pack.json",
}
SNAPSHOT_DOMAIN = b"miyorare-compatibility-snapshot-v1\n"
REQUIRED_UPSTREAMS = ("uma", "gekkoushi", "keiyoushi")


class ReadinessError(ValueError):
    pass


def load_json(path: str | Path) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _require_dict(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ReadinessError(f"{name} must be an object")
    return value


def _require_list(value: Any, name: str) -> list[Any]:
    if not isinstance(value, list):
        raise ReadinessError(f"{name} must be an array")
    return value


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha40(value: Any, name: str) -> str:
    if not isinstance(value, str) or not HEX40.fullmatch(value.lower()):
        raise ReadinessError(f"{name} must be a 40-character git SHA")
    return value.lower()


def _hex64(value: Any, name: str) -> str:
    if not isinstance(value, str) or not HEX64.fullmatch(value.lower()):
        raise ReadinessError(f"{name} must be a SHA-256 digest")
    return value.lower()


def compatibility_snapshot_id(
    contract_sha256: str,
    runtime_commit: str,
    builder_commit: str,
    farm_commit: str,
    upstreams: dict[str, str],
) -> str:
    payload = {
        "schemaVersion": 1,
        "contractSha256": _hex64(contract_sha256, "compatibilitySnapshot.contractSha256"),
        "runtimeCommit": _sha40(runtime_commit, "runtimeCompatibility.commit"),
        "builderCommit": _sha40(builder_commit, "sourceCommit"),
        "farmCommit": _sha40(farm_commit, "compatibilitySnapshot.farmCommit"),
        "providerCommits": {
            name: _sha40(upstreams.get(name), f"upstreams.{name}")
            for name in REQUIRED_UPSTREAMS
        },
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(SNAPSHOT_DOMAIN + canonical).hexdigest()


def _semver(tag: str) -> tuple[int, int, int]:
    match = TAG_RE.fullmatch(tag)
    if not match:
        raise ReadinessError(f"invalid Source Pack release tag: {tag}")
    return tuple(int(part) for part in match.groups())


def list_stable_tags(releases: Any) -> list[str]:
    rows = _require_list(releases, "GitHub releases payload")
    tags: list[tuple[tuple[int, int, int], str]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        if row.get("draft") is True or row.get("prerelease") is True:
            continue
        tag = row.get("tag_name")
        if not isinstance(tag, str) or TAG_RE.fullmatch(tag) is None:
            continue
        tags.append((_semver(tag), tag))
    tags.sort(reverse=True)
    return [tag for _, tag in tags]


def _release_assets(release: dict[str, Any]) -> dict[str, dict[str, Any]]:
    assets = _require_list(release.get("assets"), "release.assets")
    result: dict[str, dict[str, Any]] = {}
    for raw in assets:
        item = _require_dict(raw, "release asset")
        name = item.get("name")
        size = item.get("size")
        digest = item.get("digest")
        if not isinstance(name, str) or not name:
            raise ReadinessError("release asset name is missing")
        if name in result:
            raise ReadinessError(f"duplicate release asset: {name}")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise ReadinessError(f"release asset size is invalid: {name}")
        if not isinstance(digest, str) or not digest.startswith("sha256:"):
            raise ReadinessError(f"GitHub SHA-256 digest missing for {name}")
        sha = _hex64(digest.removeprefix("sha256:"), f"release asset digest for {name}")
        result[name] = {"size": size, "sha256": sha}
    return result


def validate_release(
    release: dict[str, Any],
    manifest_bytes: bytes,
    lock_bytes: bytes,
    lock_sha_bytes: bytes,
    contract: dict[str, Any],
    app_version_code: int,
    contract_sha256: str,
) -> dict[str, Any]:
    if release.get("draft") is not False or release.get("prerelease") is not False:
        raise ReadinessError("Source Pack release is not a public stable release")
    tag = release.get("tag_name")
    if not isinstance(tag, str):
        raise ReadinessError("Source Pack release tag is missing")
    _semver(tag)
    assets = _release_assets(release)
    for required in (MANIFEST_ASSET, LOCK_ASSET, LOCK_SHA_ASSET):
        if required not in assets:
            raise ReadinessError(f"required release asset missing: {required}")

    manifest = _require_dict(json.loads(manifest_bytes.decode("utf-8")), "release manifest")
    fmt = _require_dict(contract.get("format"), "contract.format")
    compatibility_contract = _require_dict(contract.get("compatibility"), "contract.compatibility")
    consumer = _require_dict(contract.get("consumer"), "contract.consumer")
    trust = _require_dict(contract.get("trust"), "contract.trust")
    producer = _require_dict(contract.get("producer"), "contract.producer")

    if manifest.get("schema") != fmt.get("releaseManifestSchema"):
        raise ReadinessError("unsupported Source Pack release manifest schema")
    version = manifest.get("version")
    if not isinstance(version, str) or tag != f"miyorare-sources-v{version}":
        raise ReadinessError("release manifest version/tag mismatch")
    if manifest.get("tag") != tag:
        raise ReadinessError("release manifest tag mismatch")
    if manifest.get("sourceRepository") != consumer.get("repository"):
        raise ReadinessError("Source Pack source repository mismatch")
    if manifest.get("sourceBranch") != consumer.get("sourceBranchForPackBuilds"):
        raise ReadinessError("Source Pack source branch mismatch")
    source_commit = _sha40(manifest.get("sourceCommit"), "release manifest sourceCommit")

    compatibility = _require_dict(manifest.get("compatibility"), "manifest.compatibility")
    if compatibility.get("channel") != compatibility_contract.get("stableChannel"):
        raise ReadinessError("Source Pack channel is incompatible")
    if compatibility.get("tsukiApi") != compatibility_contract.get("tsukiApi"):
        raise ReadinessError("Source Pack Tsuki API is incompatible")
    if compatibility.get("compatibilityEpoch") != compatibility_contract.get("compatibilityEpoch"):
        raise ReadinessError("Source Pack compatibility epoch is incompatible")
    if compatibility.get("releaseLockRequired") is not True:
        raise ReadinessError("Source Pack release lock is not required by manifest")

    minimum = compatibility.get("minMiyorareVersionCode")
    maximum = compatibility.get("maxMiyorareVersionCode")
    if not isinstance(minimum, int) or isinstance(minimum, bool) or minimum <= 0:
        raise ReadinessError("Source Pack minimum Miyorare versionCode is invalid")
    if maximum is not None and (
        not isinstance(maximum, int) or isinstance(maximum, bool) or maximum < minimum
    ):
        raise ReadinessError("Source Pack maximum Miyorare versionCode is invalid")
    if app_version_code < minimum or (maximum is not None and app_version_code > maximum):
        raise ReadinessError(
            f"Source Pack {tag} does not cover Miyorare versionCode {app_version_code}"
        )

    required_packs = fmt.get("requiredLogicalPacks")
    if not isinstance(required_packs, list) or not all(isinstance(x, str) and x for x in required_packs):
        raise ReadinessError("contract requiredLogicalPacks is invalid")
    if set(compatibility.get("requiredLogicalPacks", [])) != set(required_packs):
        raise ReadinessError("Source Pack required logical pack set is incompatible")

    runtime = _require_dict(manifest.get("runtimeCompatibility"), "manifest.runtimeCompatibility")
    if runtime.get("repository") != consumer.get("repository"):
        raise ReadinessError("runtime compatibility repository mismatch")
    if runtime.get("branch") != consumer.get("stableCompatibilityBranch"):
        raise ReadinessError("runtime compatibility branch mismatch")
    runtime_commit = _sha40(runtime.get("commit"), "runtime compatibility commit")
    if runtime.get("versionCode") != minimum:
        raise ReadinessError("runtime compatibility versionCode does not match minimum")
    if runtime.get("tsukiApi") != compatibility.get("tsukiApi"):
        raise ReadinessError("runtime compatibility Tsuki API mismatch")

    upstreams_raw = _require_dict(manifest.get("upstreams"), "manifest.upstreams")
    if set(upstreams_raw) != set(REQUIRED_UPSTREAMS):
        raise ReadinessError("Source Pack upstream set is incompatible")
    upstreams = {
        name: _sha40(upstreams_raw.get(name), f"upstreams.{name}")
        for name in REQUIRED_UPSTREAMS
    }

    snapshot = _require_dict(manifest.get("compatibilitySnapshot"), "manifest.compatibilitySnapshot")
    if snapshot.get("schemaVersion") != 1 or snapshot.get("algorithm") != "sha256":
        raise ReadinessError("compatibility snapshot schema/algorithm mismatch")
    snapshot_contract_sha = _hex64(
        snapshot.get("contractSha256"),
        "compatibilitySnapshot.contractSha256",
    )
    expected_contract_sha = _hex64(contract_sha256, "local contract SHA-256")
    if snapshot_contract_sha != expected_contract_sha:
        raise ReadinessError("compatibility snapshot contract SHA-256 mismatch")
    farm_commit = _sha40(snapshot.get("farmCommit"), "compatibilitySnapshot.farmCommit")
    snapshot_id = _hex64(manifest.get("compatibilitySnapshotId"), "compatibilitySnapshotId")
    expected_snapshot_id = compatibility_snapshot_id(
        snapshot_contract_sha,
        runtime_commit,
        source_commit,
        farm_commit,
        upstreams,
    )
    if snapshot_id != expected_snapshot_id:
        raise ReadinessError("compatibilitySnapshotId does not match immutable compatibility inputs")

    packs = _require_list(manifest.get("packs"), "manifest.packs")
    parsed_packs: dict[str, dict[str, Any]] = {}
    for raw in packs:
        pack = _require_dict(raw, "manifest pack")
        plugin_id = pack.get("pluginId")
        if not isinstance(plugin_id, str) or not plugin_id:
            raise ReadinessError("logical pack pluginId missing")
        if plugin_id in parsed_packs:
            raise ReadinessError(f"duplicate logical pack: {plugin_id}")
        source_count = pack.get("sourceCount")
        if not isinstance(source_count, int) or isinstance(source_count, bool) or source_count <= 0:
            raise ReadinessError(f"logical pack has no sources: {plugin_id}")
        shards = _require_list(pack.get("shards"), f"{plugin_id}.shards")
        if not shards:
            raise ReadinessError(f"logical pack has no shards: {plugin_id}")
        shard_names: set[str] = set()
        for raw_shard in shards:
            shard = _require_dict(raw_shard, f"{plugin_id} shard")
            asset_name = shard.get("assetName")
            size = shard.get("size")
            digest = shard.get("sha256")
            if not isinstance(asset_name, str) or not asset_name or asset_name in shard_names:
                raise ReadinessError(f"invalid or duplicate shard asset in {plugin_id}")
            shard_names.add(asset_name)
            if asset_name not in assets:
                raise ReadinessError(f"release shard asset missing: {asset_name}")
            if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
                raise ReadinessError(f"invalid shard size: {asset_name}")
            sha = _hex64(digest, f"manifest shard digest for {asset_name}")
            if assets[asset_name]["size"] != size or assets[asset_name]["sha256"] != sha:
                raise ReadinessError(f"release shard metadata mismatch: {asset_name}")
        parsed_packs[plugin_id] = pack

    if set(parsed_packs) != set(required_packs):
        raise ReadinessError("release is missing required logical packs")
    for plugin_id in required_packs:
        metadata_asset = LOGICAL_PACK_ASSETS.get(plugin_id)
        if not metadata_asset or metadata_asset not in assets:
            raise ReadinessError(f"logical pack metadata asset missing: {plugin_id}")

    manifest_sha = _sha256(manifest_bytes)
    if assets[MANIFEST_ASSET]["sha256"] != manifest_sha:
        raise ReadinessError("GitHub manifest digest does not match downloaded manifest")

    checksum_parts = lock_sha_bytes.decode("utf-8").strip().split()
    if len(checksum_parts) < 2 or checksum_parts[1] != LOCK_ASSET:
        raise ReadinessError("invalid release-lock checksum file")
    if _hex64(checksum_parts[0], "release-lock checksum") != _sha256(lock_bytes):
        raise ReadinessError("release-lock SHA-256 mismatch")
    if assets[LOCK_ASSET]["sha256"] != _sha256(lock_bytes):
        raise ReadinessError("GitHub release-lock digest mismatch")
    if assets[LOCK_SHA_ASSET]["sha256"] != _sha256(lock_sha_bytes):
        raise ReadinessError("GitHub release-lock checksum asset digest mismatch")

    lock = _require_dict(json.loads(lock_bytes.decode("utf-8")), "release lock")
    if lock.get("schemaVersion") != 1 or lock.get("kind") != "MIYORARE_SOURCE_PACK_RELEASE_LOCK":
        raise ReadinessError("release lock schema/kind mismatch")
    if lock.get("immutable") is not True or lock.get("sealedBeforePublish") is not True:
        raise ReadinessError("release lock is not immutable and sealed-before-publish")
    if lock.get("tag") != tag:
        raise ReadinessError("release lock tag mismatch")
    if _hex64(lock.get("releaseManifestSha256"), "release lock manifest digest") != manifest_sha:
        raise ReadinessError("release manifest does not match release lock")
    lock_snapshot_id = _hex64(lock.get("compatibilitySnapshotId"), "release lock compatibilitySnapshotId")
    if lock_snapshot_id != snapshot_id:
        raise ReadinessError("release lock compatibility snapshot does not match manifest")

    identity = _require_dict(trust.get("signatureIdentity"), "contract.trust.signatureIdentity")
    if lock.get("signatureMode") != "GITHUB_ARTIFACT_ATTESTATION":
        raise ReadinessError("release lock signature mode mismatch")
    if lock.get("signatureIssuer") != identity.get("issuer"):
        raise ReadinessError("release lock signature issuer mismatch")
    if lock.get("signatureRepository") != identity.get("repository"):
        raise ReadinessError("release lock signature repository mismatch")
    if lock.get("signatureWorkflow") != identity.get("workflow"):
        raise ReadinessError("release lock signature workflow mismatch")
    if identity.get("repository") != producer.get("repository"):
        raise ReadinessError("contract producer/signature repository mismatch")

    locked: dict[str, dict[str, Any]] = {}
    for raw in _require_list(lock.get("assets"), "release lock assets"):
        item = _require_dict(raw, "release lock asset")
        name = item.get("name")
        size = item.get("size")
        sha = _hex64(item.get("sha256"), f"release lock digest for {name}")
        if not isinstance(name, str) or not name or name in locked or name in {LOCK_ASSET, LOCK_SHA_ASSET}:
            raise ReadinessError("invalid or duplicate release-lock asset")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise ReadinessError(f"invalid release-lock asset size: {name}")
        locked[name] = {"size": size, "sha256": sha}

    covered_assets = {
        name: metadata for name, metadata in assets.items()
        if name not in {LOCK_ASSET, LOCK_SHA_ASSET}
    }
    if set(locked) != set(covered_assets):
        raise ReadinessError("release-lock asset set does not match GitHub release asset set")
    for name, metadata in locked.items():
        if metadata != covered_assets[name]:
            raise ReadinessError(f"release-lock metadata differs from GitHub for {name}")
    if locked.get(MANIFEST_ASSET, {}).get("sha256") != manifest_sha:
        raise ReadinessError("release lock does not bind the exact release manifest")

    return {
        "tag": tag,
        "appVersionCode": app_version_code,
        "minMiyorareVersionCode": minimum,
        "maxMiyorareVersionCode": maximum,
        "logicalPacks": sorted(parsed_packs),
        "assetCount": len(assets),
        "compatibilitySnapshotId": snapshot_id,
        "farmCommit": farm_commit,
        "contractSha256": snapshot_contract_sha,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    list_parser = sub.add_parser("list-tags")
    list_parser.add_argument("--releases", required=True, type=Path)

    validate_parser = sub.add_parser("validate")
    validate_parser.add_argument("--release", required=True, type=Path)
    validate_parser.add_argument("--manifest", required=True, type=Path)
    validate_parser.add_argument("--lock", required=True, type=Path)
    validate_parser.add_argument("--lock-sha256", required=True, type=Path)
    validate_parser.add_argument("--contract", required=True, type=Path)
    validate_parser.add_argument("--app-version-code", required=True, type=int)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "list-tags":
            for tag in list_stable_tags(load_json(args.releases)):
                print(tag)
            return 0

        if args.app_version_code <= 0:
            raise ReadinessError("app versionCode must be positive")
        contract_bytes = args.contract.read_bytes()
        result = validate_release(
            _require_dict(load_json(args.release), "GitHub release"),
            args.manifest.read_bytes(),
            args.lock.read_bytes(),
            args.lock_sha256.read_bytes(),
            _require_dict(json.loads(contract_bytes.decode("utf-8")), "compatibility contract"),
            args.app_version_code,
            _sha256(contract_bytes),
        )
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (ReadinessError, OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        print(f"error: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
