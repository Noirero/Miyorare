#!/usr/bin/env python3
"""Verify and finalize the Gekkoushi-only Miyorare Global shard."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import zipfile
from pathlib import Path


GENERATED_SOURCE_RE = re.compile(
    r'^\s*(?:@Deprecated\("(?:\\.|[^"\\])*"\)\s*)?([A-Z_][A-Z0-9_]{3,})\(',
    re.MULTILINE,
)


def fail(message: str) -> None:
    raise SystemExit(message)


def generated_source_file(upstream: Path) -> Path:
    generated_root = upstream / "build/generated/ksp"
    candidates = list(generated_root.rglob("MangaSource.kt")) if generated_root.is_dir() else []
    if len(candidates) != 1:
        fail(f"Expected exactly one generated MangaSource.kt, found {len(candidates)}")
    return candidates[0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    pack = manifest["packs"]["global"]
    provenance_file = args.upstream / "miyorare-pack.json"
    if not provenance_file.is_file():
        fail("Prepared global shard provenance metadata is missing")
    provenance = json.loads(provenance_file.read_text(encoding="utf-8"))
    if provenance.get("schema") != 2 or provenance.get("shard") != "gekkoushi":
        fail("Unsupported global shard provenance")
    if provenance.get("logicalPackId") != pack["pluginId"] or provenance.get("language") != "all":
        fail("Global shard provenance does not match Miyorare Global")

    exposed_names = provenance.get("sourceNames") or []
    compiled_names = provenance.get("compiledSourceNames") or []
    if not exposed_names or len(exposed_names) != len(set(exposed_names)):
        fail("Global shard has an invalid exposed source list")
    if not compiled_names or len(compiled_names) != len(set(compiled_names)):
        fail("Global shard has an invalid compiled source list")
    if not set(exposed_names).issubset(set(compiled_names)):
        fail("Every exposed global source must be compiled")

    hidden_names = provenance.get("hiddenSupportSourceNames") or []
    if set(hidden_names) != set(compiled_names) - set(exposed_names):
        fail("Global shard hidden support metadata is inconsistent")

    generated = generated_source_file(args.upstream)
    generated_names = GENERATED_SOURCE_RE.findall(generated.read_text(encoding="utf-8"))
    if set(generated_names) != set(compiled_names):
        fail("Generated KSP source set differs from global shard provenance")

    summary = args.upstream / ".github/summary.yaml"
    if not summary.is_file():
        fail("KSP summary.yaml was not generated")
    try:
        summary_count = int(summary.read_text(encoding="utf-8").strip().split(":", 1)[1].strip())
    except (IndexError, ValueError):
        fail("Unexpected KSP summary")
    if summary_count != len(generated_names):
        fail("KSP summary count differs from generated global shard")

    built_relative = provenance.get("buildArtifact")
    built = args.upstream / str(built_relative)
    if not built.is_file() or built.stat().st_size == 0:
        fail("Global source shard build artifact is missing")
    with zipfile.ZipFile(built, "r") as archive:
        if "classes.dex" not in set(archive.namelist()):
            fail("Built global plugin is not a dexed Tsuki JAR")

    license_file = args.upstream / "LICENSE"
    if not license_file.is_file():
        fail("Required Gekkoushi license is missing")
    with zipfile.ZipFile(built, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/licenses/Gekkoushi-GPL-3.0.txt", license_file.read_text(encoding="utf-8"))
        archive.writestr("META-INF/miyorare-pack.json", provenance_file.read_text(encoding="utf-8"))

    asset_name = provenance.get("assetName")
    if not isinstance(asset_name, str) or not asset_name.endswith(".jar"):
        fail("Global shard has an invalid asset name")
    args.output.mkdir(parents=True, exist_ok=True)
    final_jar = args.output / asset_name
    shutil.copy2(built, final_jar)
    digest = hashlib.sha256(final_jar.read_bytes()).hexdigest()
    (args.output / f"{asset_name}.sha256").write_text(f"{digest}  {asset_name}\n", encoding="utf-8")
    shutil.copy2(provenance_file, args.output / "miyorare-global-pack-shard.json")
    print(f"Finalized {final_jar} ({len(exposed_names)} exposed global source(s), sha256={digest})")


if __name__ == "__main__":
    main()
