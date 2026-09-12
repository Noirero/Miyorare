#!/usr/bin/env python3
"""Verify, attribute and finalize one independently built Miyorare source shard JAR."""

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


def add_license(archive: zipfile.ZipFile, source: Path, archive_name: str) -> None:
    license_file = source / "LICENSE"
    if not license_file.is_file():
        fail(f"Required upstream license is missing: {license_file}")
    archive.writestr(
        f"META-INF/licenses/{archive_name}",
        license_file.read_text(encoding="utf-8"),
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--pack", choices=("id", "en"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    pack = manifest["packs"][args.pack]
    provenance_file = args.upstream / "miyorare-pack.json"
    if not provenance_file.is_file():
        fail("Prepared shard provenance metadata is missing")
    provenance = json.loads(provenance_file.read_text(encoding="utf-8"))
    if provenance.get("schema") != 2:
        fail("Unsupported shard provenance schema")
    if provenance.get("logicalPackId") != pack["pluginId"]:
        fail("Shard provenance belongs to a different logical pack")
    if provenance.get("language") != pack["language"]:
        fail("Shard provenance language differs from logical pack")

    exposed_names = provenance.get("sourceNames") or []
    exposed_count = provenance.get("sourceCount")
    if not exposed_names or exposed_count != len(exposed_names):
        fail("Prepared shard provenance has an invalid exposed source list")
    if len(exposed_names) != len(set(exposed_names)):
        fail("Prepared shard provenance contains duplicate exposed source names")

    compiled_names = provenance.get("compiledSourceNames") or exposed_names
    compiled_count = provenance.get("compiledSourceCount", len(compiled_names))
    if not compiled_names or compiled_count != len(compiled_names):
        fail("Prepared shard provenance has an invalid compiled source list")
    if len(compiled_names) != len(set(compiled_names)):
        fail("Prepared shard provenance contains duplicate compiled source names")

    exposed_set = set(exposed_names)
    compiled_set = set(compiled_names)
    if not exposed_set.issubset(compiled_set):
        fail("Every exposed source must exist in the compiled source set")

    hidden_names = provenance.get("hiddenSupportSourceNames") or []
    hidden_count = provenance.get("hiddenSupportSourceCount", len(hidden_names))
    if hidden_count != len(hidden_names) or set(hidden_names) != compiled_set - exposed_set:
        fail("Prepared shard provenance has an inconsistent hidden support source list")

    generated = generated_source_file(args.upstream)
    generated_names = GENERATED_SOURCE_RE.findall(generated.read_text(encoding="utf-8"))
    if len(generated_names) != len(set(generated_names)):
        fail("KSP generated duplicate runtime source names")
    missing = sorted(compiled_set - set(generated_names))
    unexpected = sorted(set(generated_names) - compiled_set)
    if missing or unexpected:
        details = []
        if missing:
            details.append(f"missing: {', '.join(missing)}")
        if unexpected:
            details.append(f"unexpected: {', '.join(unexpected)}")
        fail(
            "Generated KSP source set differs from compiled provenance ("
            + "; ".join(details)
            + ")"
        )

    summary = args.upstream / ".github/summary.yaml"
    if not summary.is_file():
        fail("KSP summary.yaml was not generated")
    summary_line = summary.read_text(encoding="utf-8").strip()
    try:
        summary_count = int(summary_line.split(":", 1)[1].strip())
    except (IndexError, ValueError):
        fail(f"Unexpected KSP summary: {summary_line!r}")
    if summary_count != len(generated_names):
        fail(
            f"KSP summary reports {summary_count}, "
            f"generated enum contains {len(generated_names)}"
        )

    built_relative = provenance.get("buildArtifact")
    if not isinstance(built_relative, str) or not built_relative:
        fail("Shard provenance has no build artifact path")
    built = args.upstream / built_relative
    if not built.is_file() or built.stat().st_size == 0:
        fail(f"Source shard build did not produce {built_relative}")

    with zipfile.ZipFile(built, "r") as archive:
        names = set(archive.namelist())
    if "classes.dex" not in names:
        fail("Built plugin is not a dexed Tsuki JAR (classes.dex missing)")

    build_upstream = provenance.get("buildUpstream") or {}
    build_name = build_upstream.get("name", "UPSTREAM")
    safe_name = re.sub(r"[^A-Za-z0-9._-]+", "-", build_name).strip("-") or "UPSTREAM"
    with zipfile.ZipFile(built, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        add_license(archive, args.upstream, f"{safe_name}-GPL-3.0.txt")
        archive.writestr(
            "META-INF/miyorare-pack.json",
            provenance_file.read_text(encoding="utf-8"),
        )

    asset_name = provenance.get("assetName")
    plugin_id = provenance.get("pluginId")
    if not isinstance(asset_name, str) or not asset_name.endswith(".jar"):
        fail("Shard provenance has an invalid asset name")
    if not isinstance(plugin_id, str) or not plugin_id:
        fail("Shard provenance has an invalid plugin id")

    args.output.mkdir(parents=True, exist_ok=True)
    final_jar = args.output / asset_name
    shutil.copy2(built, final_jar)
    digest = hashlib.sha256(final_jar.read_bytes()).hexdigest()
    (args.output / f"{asset_name}.sha256").write_text(
        f"{digest}  {asset_name}\n",
        encoding="utf-8",
    )
    shutil.copy2(provenance_file, args.output / f"{plugin_id}-pack.json")
    print(
        f"Finalized {final_jar} ({len(exposed_names)} exposed / "
        f"{len(generated_names)} compiled runtime sources, sha256={digest})"
    )


if __name__ == "__main__":
    main()
