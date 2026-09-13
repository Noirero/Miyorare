#!/usr/bin/env python3
"""Prepare the Gekkoushi-only Miyorare Global shard.

The upstream checkout stays intact. Only visibility/provenance metadata is written so selected
locale-independent parsers can be exposed as one global Miyorare pack without duplicating them in
Miyorare-ID or Miyorare-EN.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from prepare_gekkoushi_shard import collect_sources, fail, git_head


def prepare(manifest: Path, gekkoushi_upstream: Path, pack_name: str) -> None:
    root = json.loads(manifest.read_text(encoding="utf-8"))
    if root.get("schema") != 1:
        fail("Unsupported packs.json schema")
    pack = (root.get("packs") or {}).get(pack_name)
    if not isinstance(pack, dict):
        fail(f"Unknown pack: {pack_name}")
    if pack.get("language") != "all":
        fail("Global Gekkoushi pack must use language 'all'")

    gekkoushi_meta = (root.get("additionalUpstreams") or {}).get("gekkoushi")
    if not isinstance(gekkoushi_meta, dict):
        fail("packs.json is missing additionalUpstreams.gekkoushi")
    expected_gekkoushi = gekkoushi_meta["commit"]
    actual_gekkoushi = git_head(gekkoushi_upstream)
    if actual_gekkoushi != expected_gekkoushi:
        fail(f"Gekkoushi HEAD mismatch: expected {expected_gekkoushi}, got {actual_gekkoushi}")

    site_root = gekkoushi_upstream / "src/main/kotlin/tsuki/site"
    if not site_root.is_dir():
        fail(f"Gekkoushi site directory not found: {site_root}")
    compiled_names, locales, parser_files, source_icons = collect_sources(site_root)
    compiled_set = set(compiled_names)

    selected = pack.get("gekkoushiSources") or []
    if not selected or len(selected) != len(set(selected)) or not all(isinstance(name, str) and name for name in selected):
        fail(f"Pack {pack_name} must contain a non-empty unique gekkoushiSources list")
    selected_set = set(selected)
    missing = sorted(selected_set - compiled_set)
    if missing:
        fail("Global pack references missing Gekkoushi sources: " + ", ".join(missing))
    wrong_locale = sorted(name for name in selected_set if locales.get(name) != "all")
    if wrong_locale:
        fail("Global pack may expose only locale-independent sources: " + ", ".join(wrong_locale))

    shutil.rmtree(gekkoushi_upstream / "build", ignore_errors=True)
    summary = gekkoushi_upstream / ".github/summary.yaml"
    if summary.exists():
        summary.unlink()

    exposed = selected_set
    hidden = compiled_set - exposed
    exposed_icons = {name: url for name, url in source_icons.items() if name in exposed}
    plugin_id = pack["pluginId"]
    asset_name = f"miyorare-{pack_name}-gekkoushi.jar"
    metadata = {
        "schema": 2,
        "logicalPackId": pack["pluginId"],
        "logicalDisplayName": pack["displayName"],
        "shard": "gekkoushi",
        "pluginId": plugin_id,
        "displayName": f"{pack['displayName']} / Gekkoushi",
        "language": "all",
        "assetName": asset_name,
        "tsukiApi": root["tsukiApi"],
        "buildUpstream": gekkoushi_meta,
        "upstreams": [gekkoushi_meta],
        "sourceFilesCount": len(set(parser_files.get("all", []))),
        "sourceCount": len(exposed),
        "sourceNames": sorted(exposed),
        "sourceIcons": dict(sorted(exposed_icons.items())),
        "compiledSourceCount": len(compiled_set),
        "compiledSourceNames": sorted(compiled_set),
        "hiddenSupportSourceCount": len(hidden),
        "hiddenSupportSourceNames": sorted(hidden),
        "targetLanguageSourceCount": len(exposed),
        "targetLanguageSourceNames": sorted(exposed),
        "skippedExistingSourceCount": 0,
        "skippedExistingSourceNames": [],
        "dedupeAuthority": "global-single-owner",
        "buildArtifact": "build/libs/gekkoushi.jar",
    }
    (gekkoushi_upstream / "miyorare-pack.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(
        f"Prepared {pack['displayName']} Gekkoushi shard: {len(exposed)} exposed global source(s), "
        f"kept {len(hidden)} compiled support sources without modifying Gekkoushi code"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--gekkoushi-upstream", type=Path, required=True)
    parser.add_argument("--pack", choices=("global",), default="global")
    args = parser.parse_args()
    prepare(args.manifest.resolve(), args.gekkoushi_upstream.resolve(), args.pack)


if __name__ == "__main__":
    main()
