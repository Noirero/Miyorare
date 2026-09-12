#!/usr/bin/env python3
"""Create one logical Miyorare-ID/EN manifest from independently built source shards."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(message)


def load_metadata(path: Path, expected_shard: str) -> dict:
    if not path.is_file():
        fail(f"Shard metadata missing: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != 2:
        fail(f"Unsupported shard metadata schema in {path}")
    if data.get("shard") != expected_shard:
        fail(f"Expected {expected_shard} shard metadata, got {data.get('shard')!r}")
    names = data.get("sourceNames") or []
    if not names or len(names) != len(set(names)):
        fail(f"Shard {expected_shard} has an invalid source list")
    return data


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--pack", choices=("id", "en"), required=True)
    parser.add_argument("--uma-metadata", type=Path, required=True)
    parser.add_argument("--gekkoushi-metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = json.loads(args.manifest.read_text(encoding="utf-8"))
    pack = root["packs"][args.pack]
    uma = load_metadata(args.uma_metadata, "uma")
    gekkoushi = load_metadata(args.gekkoushi_metadata, "gekkoushi")

    language = pack["language"]
    for shard in (uma, gekkoushi):
        if shard.get("logicalPackId") != pack["pluginId"]:
            fail("Shard belongs to a different logical pack")
        if shard.get("language") != language:
            fail("Shard language differs from logical pack language")

    uma_names = set(uma["sourceNames"])
    gekkoushi_names = set(gekkoushi["sourceNames"])
    overlap = sorted(uma_names & gekkoushi_names)
    if overlap:
        fail("Logical pack contains duplicate exposed runtime source keys: " + ", ".join(overlap))

    skipped = set(gekkoushi.get("skippedExistingSourceNames") or [])
    if not skipped.issubset(uma_names):
        fail("Gekkoushi skipped-source metadata is not fully owned by UMA")

    shards = []
    for provider, data in (("UMA", uma), ("GEKKOUSHI", gekkoushi)):
        shards.append(
            {
                "provider": provider,
                "pluginId": data["pluginId"],
                "displayName": data["displayName"],
                "assetName": data["assetName"],
                "sourceCount": data["sourceCount"],
                "sourceNames": data["sourceNames"],
                "upstreams": data.get("upstreams") or [],
            }
        )

    all_names = sorted(uma_names | gekkoushi_names)
    logical = {
        "schema": 1,
        "packId": pack["pluginId"],
        "displayName": pack["displayName"],
        "language": language,
        "tsukiApi": root["tsukiApi"],
        "sourceCount": len(all_names),
        "sourceNames": all_names,
        "deduplication": {
            "priority": ["UMA", "GEKKOUSHI"],
            "runtimeKeyBased": True,
            "gekkoushiSkippedBecauseUmaExists": sorted(skipped),
        },
        "shards": shards,
    }

    args.output.mkdir(parents=True, exist_ok=True)
    output = args.output / f"miyorare-{args.pack}-pack.json"
    output.write_text(json.dumps(logical, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(
        f"Finalized logical {pack['displayName']}: {len(uma_names)} UMA + "
        f"{len(gekkoushi_names)} Gekkoushi = {len(all_names)} unique sources"
    )


if __name__ == "__main__":
    main()
